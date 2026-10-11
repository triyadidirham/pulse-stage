package dev.pulsestage.wear.sensor

/** Sensor timestamps use time since boot, never the wall clock or callback arrival time. */
internal class HeartRateSignal {
    companion object {
        const val FRESH_MS = 15_000L
        const val RETRY_MS = 45_000L
    }

    data class Reading(val bpm: Float, val elapsedMs: Long)
    // Missing Health Services accuracy is different from SensorManager's UNRELIABLE (0).
    enum class Quality { RELIABLE, UNSPECIFIED, NO_CONTACT, UNRELIABLE }
    enum class Issue(val message: String) {
        WAITING("Menunggu pembacaan pertama… Pakai jam dengan pas."),
        NO_CONTACT("Sensor belum menyentuh kulit. Sesuaikan posisi jam."),
        UNRELIABLE("Sinyal sensor belum stabil. Diamkan tangan sebentar."),
        INVALID_VALUE("Sensor belum memberikan BPM yang valid."),
        OLD_DATA("Sensor mengirim data lama. Menunggu pembacaan baru…"),
        INVALID_TIME("Waktu data sensor tidak valid. Coba restart jam."),
        NO_UPDATES("Sensor belum mengirim pembacaan baru…")
    }

    var reading: Reading? = null
        private set
    var issue = Issue.WAITING
        private set
    private var attemptStartedMs = 0L
    private var lastAcceptedNanos = 0L
    private var lastAcceptedAtMs = 0L

    fun beginAttempt(nowMs: Long) {
        attemptStartedMs = nowMs
        reading = null
        issue = Issue.WAITING
        // Keep the last timestamp so re-registering cannot replay an old measurement.
    }

    fun updateAccuracy(accuracy: Int) {
        if (accuracy <= 0) {
            reading = null
            issue = if (accuracy == -1) Issue.NO_CONTACT else Issue.UNRELIABLE
        }
    }

    fun accept(value: Float?, accuracy: Int, timestampNanos: Long, nowNanos: Long): Reading? {
        val quality = when {
            accuracy == -1 -> Quality.NO_CONTACT
            accuracy <= 0 -> Quality.UNRELIABLE
            else -> Quality.RELIABLE
        }
        return accept(value?.toDouble(), quality, timestampNanos, nowNanos)
    }

    fun clear() { reading = null; issue = Issue.WAITING }

    fun accept(value: Double?, quality: Quality, timestampNanos: Long, nowNanos: Long): Reading? {
        when (quality) {
            Quality.NO_CONTACT -> { updateAccuracy(-1); return null }
            Quality.UNRELIABLE -> { updateAccuracy(0); return null }
            else -> Unit
        }
        if (value == null || !value.isFinite() || value !in 20.0..300.0) {
            reading = null
            issue = Issue.INVALID_VALUE
            return null
        }
        val timeIssue = when {
            timestampNanos <= 0 || timestampNanos > nowNanos -> Issue.INVALID_TIME
            nowNanos - timestampNanos > FRESH_MS * 1_000_000 -> Issue.OLD_DATA
            timestampNanos <= lastAcceptedNanos -> Issue.OLD_DATA
            else -> null
        }
        if (timeIssue != null) {
            // A delayed/replayed event must not replace a newer, still-fresh reading.
            if (!isFresh(nowNanos / 1_000_000)) { reading = null; issue = timeIssue }
            return null
        }
        lastAcceptedNanos = timestampNanos
        lastAcceptedAtMs = nowNanos / 1_000_000
        return Reading(value.toFloat(), timestampNanos / 1_000_000).also { reading = it }
    }

    fun isFresh(nowMs: Long): Boolean = reading?.let { nowMs - it.elapsedMs in 0..FRESH_MS } == true

    fun message(nowMs: Long): String? {
        if (isFresh(nowMs)) return null
        return if (reading != null) Issue.NO_UPDATES.message else issue.message
    }

    fun needsRetry(nowMs: Long): Boolean = nowMs - maxOf(attemptStartedMs, lastAcceptedAtMs) >= RETRY_MS
}
