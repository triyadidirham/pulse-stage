package dev.pulsestage.wear.service

import dev.pulsestage.wear.R
import dev.pulsestage.wear.data.Api
import dev.pulsestage.wear.data.ApiException
import dev.pulsestage.wear.sensor.HeartRateSignal
import dev.pulsestage.wear.sensor.HealthServicesHeartRateSource
import dev.pulsestage.wear.ui.MainActivity

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HeartRateService : Service() {
    companion object {
        const val STOP = "dev.pulsestage.STOP"
        @Volatile var running = false
        @Volatile var stopping = false
        @Volatile var bpm: Int? = null
        @Volatile var message = "Siap"
        @Volatile var lastMeasurement = 0L
        @Volatile var startFailure: String? = null
        @Volatile private var sensorMessage: String? = null
        @Volatile private var networkFailed = false
        val statusMessage: String
            get() = if (stopping) "Menghentikan sesi sensor…" else if (running && sensorMessage != null) {
                listOfNotNull(sensorMessage, message.takeIf { networkFailed }).joinToString("\n")
            } else message
        private const val TAG = "PulseStageSensor"
        private const val ACQUISITION_TIMEOUT_MS = 135_000L
    }

    private data class Sample(val bpm: Float, val measuredAt: Long, val elapsedMs: Long, val sequence: Long)
    private val latest = AtomicReference<Sample?>(null)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var destroyed = false
    private var closing = false
    private lateinit var api: Api
    private var source: HealthServicesHeartRateSource? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sessionId: String? = null
    private var sequence = 0L
    private var lastSent = -1L
    private var lastStatusCheck = 0L
    private var startedAt = 0L
    private var lastReadingReceivedAt = 0L
    private var providerMessage: String? = null
    private val maxSessionMs = 4 * 60 * 60 * 1000L
    private val signal = HeartRateSignal()
    private var lastDiagnostic: String? = null
    private val sensorTick = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (now - startedAt >= maxSessionMs) {
                finishSession("Sesi 4 jam selesai. Tekan Start untuk sesi baru.", failed = true)
                return
            }
            if (!hasSensorPermission()) {
                finishSession("Izin sensor dicabut. Izinkan Sensor tubuh/Detak jantung di pengaturan aplikasi.", failed = true)
                return
            }
            updateSensorStatus(now)
            if (now - maxOf(startedAt, lastReadingReceivedAt) >= ACQUISITION_TIMEOUT_MS) {
                finishSession("${sensorMessage ?: "BPM belum tersedia."}\nHealth Services belum memberi data valid. Perbarui Health Services/sistem jam lalu coba lagi.", failed = true)
                return
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        api = Api(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("streaming", "Sesi berbagi BPM", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finishSession("Sesi dihentikan."); return START_NOT_STICKY }
        if (running || stopping || closing) return START_NOT_STICKY
        startFailure = null
        networkFailed = false
        try {
            check(hasSensorPermission()) { "Izin sensor belum diberikan. Izinkan Sensor tubuh/Detak jantung di pengaturan aplikasi." }
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            else startForeground(1, notification)
            running = true
            active = true
            bpm = null
            lastMeasurement = 0
            message = "Menunggu pengiriman…"
            startedAt = SystemClock.elapsedRealtime()
            signal.beginAttempt(startedAt)
            providerMessage = "Menghubungkan Health Services…"
            updateSensorStatus(startedAt)
            Log.i(TAG, "Starting sensor; sdk=${Build.VERSION.SDK_INT}; provider=HealthServices; permission=granted")
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseStage:session").apply {
                acquire(maxSessionMs)
            }
            source = HealthServicesHeartRateSource(this,
                onStarted = {
                    if (active) {
                        providerMessage = null
                        updateSensorStatus(SystemClock.elapsedRealtime())
                    }
                },
                onReading = ::receiveReading,
                onAvailability = { availability ->
                    if (active) {
                        providerMessage = availability
                        if (availability != null) signal.clear()
                        updateSensorStatus(SystemClock.elapsedRealtime())
                    }
                },
                onFailure = { error ->
                    if (active) {
                        Log.w(TAG, "Health Services failed: ${error.javaClass.simpleName}")
                        val reason = if (error is SecurityException) "Akses sensor ditolak. Periksa izin Sensor tubuh/Detak jantung."
                            else error.message ?: "Health Services tidak dapat diakses. Perbarui layanan di jam lalu coba lagi."
                        finishSession(reason, failed = true)
                    }
                }
            )
            source!!.start()
            if (active) {
                main.postDelayed(sensorTick, 1000)
                worker.scheduleWithFixedDelay({ transmit() }, 0, 1000, TimeUnit.MILLISECONDS)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Start failed: ${error.javaClass.simpleName}")
            finishSession(error.message ?: "Tidak dapat memulai Health Services. Periksa izin sensor.", failed = true)
        }
        return START_NOT_STICKY
    }

    private fun hasSensorPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun finishSession(reason: String, failed: Boolean = false) {
        if (closing) return
        closing = true
        active = false
        running = false
        stopping = true
        bpm = null
        latest.set(null)
        main.removeCallbacks(sensorTick)
        message = reason
        startFailure = reason.takeIf { failed }
        Log.i(TAG, "Stopping sensor session; failed=$failed")
        // Keep the foreground service until a late start is undone and the callback is removed.
        closeSource().whenComplete { _, error ->
            if (!destroyed) {
                if (error != null) {
                    message = "Penutupan sesi Health Services gagal. Buka aplikasi lagi atau restart jam."
                    startFailure = message
                    Log.w(TAG, "Sensor cleanup failed: ${error.javaClass.simpleName}")
                }
                stopSelf()
            }
        }
    }

    private fun closeSource(): CompletableFuture<Unit> = source?.stop() ?: CompletableFuture.completedFuture(Unit)

    private fun updateSensorStatus(now: Long) {
        sensorMessage = providerMessage ?: signal.message(now)
        if (sensorMessage != null) { latest.set(null); bpm = null }
        val diagnostic = sensorMessage ?: "Fresh heart-rate event received"
        if (diagnostic != lastDiagnostic) {
            Log.i(TAG, diagnostic) // Never log BPM, credentials, or pairing/overlay URLs.
            lastDiagnostic = diagnostic
        }
    }

    private fun receiveReading(value: Double, quality: HeartRateSignal.Quality, timestampNanos: Long) {
        if (!active) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val reading = signal.accept(value, quality, timestampNanos, nowNanos)
        providerMessage = null
        updateSensorStatus(nowNanos / 1_000_000)
        if (reading == null) return
        val measuredAt = System.currentTimeMillis() - (nowNanos / 1_000_000 - reading.elapsedMs)
        bpm = reading.bpm.toInt()
        lastMeasurement = reading.elapsedMs
        lastReadingReceivedAt = nowNanos / 1_000_000
        latest.set(Sample(reading.bpm, measuredAt, reading.elapsedMs, ++sequence))
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HeartRateService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "streaming")
            .setSmallIcon(R.drawable.ic_pulse).setContentTitle("PulseStage aktif")
            .setContentText("Sesi sensor Wear OS aktif. Ketuk Stop untuk berhenti.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
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
                api.request("/api/device/status")
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
                    if (active) { api.clearRegistration(); finishSession("Pairing dicabut. Hubungkan ulang.", failed = true) }
                }
                409 -> { sessionId = null; message = "Memulihkan sesi…" }
                else -> { message = error.message ?: "Pengiriman gagal." }
            }
        } catch (_: Exception) {
            if (active) { networkFailed = true; message = "Koneksi terputus. Mencoba lagi…" }
        }
    }

    override fun onDestroy() {
        destroyed = true
        active = false
        running = false
        stopping = true
        main.removeCallbacks(sensorTick)
        bpm = null
        latest.set(null)
        closeSource().whenComplete { _, _ -> stopping = false }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        worker.execute {
            try { if (sessionId != null) api.request("/api/device/session", "POST", JSONObject().put("active", false).put("sessionId", sessionId)) }
            catch (_: Exception) { /* Server expires stale BPM if offline. */ }
        }
        worker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
