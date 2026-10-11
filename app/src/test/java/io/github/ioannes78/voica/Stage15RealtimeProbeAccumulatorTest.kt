package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.OpusPacketInfo
import io.github.ioannes78.voica.ble.NotificationSource
import io.github.ioannes78.voica.ble.RoutedNotification
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage15RealtimeProbeAccumulatorTest {
    @Test
    fun sequenceIsRawHistogramAndMediaTimingUsesOpusSamples() {
        val probe = Stage15RealtimeProbeAccumulator()
        val info = OpusPacketInfo(valid = true, channels = 1, totalSamples48k = 960)

        probe.noteTx("START", sequence = 0, written = true, atNanos = 1_000_000_000L)
        listOf(
            1_020_000_000L,
            1_040_000_000L,
            1_080_000_000L,
            1_400_000_000L,
        ).forEach { receivedAt ->
            probe.accept(
                notification = audio(sequence = 0, size = 160),
                receivedAtNanos = receivedAt,
                opusInfo = info,
            )
        }
        probe.noteTx("STOP", sequence = 1, written = true, atNanos = 1_500_000_000L)

        val snapshot = probe.snapshot(nowNanos = 2_000_000_000L)
        assertEquals(4L, snapshot.audioPackets)
        assertEquals(mapOf(0 to 4L), snapshot.protocolSequenceHistogram)
        assertEquals(mapOf(160 to 4L), snapshot.bodyLengthHistogram)
        assertEquals(mapOf(1 to 4L), snapshot.opusChannelCounts)
        assertEquals(mapOf(960 to 4L), snapshot.opusTotalSamples48k)
        assertEquals(mapOf(20 to 4L), snapshot.opusPacketDurationMs)
        assertEquals(80.0, snapshot.mediaDurationMs, 0.001)
        assertEquals(500L, snapshot.wallElapsedMs)
        assertEquals(0.16, snapshot.mediaWallRatio, 0.001)
        assertEquals(8.0, snapshot.packetsPerSecond, 0.001)
        assertEquals(1L, snapshot.arrivalGapHistogram["<25ms"])
        assertEquals(1L, snapshot.arrivalGapHistogram["25-49ms"])
        assertEquals(1L, snapshot.arrivalGapHistogram[">=250ms"])
        assertEquals(320.0, snapshot.maxArrivalGapMs, 0.001)
        assertEquals(4L, snapshot.phasePacketCounts["beforePause"])
    }

    @Test
    fun deviceStateSplitsPhasesAndCapturesFilenameAndTimeline() {
        val probe = Stage15RealtimeProbeAccumulator()
        val info = OpusPacketInfo(valid = true, channels = 1, totalSamples48k = 960)
        val filename = "note20261011-145800.opus"

        probe.noteTx("START", sequence = 0, written = true, atNanos = 1_000_000_000L)
        probe.accept(
            notification = realtime(
                command = ProtocolConstants.Realtime.START,
                sequence = 0,
                body = filename.toByteArray(Charsets.US_ASCII),
                source = NotificationSource.AE23,
            ),
            receivedAtNanos = 1_005_000_000L,
            opusInfo = null,
        )
        probe.accept(audio(0, 160), 1_020_000_000L, info)

        probe.noteTx("PAUSE", sequence = 1, written = true, atNanos = 1_040_000_000L)
        probe.accept(
            notification = realtime(
                command = ProtocolConstants.Realtime.DEVICE_STATE,
                sequence = 1,
                body = byteArrayOf(1),
            ),
            receivedAtNanos = 1_050_000_000L,
            opusInfo = null,
        )
        probe.accept(audio(0, 160), 1_060_000_000L, info)

        probe.noteTx("RESUME", sequence = 2, written = true, atNanos = 1_100_000_000L)
        probe.accept(
            notification = realtime(
                command = ProtocolConstants.Realtime.DEVICE_STATE,
                sequence = 2,
                body = byteArrayOf(0),
            ),
            receivedAtNanos = 1_110_000_000L,
            opusInfo = null,
        )
        probe.accept(audio(0, 160), 1_120_000_000L, info)
        probe.noteTx("STOP", sequence = 3, written = true, atNanos = 1_150_000_000L)

        val snapshot = probe.snapshot(nowNanos = 2_000_000_000L)
        assertEquals(filename, snapshot.deviceReportedFilename)
        assertEquals(1L, snapshot.phasePacketCounts["beforePause"])
        assertEquals(1L, snapshot.phasePacketCounts["paused"])
        assertEquals(1L, snapshot.phasePacketCounts["afterResume"])
        assertEquals(20L, snapshot.timelineOffsetsMs["firstAudio"])
        assertEquals(40L, snapshot.timelineOffsetsMs["pauseTx"])
        assertEquals(50L, snapshot.timelineOffsetsMs["devicePaused"])
        assertEquals(100L, snapshot.timelineOffsetsMs["resumeTx"])
        assertEquals(110L, snapshot.timelineOffsetsMs["deviceResumed"])
        assertEquals(120L, snapshot.timelineOffsetsMs["firstAudioAfterResume"])
        assertEquals(150L, snapshot.timelineOffsetsMs["stopTx"])
        assertEquals(120L, snapshot.timelineOffsetsMs["lastAudio"])
        assertEquals(1_000L, snapshot.sessionStartMonotonicMs)
    }

    @Test
    fun invalidOpusDoesNotAdvanceMediaDuration() {
        val probe = Stage15RealtimeProbeAccumulator()
        val invalid = OpusPacketInfo(valid = false, errorCode = -4)

        probe.noteTx("START", sequence = 0, written = true, atNanos = 2_000_000_000L)
        probe.accept(
            notification = audio(sequence = 0, size = 160),
            receivedAtNanos = 2_020_000_000L,
            opusInfo = invalid,
        )
        probe.noteTx("STOP", sequence = 1, written = true, atNanos = 2_100_000_000L)

        val snapshot = probe.snapshot()
        assertEquals(1L, snapshot.opusInvalid)
        assertEquals(mapOf(-4 to 1L), snapshot.opusErrorCounts)
        assertEquals(0.0, snapshot.mediaDurationMs, 0.001)
        assertEquals(mapOf(0 to 1L), snapshot.protocolSequenceHistogram)
    }

    @Test
    fun failedStartDoesNotCreateWallClockAnchor() {
        val probe = Stage15RealtimeProbeAccumulator()
        probe.noteTx("START", sequence = 0, written = false, atNanos = 1_000_000_000L)
        val snapshot = probe.snapshot(nowNanos = 2_000_000_000L)
        assertEquals(null, snapshot.wallElapsedMs)
        assertEquals(null, snapshot.sessionStartMonotonicMs)
        assertTrue(snapshot.timelineOffsetsMs.isEmpty())
    }

    private fun audio(sequence: Int, size: Int): RoutedNotification =
        realtime(
            command = ProtocolConstants.Realtime.AUDIO_DATA,
            sequence = sequence,
            body = ByteArray(size) { it.toByte() },
        )

    private fun realtime(
        command: Int,
        sequence: Int,
        body: ByteArray,
        source: NotificationSource = NotificationSource.AE22,
    ): RoutedNotification = RoutedNotification(
        source = source,
        frame = ProtocolFrame(
            sequence = sequence,
            data = byteArrayOf(
                ProtocolConstants.Type.REALTIME.toByte(),
                command.toByte(),
            ) + body,
        ),
    )
}
