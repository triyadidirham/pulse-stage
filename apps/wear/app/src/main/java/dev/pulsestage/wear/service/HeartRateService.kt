package dev.pulsestage.wear.service

import dev.pulsestage.wear.R
import dev.pulsestage.wear.data.Api
import dev.pulsestage.wear.data.ApiException
import dev.pulsestage.wear.ui.MainActivity
import dev.pulsestage.wear.sensor.HeartRateSignal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HeartRateService : Service(), SensorEventListener {
    companion object {
        const val STOP = "dev.pulsestage.STOP"
        @Volatile var running = false
        @Volatile var bpm: Int? = null
        @Volatile var message = "Siap"
        @Volatile var lastMeasurement = 0L
        @Volatile var startFailure: String? = null
        @Volatile private var sensorMessage: String? = null
        @Volatile private var networkFailed = false
        val statusMessage: String
            get() = if (running && sensorMessage != null) {
                listOfNotNull(sensorMessage, message.takeIf { networkFailed }).joinToString("\n")
            } else message
        private const val TAG = "PulseStageSensor"
        private const val MAX_SENSOR_RETRIES = 2
    }
    private data class Sample(val bpm: Float, val measuredAt: Long, val elapsedMs: Long, val sequence: Long)
    private val latest = AtomicReference<Sample?>(null)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private lateinit var sensors: SensorManager
    private lateinit var api: Api
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId: String? = null
    private var sequence = 0L
    private var lastSent = -1L
    private var lastStatusCheck = 0L
    private var startedAt = 0L
    private val maxSessionMs = 4 * 60 * 60 * 1000L
    private val signal = HeartRateSignal()
    private var candidates = emptyList<Sensor>()
    private var activeSensor: Sensor? = null
    private var candidateIndex = -1
    private var sensorRetries = 0
    private var lastDiagnostic: String? = null
    private val sensorTick = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (now - startedAt >= maxSessionMs) {
                fail("Sesi 4 jam selesai. Tekan Start untuk sesi baru.")
                return
            }
            if (!hasSensorPermission()) {
                fail("Izin sensor dicabut. Izinkan Sensor tubuh/Detak jantung di pengaturan aplikasi.")
                return
            }
            updateSensorStatus(now)
            if (signal.needsRetry(now)) {
                if (sensorRetries >= MAX_SENSOR_RETRIES) {
                    fail("${sensorMessage ?: "Sensor tidak merespons."}\nSesi dihentikan. Cek izin sensor atau restart jam, lalu Start lagi.")
                    return
                }
                sensorRetries++
                Log.w(TAG, "No fresh reading for 45s; retry=$sensorRetries")
                try { registerSensor((candidateIndex + 1) % candidates.size) }
                catch (error: Exception) { fail(sensorError(error)); return }
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        api = Api(this)
        sensors = getSystemService(SensorManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("streaming", "Sesi berbagi BPM", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        startFailure = null
        networkFailed = false
        try {
            check(hasSensorPermission()) { "Izin sensor belum diberikan. Izinkan Sensor tubuh/Detak jantung di pengaturan aplikasi." }
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            else startForeground(1, notification)
            candidates = (listOfNotNull(
                sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE, true),
                sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE, false)
            ) + sensors.getSensorList(Sensor.TYPE_HEART_RATE)).distinct()
            check(candidates.isNotEmpty()) { "Sensor heart rate tidak tersedia untuk aplikasi ini." }
            Log.i(TAG, "Starting sensor; sdk=${Build.VERSION.SDK_INT}; candidates=${candidates.size}; permission=granted")
            running = true
            active = true
            bpm = null
            lastMeasurement = 0
            message = "Menunggu pengiriman…"
            startedAt = SystemClock.elapsedRealtime()
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseStage:session").apply {
                acquire(maxSessionMs)
            }
            registerSensor(0)
            main.postDelayed(sensorTick, 1000)
            worker.scheduleWithFixedDelay({ transmit() }, 0, 1000, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            fail(sensorError(error))
        }
        return START_NOT_STICKY
    }

    private fun hasSensorPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun sensorError(error: Exception): String = if (error is SecurityException) {
        "Akses sensor ditolak. Izinkan Sensor tubuh/Detak jantung di pengaturan aplikasi."
    } else error.message ?: "Sensor tidak dapat diaktifkan. Coba restart jam."

    private fun fail(reason: String) {
        startFailure = reason
        message = reason
        active = false
        running = false
        Log.w(TAG, reason)
        stopSelf()
    }

    private fun registerSensor(firstIndex: Int) {
        sensors.unregisterListener(this)
        activeSensor = null
        latest.set(null)
        bpm = null
        lastMeasurement = 0
        signal.beginAttempt(SystemClock.elapsedRealtime())
        updateSensorStatus(SystemClock.elapsedRealtime())
        for (offset in candidates.indices) {
            val index = (firstIndex + offset) % candidates.size
            val sensor = candidates[index]
            activeSensor = sensor
            // Explicitly dispatch events to the main looper, with no batching.
            val registered = sensors.registerListener(this, sensor, 1_000_000, 0, main)
            Log.i(TAG, "Register sensor=${sensor.name}; wakeUp=${sensor.isWakeUpSensor}; reportingMode=${sensor.reportingMode}; registered=$registered")
            if (registered) { candidateIndex = index; return }
        }
        activeSensor = null
        error("Sensor menolak registrasi. Periksa izin Sensor tubuh/Detak jantung, lalu coba lagi.")
    }

    private fun updateSensorStatus(now: Long) {
        sensorMessage = signal.message(now)
        if (sensorMessage != null) { latest.set(null); bpm = null }
        val diagnostic = sensorMessage ?: "Fresh heart-rate event received"
        if (diagnostic != lastDiagnostic) {
            Log.i(TAG, diagnostic) // Do not log BPM, credentials, or pairing/overlay URLs.
            lastDiagnostic = diagnostic
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HeartRateService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "streaming")
            .setSmallIcon(R.drawable.ic_pulse).setContentTitle("PulseStage aktif")
            .setContentText("BPM dibagikan ke overlay. Ketuk Stop untuk berhenti.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active || event.sensor != activeSensor) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val reading = signal.accept(event.values.firstOrNull(), event.accuracy, event.timestamp, nowNanos)
        updateSensorStatus(nowNanos / 1_000_000)
        if (reading == null) return
        val measuredAt = System.currentTimeMillis() - (nowNanos / 1_000_000 - reading.elapsedMs)
        bpm = reading.bpm.toInt()
        lastMeasurement = reading.elapsedMs
        sensorRetries = 0
        latest.set(Sample(reading.bpm, measuredAt, reading.elapsedMs, ++sequence))
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (!active || sensor != activeSensor) return
        Log.i(TAG, "Sensor accuracy=$accuracy")
        signal.updateAccuracy(accuracy)
        updateSensorStatus(SystemClock.elapsedRealtime())
    }

    private fun transmit() {
        if (!active) return
        try {
            if (sessionId == null) sessionId = api.request("/api/device/session", "POST", JSONObject().put("active", true)).getString("sessionId")
            if (!active) return
            val sample = latest.get()
            if (sample != null && sample.sequence > lastSent && SystemClock.elapsedRealtime() - sample.elapsedMs <= HeartRateSignal.FRESH_MS) {
                api.request("/api/device/samples", "POST", JSONObject()
                    .put("sessionId", sessionId).put("bpm", sample.bpm.toDouble())
                    .put("sequence", sample.sequence).put("measuredAt", sample.measuredAt))
                lastSent = sample.sequence
                if (!active) return
                networkFailed = false
                message = "BPM terkirim"
            } else if (SystemClock.elapsedRealtime() - lastStatusCheck > 10_000) {
                api.request("/api/device/status") // Detect revoked pairing even if the sensor stops reporting.
                lastStatusCheck = SystemClock.elapsedRealtime()
                if (!active) return
                networkFailed = false
                if (sample == null) message = "Server terhubung."
            }
        } catch (error: ApiException) {
            if (!active) return
            networkFailed = true
            when (error.status) {
                401 -> main.post {
                    if (active) { api.clearRegistration(); fail("Pairing dicabut. Hubungkan ulang.") }
                }
                409 -> { sessionId = null; message = "Memulihkan sesi…" }
                else -> { message = error.message ?: "Pengiriman gagal." }
            }
        } catch (_: Exception) {
            if (active) { networkFailed = true; message = "Koneksi terputus. Mencoba lagi…" }
        }
    }

    override fun onDestroy() {
        active = false
        running = false
        main.removeCallbacks(sensorTick)
        bpm = null
        latest.set(null)
        sensors.unregisterListener(this)
        activeSensor = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        // Serialized after in-flight uploads, so Stop is the final operation in this session.
        worker.execute {
            try { if (sessionId != null) api.request("/api/device/session", "POST", JSONObject().put("active", false).put("sessionId", sessionId)) } catch (_: Exception) { /* Server expires stale BPM if offline. */ }
        }
        worker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
