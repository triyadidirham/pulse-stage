package dev.pulsestage.wear.sensor

import org.junit.Assert.*
import org.junit.Test

class HeartRateSignalTest {
    private fun nanos(ms: Long) = ms * 1_000_000
    private fun signal() = HeartRateSignal().apply { beginAttempt(100_000) }

    @Test fun validReadingRetainsMeasurementTimeInsteadOfCallbackTime() {
        val signal = signal()
        val reading = signal.accept(78f, 3, nanos(101_000), nanos(105_000))!!
        assertEquals(101_000L, reading.elapsedMs)
        assertEquals(78f, reading.bpm, 0f)
        assertNull(signal.message(105_000))
        assertFalse(signal.isFresh(116_001))
        assertEquals(HeartRateSignal.Issue.NO_UPDATES.message, signal.message(116_001))
    }

    @Test fun noContactIsRejectedEvenWhenEventContainsPlausibleBpm() {
        val signal = signal()
        assertNull(signal.accept(78f, -1, nanos(101_000), nanos(101_000)))
        assertEquals(HeartRateSignal.Issue.NO_CONTACT.message, signal.message(101_000))
        assertFalse(signal.isFresh(101_000))
    }

    @Test fun unreliableSignalClearsPreviousReadingButLowAccuracyIsAccepted() {
        val signal = signal()
        assertNotNull(signal.accept(78f, 1, nanos(101_000), nanos(101_000)))
        signal.updateAccuracy(0)
        assertNull(signal.reading)
        assertEquals(HeartRateSignal.Issue.UNRELIABLE.message, signal.message(102_000))
        assertNotNull(signal.accept(79f, 2, nanos(103_000), nanos(103_000)))
        assertNull(signal.message(103_000))
    }

    @Test fun staleZeroAndFutureTimestampsNeverBecomeFreshData() {
        for (timestamp in listOf(0L, nanos(80_000), nanos(110_001))) {
            val signal = signal()
            assertNull(signal.accept(78f, 3, timestamp, nanos(110_000)))
            assertNull(signal.reading)
            assertNotEquals(HeartRateSignal.Issue.WAITING.message, signal.message(110_000))
        }
    }

    @Test fun invalidValuesAreRejected() {
        for (value in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 0f, 19f, 301f)) {
            val signal = signal()
            assertNull(signal.accept(value, 3, nanos(101_000), nanos(101_000)))
            assertEquals(HeartRateSignal.Issue.INVALID_VALUE.message, signal.message(101_000))
        }
    }

    @Test fun duplicateAndOutOfOrderCallbacksDoNotReplaceNewerReading() {
        val signal = signal()
        val latest = signal.accept(78f, 3, nanos(101_000), nanos(101_000))
        assertNull(signal.accept(91f, 3, nanos(101_000), nanos(103_000)))
        assertNull(signal.accept(92f, 3, nanos(100_000), nanos(103_000)))
        assertEquals(latest, signal.reading)
        assertNull(signal.message(103_000))
    }

    @Test fun reRegisteringDoesNotReplayOldReadingAsNew() {
        val signal = signal()
        signal.accept(78f, 3, nanos(101_000), nanos(101_000))
        signal.beginAttempt(105_000)
        assertNull(signal.accept(78f, 3, nanos(101_000), nanos(105_000)))
        assertNull(signal.reading)
        assertEquals(HeartRateSignal.Issue.OLD_DATA.message, signal.message(105_000))
        assertNotNull(signal.accept(78f, 3, nanos(106_000), nanos(106_000)))
    }

    @Test fun retriesWaitForFullAcquisitionWindowAndRestartAfterValidReading() {
        val signal = signal()
        assertFalse(signal.needsRetry(144_999))
        assertTrue(signal.needsRetry(145_000))
        signal.beginAttempt(145_000)
        assertFalse(signal.needsRetry(189_999))
        signal.accept(78f, 3, nanos(160_000), nanos(160_000))
        assertFalse(signal.needsRetry(204_999))
        assertTrue(signal.needsRetry(205_000))
    }

    @Test fun badEventsCannotKeepSilentSensorWaitingForever() {
        val signal = signal()
        for (ms in 101_000L..144_000L step 1000) {
            signal.accept(78f, 0, nanos(ms), nanos(ms))
        }
        assertTrue(signal.needsRetry(145_000))
        assertEquals(HeartRateSignal.Issue.UNRELIABLE.message, signal.message(145_000))
    }
}
