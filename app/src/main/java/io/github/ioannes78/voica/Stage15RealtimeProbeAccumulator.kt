package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.OpusPacketInfo
import io.github.ioannes78.voica.ble.NotificationSource
import io.github.ioannes78.voica.ble.RoutedNotification
import io.github.ioannes78.voica.protocol.ProtocolConstants
import java.util.Locale

internal data class Stage15ProbeSnapshot(
    val realtimeFrames: Long = 0,
    val audioPackets: Long = 0,
    val audioBytes: Long = 0,
    val ae22Frames: Long = 0,
    val ae23Frames: Long = 0,
    val commandCounts: Map<Int, Long> = emptyMap(),
    val bodyLengthHistogram: Map<Int, Long> = emptyMap(),
    val firstAudioSequence: Int? = null,
    val lastAudioSequence: Int? = null,
    val sequentialTransitions: Long = 0,
    val duplicateCandidates: Long = 0,
    val gapCandidates: Long = 0,
    val opusValid: Long = 0,
    val opusInvalid: Long = 0,
    val opusErrorCounts: Map<Int, Long> = emptyMap(),
    val opusChannelCounts: Map<Int, Long> = emptyMap(),
    val opusTotalSamples48k: Map<Int, Long> = emptyMap(),
    val opusPacketDurationMs: Map<Int, Long> = emptyMap(),
    val queueOverflowCount: Long = 0,
    val packetsPerSecond: Double = 0.0,
    val nonAudioFrames: List<String> = emptyList(),
    val sequenceAnomalies: List<String> = emptyList(),
    val txEvents: List<String> = emptyList(),
) {
    fun render(observerOverflowCount: Int = 0): String = buildString {
        appendLine("Stage 15.0 Realtime Fact Gate")
        appendLine("realtimeFrames=$realtimeFrames audioPackets=$audioPackets audioBytes=$audioBytes")
        appendLine("source AE22=$ae22Frames AE23=$ae23Frames")
        appendLine("commands=${commandCounts.toSortedMap()}")
        appendLine("bodyLengthHistogram=${bodyLengthHistogram.toSortedMap()}")
        appendLine("audioSeq first=$firstAudioSequence last=$lastAudioSequence")
        appendLine(
            "seqTransitions sequential=$sequentialTransitions duplicateCandidate=$duplicateCandidates gapCandidate=$gapCandidates",
        )
        appendLine("probeQueueOverflow=$queueOverflowCount genericObserverOverflow=$observerOverflowCount")
        appendLine("rate=${String.format(Locale.US, "%.2f", packetsPerSecond)} packets/s")
        appendLine("opus valid=$opusValid invalid=$opusInvalid errors=${opusErrorCounts.toSortedMap()}")
        appendLine("opus channels=${opusChannelCounts.toSortedMap()}")
        appendLine("opus totalSamples48k=${opusTotalSamples48k.toSortedMap()}")
        appendLine("opus durationMs=${opusPacketDurationMs.toSortedMap()}")
        appendLine("tx=${txEvents.joinToString()}")
        appendLine("nonAudioFrames:")
        nonAudioFrames.forEach { appendLine("  $it") }
        appendLine("sequenceAnomalies:")
        sequenceAnomalies.forEach { appendLine("  $it") }
    }
}

internal class Stage15RealtimeProbeAccumulator(
    private val maxSamples: Int = 32,
) {
    private val lock = Any()
    private var realtimeFrames = 0L
    private var audioPackets = 0L
    private var audioBytes = 0L
    private var ae22Frames = 0L
    private var ae23Frames = 0L
    private val commandCounts = linkedMapOf<Int, Long>()
    private val bodyLengthHistogram = linkedMapOf<Int, Long>()
    private var firstAudioSequence: Int? = null
    private var lastAudioSequence: Int? = null
    private var sequentialTransitions = 0L
    private var duplicateCandidates = 0L
    private var gapCandidates = 0L
    private var opusValid = 0L
    private var opusInvalid = 0L
    private val opusErrorCounts = linkedMapOf<Int, Long>()
    private val opusChannelCounts = linkedMapOf<Int, Long>()
    private val opusTotalSamples48k = linkedMapOf<Int, Long>()
    private val opusPacketDurationMs = linkedMapOf<Int, Long>()
    private var queueOverflowCount = 0L
    private var firstAudioAtNanos: Long? = null
    private var lastAudioAtNanos: Long? = null
    private val nonAudioFrames = ArrayDeque<String>()
    private val sequenceAnomalies = ArrayDeque<String>()
    private val txEvents = ArrayDeque<String>()

    fun reset() = synchronized(lock) {
        realtimeFrames = 0
        audioPackets = 0
        audioBytes = 0
        ae22Frames = 0
        ae23Frames = 0
        commandCounts.clear()
        bodyLengthHistogram.clear()
        firstAudioSequence = null
        lastAudioSequence = null
        sequentialTransitions = 0
        duplicateCandidates = 0
        gapCandidates = 0
        opusValid = 0
        opusInvalid = 0
        opusErrorCounts.clear()
        opusChannelCounts.clear()
        opusTotalSamples48k.clear()
        opusPacketDurationMs.clear()
        queueOverflowCount = 0
        firstAudioAtNanos = null
        lastAudioAtNanos = null
        nonAudioFrames.clear()
        sequenceAnomalies.clear()
        txEvents.clear()
    }

    fun noteQueueOverflow() = synchronized(lock) {
        queueOverflowCount += 1
    }

    fun noteTx(label: String, sequence: Int, written: Boolean) = synchronized(lock) {
        addBounded(txEvents, "$label seq=$sequence written=$written")
    }

    fun accept(
        notification: RoutedNotification,
        receivedAtNanos: Long,
        opusInfo: OpusPacketInfo?,
    ) = synchronized(lock) {
        val frame = notification.frame
        if (frame.type != ProtocolConstants.Type.REALTIME) return@synchronized
        val command = frame.command ?: return@synchronized

        realtimeFrames += 1
        if (notification.source == NotificationSource.AE22) ae22Frames += 1 else ae23Frames += 1
        increment(commandCounts, command)

        if (command != ProtocolConstants.Realtime.AUDIO_DATA) {
            addBounded(
                nonAudioFrames,
                "source=${notification.source} cmd=$command seq=${frame.sequence} " +
                    "bodyBytes=${frame.body.size} bodyHex=${frame.body.toHexPrefix()}",
            )
            return@synchronized
        }

        audioPackets += 1
        audioBytes += frame.body.size
        increment(bodyLengthHistogram, frame.body.size)
        if (firstAudioAtNanos == null) firstAudioAtNanos = receivedAtNanos
        lastAudioAtNanos = receivedAtNanos

        val previous = lastAudioSequence
        if (firstAudioSequence == null) firstAudioSequence = frame.sequence
        if (previous != null) {
            val delta = (frame.sequence - previous) and 0xFF
            when (delta) {
                1 -> sequentialTransitions += 1
                0 -> {
                    duplicateCandidates += 1
                    addBounded(sequenceAnomalies, "$previous->${frame.sequence} delta=0 duplicate-candidate")
                }
                else -> {
                    gapCandidates += 1
                    addBounded(sequenceAnomalies, "$previous->${frame.sequence} delta=$delta gap-candidate")
                }
            }
        }
        lastAudioSequence = frame.sequence

        if (opusInfo != null) {
            if (opusInfo.valid) {
                opusValid += 1
                opusInfo.channels?.let { increment(opusChannelCounts, it) }
                opusInfo.totalSamples48k?.let { samples ->
                    increment(opusTotalSamples48k, samples)
                    if (samples % 48 == 0) increment(opusPacketDurationMs, samples / 48)
                }
            } else {
                opusInvalid += 1
                opusInfo.errorCode?.let { increment(opusErrorCounts, it) }
            }
        }
    }

    fun snapshot(nowNanos: Long = System.nanoTime()): Stage15ProbeSnapshot = synchronized(lock) {
        val first = firstAudioAtNanos
        val last = lastAudioAtNanos ?: nowNanos
        val elapsedSeconds = if (first != null && last > first) (last - first) / 1_000_000_000.0 else 0.0
        Stage15ProbeSnapshot(
            realtimeFrames = realtimeFrames,
            audioPackets = audioPackets,
            audioBytes = audioBytes,
            ae22Frames = ae22Frames,
            ae23Frames = ae23Frames,
            commandCounts = commandCounts.toMap(),
            bodyLengthHistogram = bodyLengthHistogram.toMap(),
            firstAudioSequence = firstAudioSequence,
            lastAudioSequence = lastAudioSequence,
            sequentialTransitions = sequentialTransitions,
            duplicateCandidates = duplicateCandidates,
            gapCandidates = gapCandidates,
            opusValid = opusValid,
            opusInvalid = opusInvalid,
            opusErrorCounts = opusErrorCounts.toMap(),
            opusChannelCounts = opusChannelCounts.toMap(),
            opusTotalSamples48k = opusTotalSamples48k.toMap(),
            opusPacketDurationMs = opusPacketDurationMs.toMap(),
            queueOverflowCount = queueOverflowCount,
            packetsPerSecond = if (elapsedSeconds > 0.0) audioPackets / elapsedSeconds else 0.0,
            nonAudioFrames = nonAudioFrames.toList(),
            sequenceAnomalies = sequenceAnomalies.toList(),
            txEvents = txEvents.toList(),
        )
    }

    private fun increment(map: MutableMap<Int, Long>, key: Int) {
        map[key] = (map[key] ?: 0L) + 1L
    }

    private fun addBounded(queue: ArrayDeque<String>, value: String) {
        if (queue.size >= maxSamples) queue.removeFirst()
        queue.addLast(value)
    }

    private fun ByteArray.toHexPrefix(limit: Int = 32): String =
        take(limit).joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) } +
            if (size > limit) "..." else ""
}
