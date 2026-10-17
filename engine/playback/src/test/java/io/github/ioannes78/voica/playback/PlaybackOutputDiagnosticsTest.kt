package io.github.ioannes78.voica.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOutputDiagnosticsTest {
    @Test
    fun actualRouteGenerationChangesOnlyWhenActualTrackRouteChanges() {
        var now = 10L
        val diagnostics = PlaybackOutputDiagnosticsAccumulator { now++ }
        val speaker = device(1, "BUILTIN_SPEAKER")
        val bluetooth = device(2, "BLUETOOTH_A2DP")

        diagnostics.sinkCreated(
            trackState = "INITIALIZED",
            sampleRateHz = 16_000,
            channelCount = 1,
            encoding = "PCM16",
            bufferBytes = 8192,
            initialRoute = speaker,
        )
        val initial = diagnostics.snapshot()
        assertEquals(1L, initial.routeGeneration)
        assertEquals(0L, initial.routeChanges)
        assertEquals(speaker, initial.actualRoute)

        diagnostics.actualRoute(speaker, "PLAY_START")
        val unchanged = diagnostics.snapshot()
        assertEquals(1L, unchanged.routeGeneration)
        assertEquals(0L, unchanged.routeChanges)
        assertEquals("PLAY_START", unchanged.lastRouteReason)

        diagnostics.environmentEvent("OUTPUT_DEVICE_ADDED:2/BLUETOOTH_A2DP")
        val inventoryOnly = diagnostics.snapshot()
        assertEquals(1L, inventoryOnly.routeGeneration)
        assertEquals(speaker, inventoryOnly.actualRoute)

        diagnostics.actualRoute(bluetooth, "TRACK_ROUTED")
        val migrated = diagnostics.snapshot()
        assertEquals(2L, migrated.routeGeneration)
        assertEquals(1L, migrated.routeChanges)
        assertEquals(bluetooth, migrated.actualRoute)
        assertEquals("TRACK_ROUTED", migrated.lastRouteReason)
    }

    @Test
    fun writeAndPositionDiagnosticsAreObservationalAndRecoveryStaysDisabled() {
        val diagnostics = PlaybackOutputDiagnosticsAccumulator { 100L }
        diagnostics.sinkCreated(
            trackState = "INITIALIZED",
            sampleRateHz = 16_000,
            channelCount = 1,
            encoding = "PCM16",
            bufferBytes = 8192,
            initialRoute = null,
        )

        diagnostics.writeResult(requestedBytes = 100, result = 100, underrunCount = 1)
        diagnostics.writeResult(requestedBytes = 100, result = 0, underrunCount = 2)
        diagnostics.writeResult(requestedBytes = 100, result = -6, underrunCount = 3)
        diagnostics.position(
            framePosition = 1234L,
            timestampAvailable = true,
            underrunCount = 4,
        )

        val snapshot = diagnostics.snapshot()
        assertEquals(3L, snapshot.writeCalls)
        assertEquals(100L, snapshot.writeBytes)
        assertEquals(1L, snapshot.zeroWrites)
        assertEquals(1L, snapshot.negativeWrites)
        assertEquals(4, snapshot.underrunCount)
        assertEquals(1234L, snapshot.lastPresentedFrame)
        assertTrue(snapshot.timestampAvailable)
        assertTrue(snapshot.lastWriteFailure?.contains("result=-6") == true)
        assertEquals(0, snapshot.recoveryAttempts)
        assertEquals("NOT_ATTEMPTED", snapshot.lastRecoveryResult)
    }

    @Test
    fun newSinkResetsPerSinkCountersButKeepsMonotonicRouteHistory() {
        val diagnostics = PlaybackOutputDiagnosticsAccumulator { 200L }
        val speaker = device(1, "BUILTIN_SPEAKER")

        diagnostics.sinkCreated(
            trackState = "INITIALIZED",
            sampleRateHz = 16_000,
            channelCount = 1,
            encoding = "PCM16",
            bufferBytes = 8192,
            initialRoute = speaker,
        )
        diagnostics.writeResult(20, 20, 0)
        diagnostics.sinkClosed()

        diagnostics.sinkCreated(
            trackState = "INITIALIZED",
            sampleRateHz = 16_000,
            channelCount = 1,
            encoding = "PCM16",
            bufferBytes = 16_384,
            initialRoute = null,
        )
        val snapshot = diagnostics.snapshot()

        assertEquals(2L, snapshot.sinkGeneration)
        assertEquals(0L, snapshot.writeCalls)
        assertEquals(0L, snapshot.writeBytes)
        assertEquals("CREATED", snapshot.sinkPhase)
        assertNull(snapshot.actualRoute)
        assertEquals(2L, snapshot.routeGeneration)
        assertEquals(1L, snapshot.routeChanges)
    }

    private fun device(id: Int, label: String): PlaybackOutputDeviceDescriptor =
        PlaybackOutputDeviceDescriptor(
            id = id,
            type = id,
            typeLabel = label,
            productName = label,
            address = null,
            isSink = true,
        )
}
