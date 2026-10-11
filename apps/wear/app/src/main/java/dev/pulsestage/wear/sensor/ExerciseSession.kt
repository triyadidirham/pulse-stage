package dev.pulsestage.wear.sensor

import java.util.concurrent.CompletableFuture

/** All methods and future completions run on the same executor (the main looper on Wear OS). */
internal interface ExerciseSessionPort {
    enum class Owner { NONE, OUR_APP, OTHER_APP, UNKNOWN }
    fun prepare(): CompletableFuture<Unit>
    fun owner(): CompletableFuture<Owner>
    fun register(): CompletableFuture<Unit>
    fun start(): CompletableFuture<Unit>
    fun end(): CompletableFuture<Unit>
    fun clear(): CompletableFuture<Unit>
}

/** Serializes Start/Stop, including Stop while registration or start is still in flight. */
internal class ExerciseSession(
    private val port: ExerciseSessionPort,
    private val onStarted: () -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private var started = false
    private var stopping = false
    private var pending = false
    private var registered = false
    private var mayOwnExercise = false
    private var closing = false
    private val closed = CompletableFuture<Unit>()

    fun start() {
        check(!started && !stopping) { "Create a new session before starting again." }
        started = true
        run(port::prepare) {
            if (stopping) close() else checkOwner(registerFirst = true)
        }
    }

    fun stop(): CompletableFuture<Unit> {
        stopping = true
        if (!pending) close()
        return closed
    }

    private fun checkOwner(registerFirst: Boolean) {
        run(port::owner) { owner ->
            mayOwnExercise = owner == ExerciseSessionPort.Owner.OUR_APP
            if (stopping) { close(); return@run }
            when (owner) {
                ExerciseSessionPort.Owner.OTHER_APP -> fail(IllegalStateException(
                    "Ada sesi olahraga aplikasi lain. Hentikan sesi tersebut sebelum Start PulseStage."
                ))
                ExerciseSessionPort.Owner.UNKNOWN -> fail(IllegalStateException(
                    "Status sesi Wear OS belum diketahui. Coba Start lagi."
                ))
                else -> if (registerFirst) {
                    registered = true // Clear even when registration fails partway through.
                    run(port::register) {
                        // Recheck immediately before starting; never intentionally replace another app.
                        if (stopping) close() else checkOwner(registerFirst = false)
                    }
                } else if (owner == ExerciseSessionPort.Owner.OUR_APP) {
                    onStarted() // Reattach to this app's session after a process restart.
                } else {
                    mayOwnExercise = true // An IPC failure can still mean the remote start happened.
                    run(port::start) {
                        if (stopping) close() else onStarted()
                    }
                }
            }
        }
    }

    private fun <T> run(action: () -> CompletableFuture<T>, success: (T) -> Unit) {
        pending = true
        val future = try { action() } catch (error: Exception) {
            CompletableFuture<T>().apply { completeExceptionally(error) }
        }
        future.whenComplete { value, error ->
            pending = false
            if (error != null) {
                if (stopping) close() else fail(error.cause ?: error)
            } else success(value)
        }
    }

    private fun fail(error: Throwable) {
        stopping = true
        onFailure(error)
        if (!pending) close()
    }

    private fun close() {
        if (closing || closed.isDone || pending) return
        closing = true
        val end = if (mayOwnExercise) safe(port::end) else CompletableFuture.completedFuture(Unit)
        end.whenComplete { _, endError ->
            val clear = if (registered) safe(port::clear) else CompletableFuture.completedFuture(Unit)
            clear.whenComplete { _, clearError ->
                val error = endError ?: clearError
                if (error == null) closed.complete(Unit) else closed.completeExceptionally(error)
            }
        }
    }

    private fun safe(action: () -> CompletableFuture<Unit>): CompletableFuture<Unit> = try { action() }
    catch (error: Exception) { CompletableFuture<Unit>().apply { completeExceptionally(error) } }
}
