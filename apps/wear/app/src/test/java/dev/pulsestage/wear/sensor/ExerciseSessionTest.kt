package dev.pulsestage.wear.sensor

import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class ExerciseSessionTest {
    private class Port(val hold: String? = null) : ExerciseSessionPort {
        val calls = mutableListOf<String>()
        val gate = CompletableFuture<Unit>()
        var owner = ExerciseSessionPort.Owner.NONE
        var endError: Throwable? = null
        fun step(name: String): CompletableFuture<Unit> {
            calls += name
            return if (hold == name) gate else CompletableFuture.completedFuture(Unit)
        }
        override fun prepare() = step("prepare")
        override fun owner() = step("owner").thenApply { owner }
        override fun register() = step("register")
        override fun start() = step("start").thenApply { owner = ExerciseSessionPort.Owner.OUR_APP; Unit }
        override fun end(): CompletableFuture<Unit> = step("end").thenApply {
            endError?.let { throw it }
            owner = ExerciseSessionPort.Owner.NONE
            Unit
        }
        override fun clear() = step("clear")
    }
    private class Fixture(val port: Port = Port()) {
        var ready = false
        var error: Throwable? = null
        val session = ExerciseSession(port, { ready = true }, { error = it })
    }

    @Test fun refusesAnotherAppsWorkoutBeforeRegisteringOrStarting() {
        val f = Fixture(Port().apply { owner = ExerciseSessionPort.Owner.OTHER_APP })
        f.session.start()
        assertNotNull(f.error)
        assertFalse(f.ready)
        assertFalse(f.port.calls.any { it in listOf("register", "start", "end") })
    }

    @Test fun rechecksOwnershipAfterAsyncRegistration() {
        val f = Fixture(Port("register"))
        f.session.start()
        f.port.owner = ExerciseSessionPort.Owner.OTHER_APP
        f.port.gate.complete(Unit)
        assertNotNull(f.error)
        assertFalse(f.ready)
        assertFalse("Must not replace or stop another app", f.port.calls.any { it in listOf("start", "end") })
        assertTrue(f.port.calls.contains("clear"))
    }

    @Test fun reattachesToOwnWorkoutWithoutStartingAReplacement() {
        val f = Fixture(Port().apply { owner = ExerciseSessionPort.Owner.OUR_APP })
        f.session.start()
        assertTrue(f.ready)
        assertFalse(f.port.calls.contains("start"))
        f.session.stop().join()
        assertEquals(ExerciseSessionPort.Owner.NONE, f.port.owner)
    }

    @Test fun stopDuringSetupDoesNotStartAnExerciseLater() {
        for (stage in listOf("prepare", "owner", "register")) {
            val f = Fixture(Port(stage))
            f.session.start()
            val closed = f.session.stop()
            assertFalse(closed.isDone)
            f.port.gate.complete(Unit)
            closed.join()
            assertFalse("Late start at $stage", f.port.calls.contains("start"))
            assertFalse(f.ready)
            if (stage == "register") assertTrue(f.port.calls.contains("clear"))
        }
    }

    @Test fun stopWaitsForLateStartAndThenEndsItBeforeClearingCallback() {
        val f = Fixture(Port("start"))
        f.session.start()
        val closed = f.session.stop()
        assertFalse(closed.isDone)
        f.port.gate.complete(Unit)
        closed.join()
        assertFalse(f.ready)
        assertEquals(ExerciseSessionPort.Owner.NONE, f.port.owner)
        assertEquals(listOf("end", "clear"), f.port.calls.takeLast(2))
        f.session.stop().join()
        assertEquals(1, f.port.calls.count { it == "end" })
    }

    @Test fun failedStartStillEndsPossibleRemoteSession() {
        val f = Fixture(Port("start"))
        f.session.start()
        f.port.owner = ExerciseSessionPort.Owner.OUR_APP // Remote side succeeded, local RPC failed.
        f.port.gate.completeExceptionally(IllegalStateException("IPC failure"))
        assertNotNull(f.error)
        assertEquals(ExerciseSessionPort.Owner.NONE, f.port.owner)
        assertEquals(listOf("end", "clear"), f.port.calls.takeLast(2))
    }

    @Test fun callbackIsClearedEvenWhenRegistrationOrEndFails() {
        val failedRegistration = Fixture(Port("register"))
        failedRegistration.session.start()
        failedRegistration.port.gate.completeExceptionally(IllegalStateException("registration"))
        assertNotNull(failedRegistration.error)
        assertTrue(failedRegistration.port.calls.contains("clear"))
        val failedEnd = Fixture(Port().apply { endError = IllegalStateException("end") })
        failedEnd.session.start()
        assertTrue(failedEnd.session.stop().isCompletedExceptionally)
        assertTrue(failedEnd.port.calls.contains("clear"))
    }
}
