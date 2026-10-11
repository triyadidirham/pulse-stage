package dev.pulsestage.wear.sensor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.HeartRateAccuracy
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal class HealthServicesHeartRateSource(
    context: Context,
    private val onStarted: () -> Unit,
    private val onReading: (Double, HeartRateSignal.Quality, Long) -> Unit,
    private val onAvailability: (String?) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    companion object { private const val TAG = "PulseStageHealth" }
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { main.post(it) }
    private val client = HealthServices.getClient(context.applicationContext).exerciseClient
    private var active = false
    private var exerciseStarted = false
    private var lastState: String? = null
    private var configuration: ExerciseConfig? = null
    private var registration: CompletableFuture<Unit>? = null
    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() {
            Log.i(TAG, "Exercise callback registered")
            registration?.complete(Unit)
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.w(TAG, "Exercise callback registration failed: ${throwable.javaClass.simpleName}")
            registration?.completeExceptionally(throwable)
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            if (!active) return
            val info = update.exerciseStateInfo
            val state = info.state
            if (state.toString() != lastState) {
                lastState = state.toString()
                Log.i(TAG, "Exercise state=$state; endReason=${info.endReason}")
            }
            // Ignore a cached ended update from an earlier session during registration.
            if (state.isEnded || state.isEnding) {
                if (exerciseStarted) onFailure(IllegalStateException(
                    "Sesi sensor dihentikan Wear OS (kode ${info.endReason}). Cek izin atau sesi olahraga lain."
                ))
                return
            }
            if (state.isPaused) {
                onAvailability("Sesi aktivitas dijeda oleh Wear OS. Hentikan sesi lalu Start kembali.")
                return
            }
            val points = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
            for (point in points.sortedBy { it.timeDurationFromBoot }) {
                val quality = when ((point.accuracy as? HeartRateAccuracy)?.sensorStatus) {
                    HeartRateAccuracy.SensorStatus.NO_CONTACT -> HeartRateSignal.Quality.NO_CONTACT
                    HeartRateAccuracy.SensorStatus.UNRELIABLE -> HeartRateSignal.Quality.UNRELIABLE
                    HeartRateAccuracy.SensorStatus.ACCURACY_LOW,
                    HeartRateAccuracy.SensorStatus.ACCURACY_MEDIUM,
                    HeartRateAccuracy.SensorStatus.ACCURACY_HIGH -> HeartRateSignal.Quality.RELIABLE
                    else -> HeartRateSignal.Quality.UNSPECIFIED // Optional in the Health Services API.
                }
                onReading(point.value, quality, point.timeDurationFromBoot.toNanos())
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (!active || dataType != DataType.HEART_RATE_BPM) return
            Log.i(TAG, "Heart-rate availability=$availability")
            onAvailability(when (availability) {
                DataTypeAvailability.AVAILABLE -> null
                DataTypeAvailability.ACQUIRING -> "Health Services sedang mencari sinyal… Diamkan tangan sebentar."
                DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> "Jam belum terdeteksi di pergelangan tangan."
                DataTypeAvailability.UNAVAILABLE -> "Heart rate belum tersedia dari Health Services."
                else -> "Menunggu kesiapan sensor Health Services…"
            })
        }
    }

    private val port = object : ExerciseSessionPort {
        override fun prepare(): CompletableFuture<Unit> = bridge(client.getCapabilitiesAsync()).thenApply { capabilities ->
            check(ExerciseType.WORKOUT in capabilities.supportedExerciseTypes &&
                DataType.HEART_RATE_BPM in capabilities.getExerciseTypeCapabilities(ExerciseType.WORKOUT).supportedDataTypes) {
                "Health Services belum mendukung sesi BPM pada jam ini. Perbarui Health Services dan sistem jam."
            }
            val batching = if (BatchingMode.HEART_RATE_5_SECONDS in capabilities.supportedBatchingModeOverrides) {
                setOf(BatchingMode.HEART_RATE_5_SECONDS)
            } else emptySet()
            configuration = ExerciseConfig.Builder(ExerciseType.WORKOUT)
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .setIsGpsEnabled(false)
                .setIsAutoPauseAndResumeEnabled(false)
                .setBatchingModeOverrides(batching)
                .build()
            Log.i(TAG, "Capabilities support WORKOUT heart rate; batching5s=${batching.isNotEmpty()}")
            Unit
        }

        override fun owner(): CompletableFuture<ExerciseSessionPort.Owner> = bridge(client.getCurrentExerciseInfoAsync()).thenApply {
            Log.i(TAG, "Exercise ownership=${it.exerciseTrackedStatus}")
            when (it.exerciseTrackedStatus) {
                ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS -> ExerciseSessionPort.Owner.NONE
                ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> ExerciseSessionPort.Owner.OUR_APP
                ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> ExerciseSessionPort.Owner.OTHER_APP
                else -> ExerciseSessionPort.Owner.UNKNOWN
            }
        }

        override fun register(): CompletableFuture<Unit> = CompletableFuture<Unit>().also {
            registration = it
            client.setUpdateCallback(executor, callback)
        }

        override fun start(): CompletableFuture<Unit> {
            Log.i(TAG, "Starting Health Services heart-rate session")
            return bridge(client.startExerciseAsync(checkNotNull(configuration))).thenApply { Unit }
        }

        override fun end(): CompletableFuture<Unit> {
            // The API refuses to end another app's exercise, even if ownership changes.
            Log.i(TAG, "Ending PulseStage Health Services session")
            return owner().thenCompose { owner ->
                when (owner) {
                    ExerciseSessionPort.Owner.NONE, ExerciseSessionPort.Owner.OTHER_APP -> CompletableFuture.completedFuture(Unit)
                    else -> bridge(client.endExerciseAsync()).thenApply { Unit }
                }
            }
        }

        override fun clear(): CompletableFuture<Unit> = bridge(client.clearUpdateCallbackAsync(callback)).thenApply { Unit }
    }
    private val session = ExerciseSession(port, {
        if (active) {
            exerciseStarted = true
            Log.i(TAG, "Health Services session ready")
            onStarted()
        }
    }, onFailure)

    fun start() {
        active = true
        Log.i(TAG, "Provider=HealthServices ExerciseClient; bootMs=${SystemClock.elapsedRealtime()}")
        session.start()
    }

    fun stop(): CompletableFuture<Unit> {
        active = false
        return session.stop()
    }

    private fun <T> bridge(future: ListenableFuture<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        future.addListener({
            try { result.complete(future.get()) }
            catch (error: Exception) { result.completeExceptionally(error.cause ?: error) }
        }, executor)
        return result
    }
}
