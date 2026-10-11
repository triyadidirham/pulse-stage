package dev.pulsestage.wear.service

import dev.pulsestage.wear.R
import dev.pulsestage.wear.data.Api
import dev.pulsestage.wear.data.ApiException
import dev.pulsestage.wear.ui.MainActivity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
    }
    private data class Sample(val bpm: Float, val measuredAt: Long, val sequence: Long)
    private val latest = AtomicReference<Sample?>(null)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var sensors: SensorManager
    private lateinit var api: Api
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId: String? = null
    private var sequence = 0L
    private var lastSent = -1L
    private var lastStatusCheck = 0L
    private var startedAt = 0L
    private val maxSessionMs = 4 * 60 * 60 * 1000L

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
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            else startForeground(1, notification)
            val heartRate = sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE) ?: error("Sensor heart rate tidak tersedia.")
            check(sensors.registerListener(this, heartRate, 1_000_000, 0)) { "Sensor tidak dapat diaktifkan." }
            running = true
            bpm = null
            lastMeasurement = 0
            message = "Menunggu sensor…"
            startedAt = SystemClock.elapsedRealtime()
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseStage:session").apply {
                acquire(maxSessionMs)
            }
            worker.scheduleWithFixedDelay({ transmit() }, 0, 1000, TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            message = error.message ?: "Tidak dapat memulai sensor. Periksa izin."
            stopSelf()
        }
        return START_NOT_STICKY
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
        if (!running || event.sensor.type != Sensor.TYPE_HEART_RATE) return
        val value = event.values.firstOrNull() ?: return
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE || !value.isFinite() || value !in 20f..300f) {
            latest.set(null); bpm = null; message = "Sesuaikan posisi jam."; return
        }
        // Preserve the sensor's measurement time; do not relabel old readings as fresh.
        val ageMs = ((SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000).coerceAtLeast(0)
        if (ageMs > 15_000) return
        val measuredAt = System.currentTimeMillis() - ageMs
        bpm = value.toInt()
        lastMeasurement = SystemClock.elapsedRealtime()
        latest.set(Sample(value, measuredAt, ++sequence))
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) { latest.set(null); bpm = null }
    }

    private fun transmit() {
        if (!running) return
        if (SystemClock.elapsedRealtime() - startedAt >= maxSessionMs) {
            message = "Sesi 4 jam selesai. Tekan Start untuk sesi baru."
            main.post { stopSelf() }; return
        }
        try {
            if (sessionId == null) sessionId = api.request("/api/device/session", "POST", JSONObject().put("active", true)).getString("sessionId")
            if (!running) return
            val sample = latest.get()
            if (sample != null && sample.sequence > lastSent && System.currentTimeMillis() - sample.measuredAt <= 15_000) {
                api.request("/api/device/samples", "POST", JSONObject()
                    .put("sessionId", sessionId).put("bpm", sample.bpm.toDouble())
                    .put("sequence", sample.sequence).put("measuredAt", sample.measuredAt))
                lastSent = sample.sequence
                message = "BPM terkirim"
            } else if (SystemClock.elapsedRealtime() - lastStatusCheck > 10_000) {
                api.request("/api/device/status") // Detect revoked pairing even if the sensor stops reporting.
                lastStatusCheck = SystemClock.elapsedRealtime()
                if (sample == null || System.currentTimeMillis() - sample.measuredAt > 15_000) message = "Menunggu sensor…"
            }
        } catch (error: ApiException) {
            when (error.status) {
                401 -> { api.clearRegistration(); message = "Pairing dicabut. Hubungkan ulang."; main.post { stopSelf() } }
                409 -> { sessionId = null; message = "Memulihkan sesi…" }
                else -> { message = error.message ?: "Pengiriman gagal." }
            }
        } catch (_: Exception) { message = "Koneksi terputus. Mencoba lagi…" }
    }

    override fun onDestroy() {
        running = false
        bpm = null
        latest.set(null)
        sensors.unregisterListener(this)
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
