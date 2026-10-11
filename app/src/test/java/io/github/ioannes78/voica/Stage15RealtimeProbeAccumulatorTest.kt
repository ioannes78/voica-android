package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.OpusPacketInfo
import io.github.ioannes78.voica.ble.NotificationSource
import io.github.ioannes78.voica.ble.RoutedNotification
import io.github.ioannes78.voica.protocol.ProtocolConstants
import io.github.ioannes78.voica.protocol.ProtocolFrame
import org.junit.Assert.assertEquals
import org.junit.Test

class Stage15RealtimeProbeAccumulatorTest {
    @Test
    fun audioSequenceWrapIsSequentialAndOpusFactsAreCounted() {
        val probe = Stage15RealtimeProbeAccumulator()
        val info = OpusPacketInfo(valid = true, channels = 1, totalSamples48k = 960)

        listOf(254, 255, 0, 1).forEachIndexed { index, sequence ->
            probe.accept(
                notification = audio(sequence, size = 40),
                receivedAtNanos = 1_000_000_000L + index * 20_000_000L,
                opusInfo = info,
            )
        }

        val snapshot = probe.snapshot()
        assertEquals(4L, snapshot.audioPackets)
        assertEquals(3L, snapshot.sequentialTransitions)
        assertEquals(0L, snapshot.duplicateCandidates)
        assertEquals(0L, snapshot.gapCandidates)
        assertEquals(mapOf(40 to 4L), snapshot.bodyLengthHistogram)
        assertEquals(mapOf(1 to 4L), snapshot.opusChannelCounts)
        assertEquals(mapOf(960 to 4L), snapshot.opusTotalSamples48k)
        assertEquals(mapOf(20 to 4L), snapshot.opusPacketDurationMs)
    }

    @Test
    fun duplicateAndGapRemainCandidatesUntilTrueDeviceSemanticsAreFrozen() {
        val probe = Stage15RealtimeProbeAccumulator()
        val invalid = OpusPacketInfo(valid = false, errorCode = -4)

        listOf(10, 10, 13).forEachIndexed { index, sequence ->
            probe.accept(
                notification = audio(sequence, size = 41),
                receivedAtNanos = 2_000_000_000L + index * 20_000_000L,
                opusInfo = invalid,
            )
        }

        val snapshot = probe.snapshot()
        assertEquals(1L, snapshot.duplicateCandidates)
        assertEquals(1L, snapshot.gapCandidates)
        assertEquals(3L, snapshot.opusInvalid)
        assertEquals(mapOf(-4 to 3L), snapshot.opusErrorCounts)
    }

    @Test
    fun nonAudioRealtimeFramesAreSeparatedFromAudioHistogram() {
        val probe = Stage15RealtimeProbeAccumulator()
        probe.accept(
            notification = realtime(command = ProtocolConstants.Realtime.START, sequence = 7, body = byteArrayOf(1, 2)),
            receivedAtNanos = 1L,
            opusInfo = null,
        )
        probe.accept(
            notification = audio(sequence = 8, size = 40),
            receivedAtNanos = 2L,
            opusInfo = OpusPacketInfo(valid = true, channels = 1, totalSamples48k = 960),
        )

        val snapshot = probe.snapshot()
        assertEquals(2L, snapshot.realtimeFrames)
        assertEquals(1L, snapshot.audioPackets)
        assertEquals(1, snapshot.nonAudioFrames.size)
        assertEquals(mapOf(40 to 1L), snapshot.bodyLengthHistogram)
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
    ): RoutedNotification = RoutedNotification(
        source = NotificationSource.AE22,
        frame = ProtocolFrame(
            sequence = sequence,
            data = byteArrayOf(
                ProtocolConstants.Type.REALTIME.toByte(),
                command.toByte(),
            ) + body,
        ),
    )
}
