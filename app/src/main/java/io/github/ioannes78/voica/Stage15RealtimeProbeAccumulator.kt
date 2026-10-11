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
    val protocolSequenceHistogram: Map<Int, Long> = emptyMap(),
    val opusValid: Long = 0,
    val opusInvalid: Long = 0,
    val opusErrorCounts: Map<Int, Long> = emptyMap(),
    val opusChannelCounts: Map<Int, Long> = emptyMap(),
    val opusTotalSamples48k: Map<Int, Long> = emptyMap(),
    val opusPacketDurationMs: Map<Int, Long> = emptyMap(),
    val mediaDurationMs: Double = 0.0,
    val wallElapsedMs: Long? = null,
    val mediaWallRatio: Double = 0.0,
    val packetsPerSecond: Double = 0.0,
    val arrivalGapHistogram: Map<String, Long> = emptyMap(),
    val maxArrivalGapMs: Double = 0.0,
    val phasePacketCounts: Map<String, Long> = emptyMap(),
    val deviceReportedFilename: String? = null,
    val sessionStartMonotonicMs: Long? = null,
    val timelineOffsetsMs: Map<String, Long> = emptyMap(),
    val queueOverflowCount: Long = 0,
    val nonAudioFrames: List<String> = emptyList(),
    val txEvents: List<String> = emptyList(),
) {
    fun render(observerOverflowCount: Int = 0): String = buildString {
        appendLine("Stage 15.0B Realtime Cadence Fact Gate")
        appendLine("realtimeFrames=$realtimeFrames audioPackets=$audioPackets audioBytes=$audioBytes")
        appendLine("source AE22=$ae22Frames AE23=$ae23Frames")
        appendLine("commands=${commandCounts.toSortedMap()}")
        appendLine("bodyLengthHistogram=${bodyLengthHistogram.toSortedMap()}")
        appendLine("protocolSeqHistogram=${protocolSequenceHistogram.toSortedMap()}")
        appendLine("probeQueueOverflow=$queueOverflowCount genericObserverOverflow=$observerOverflowCount")
        appendLine(
            "wallElapsedMs=${wallElapsedMs ?: "--"} mediaDurationMs=${format(mediaDurationMs)} " +
                "mediaWallRatio=${format(mediaWallRatio)} rate=${format(packetsPerSecond)} packets/s",
        )
        appendLine("arrivalGapHistogram=$arrivalGapHistogram maxArrivalGapMs=${format(maxArrivalGapMs)}")
        appendLine("phasePackets=$phasePacketCounts")
        appendLine("deviceReportedFilename=${deviceReportedFilename ?: "--"}")
        appendLine("sessionStartMonotonicMs=${sessionStartMonotonicMs ?: "--"}")
        appendLine("timelineOffsetsMs=$timelineOffsetsMs")
        appendLine("opus valid=$opusValid invalid=$opusInvalid errors=${opusErrorCounts.toSortedMap()}")
        appendLine("opus channels=${opusChannelCounts.toSortedMap()}")
        appendLine("opus totalSamples48k=${opusTotalSamples48k.toSortedMap()}")
        appendLine("opus durationMs=${opusPacketDurationMs.toSortedMap()}")
        appendLine("tx=${txEvents.joinToString()}")
        appendLine("nonAudioFrames:")
        nonAudioFrames.forEach { appendLine("  $it") }
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)
}

internal class Stage15RealtimeProbeAccumulator(
    private val maxSamples: Int = 32,
) {
    private enum class Phase(val label: String) {
        BEFORE_PAUSE("beforePause"),
        PAUSED("paused"),
        AFTER_RESUME("afterResume"),
    }

    private val lock = Any()
    private var realtimeFrames = 0L
    private var audioPackets = 0L
    private var audioBytes = 0L
    private var ae22Frames = 0L
    private var ae23Frames = 0L
    private val commandCounts = linkedMapOf<Int, Long>()
    private val bodyLengthHistogram = linkedMapOf<Int, Long>()
    private val protocolSequenceHistogram = linkedMapOf<Int, Long>()
    private var opusValid = 0L
    private var opusInvalid = 0L
    private val opusErrorCounts = linkedMapOf<Int, Long>()
    private val opusChannelCounts = linkedMapOf<Int, Long>()
    private val opusTotalSamples48k = linkedMapOf<Int, Long>()
    private val opusPacketDurationMs = linkedMapOf<Int, Long>()
    private var mediaSamples48k = 0L
    private val arrivalGapHistogram = linkedMapOf(
        GAP_LT_25 to 0L,
        GAP_25_49 to 0L,
        GAP_50_99 to 0L,
        GAP_100_249 to 0L,
        GAP_GE_250 to 0L,
    )
    private var maxArrivalGapNanos = 0L
    private var queueOverflowCount = 0L
    private var firstAudioAtNanos: Long? = null
    private var lastAudioAtNanos: Long? = null
    private var sessionStartAtNanos: Long? = null
    private var pauseTxAtNanos: Long? = null
    private var devicePausedAtNanos: Long? = null
    private var resumeTxAtNanos: Long? = null
    private var deviceResumedAtNanos: Long? = null
    private var firstAudioAfterResumeAtNanos: Long? = null
    private var stopTxAtNanos: Long? = null
    private var phase = Phase.BEFORE_PAUSE
    private val phasePacketCounts = linkedMapOf(
        Phase.BEFORE_PAUSE to 0L,
        Phase.PAUSED to 0L,
        Phase.AFTER_RESUME to 0L,
    )
    private var deviceReportedFilename: String? = null
    private val nonAudioFrames = ArrayDeque<String>()
    private val txEvents = ArrayDeque<String>()

    fun reset() = synchronized(lock) {
        realtimeFrames = 0
        audioPackets = 0
        audioBytes = 0
        ae22Frames = 0
        ae23Frames = 0
        commandCounts.clear()
        bodyLengthHistogram.clear()
        protocolSequenceHistogram.clear()
        opusValid = 0
        opusInvalid = 0
        opusErrorCounts.clear()
        opusChannelCounts.clear()
        opusTotalSamples48k.clear()
        opusPacketDurationMs.clear()
        mediaSamples48k = 0
        arrivalGapHistogram.keys.toList().forEach { arrivalGapHistogram[it] = 0L }
        maxArrivalGapNanos = 0
        queueOverflowCount = 0
        firstAudioAtNanos = null
        lastAudioAtNanos = null
        sessionStartAtNanos = null
        pauseTxAtNanos = null
        devicePausedAtNanos = null
        resumeTxAtNanos = null
        deviceResumedAtNanos = null
        firstAudioAfterResumeAtNanos = null
        stopTxAtNanos = null
        phase = Phase.BEFORE_PAUSE
        phasePacketCounts.keys.toList().forEach { phasePacketCounts[it] = 0L }
        deviceReportedFilename = null
        nonAudioFrames.clear()
        txEvents.clear()
    }

    fun noteQueueOverflow() = synchronized(lock) {
        queueOverflowCount += 1
    }

    fun noteTx(
        label: String,
        sequence: Int,
        written: Boolean,
        atNanos: Long = System.nanoTime(),
    ) = synchronized(lock) {
        if (written) {
            when (label) {
                TX_START -> {
                    sessionStartAtNanos = atNanos
                    phase = Phase.BEFORE_PAUSE
                }
                TX_PAUSE -> pauseTxAtNanos = atNanos
                TX_RESUME -> resumeTxAtNanos = atNanos
                TX_STOP -> stopTxAtNanos = atNanos
            }
        }
        val offset = relativeMs(atNanos)?.let { " t=+${it}ms" }.orEmpty()
        addBounded(txEvents, "$label seq=$sequence written=$written$offset")
    }

    fun accept(
        notification: RoutedNotification,
        receivedAtNanos: Long,
        opusInfo: OpusPacketInfo?,
    ) = synchronized(lock) {
        val frame = notification.frame
        if (frame.type != ProtocolConstants.Type.REALTIME) return@synchronized
        val command = frame.command ?: return@synchronized
        val body = frame.body

        realtimeFrames += 1
        if (notification.source == NotificationSource.AE22) ae22Frames += 1 else ae23Frames += 1
        increment(commandCounts, command)

        if (command != ProtocolConstants.Realtime.AUDIO_DATA) {
            if (command == ProtocolConstants.Realtime.START) {
                decodeFilename(body)?.let { deviceReportedFilename = it }
            } else if (command == ProtocolConstants.Realtime.DEVICE_STATE && body.isNotEmpty()) {
                when (body[0].toInt() and 0xFF) {
                    DEVICE_STATE_PAUSED -> {
                        devicePausedAtNanos = receivedAtNanos
                        phase = Phase.PAUSED
                    }
                    DEVICE_STATE_RESUMED -> {
                        deviceResumedAtNanos = receivedAtNanos
                        phase = Phase.AFTER_RESUME
                    }
                }
            }
            addBounded(
                nonAudioFrames,
                "source=${notification.source} cmd=$command seq=${frame.sequence} " +
                    "bodyBytes=${body.size} bodyHex=${body.toHexPrefix()}",
            )
            return@synchronized
        }

        audioPackets += 1
        audioBytes += body.size
        increment(bodyLengthHistogram, body.size)
        increment(protocolSequenceHistogram, frame.sequence)
        phasePacketCounts[phase] = (phasePacketCounts[phase] ?: 0L) + 1L

        val previousAudioAt = lastAudioAtNanos
        if (previousAudioAt != null && receivedAtNanos >= previousAudioAt) {
            val gapNanos = receivedAtNanos - previousAudioAt
            val gapMs = gapNanos / 1_000_000.0
            val bucket = gapBucket(gapMs)
            arrivalGapHistogram[bucket] = (arrivalGapHistogram[bucket] ?: 0L) + 1L
            if (gapNanos > maxArrivalGapNanos) maxArrivalGapNanos = gapNanos
        }
        if (firstAudioAtNanos == null) firstAudioAtNanos = receivedAtNanos
        if (phase == Phase.AFTER_RESUME && firstAudioAfterResumeAtNanos == null) {
            firstAudioAfterResumeAtNanos = receivedAtNanos
        }
        lastAudioAtNanos = receivedAtNanos

        if (opusInfo != null) {
            if (opusInfo.valid) {
                opusValid += 1
                opusInfo.channels?.let { increment(opusChannelCounts, it) }
                opusInfo.totalSamples48k?.let { samples ->
                    increment(opusTotalSamples48k, samples)
                    if (samples > 0) mediaSamples48k += samples.toLong()
                    if (samples % 48 == 0) increment(opusPacketDurationMs, samples / 48)
                }
            } else {
                opusInvalid += 1
                opusInfo.errorCode?.let { increment(opusErrorCounts, it) }
            }
        }
    }

    fun snapshot(nowNanos: Long = System.nanoTime()): Stage15ProbeSnapshot = synchronized(lock) {
        val start = sessionStartAtNanos
        val end = stopTxAtNanos ?: nowNanos
        val wallElapsedMs = if (start != null && end >= start) (end - start) / 1_000_000L else null
        val mediaDurationMs = mediaSamples48k / 48.0
        val ratio = if (wallElapsedMs != null && wallElapsedMs > 0L) mediaDurationMs / wallElapsedMs else 0.0
        val pps = if (wallElapsedMs != null && wallElapsedMs > 0L) {
            audioPackets / (wallElapsedMs / 1_000.0)
        } else {
            0.0
        }
        Stage15ProbeSnapshot(
            realtimeFrames = realtimeFrames,
            audioPackets = audioPackets,
            audioBytes = audioBytes,
            ae22Frames = ae22Frames,
            ae23Frames = ae23Frames,
            commandCounts = commandCounts.toMap(),
            bodyLengthHistogram = bodyLengthHistogram.toMap(),
            protocolSequenceHistogram = protocolSequenceHistogram.toMap(),
            opusValid = opusValid,
            opusInvalid = opusInvalid,
            opusErrorCounts = opusErrorCounts.toMap(),
            opusChannelCounts = opusChannelCounts.toMap(),
            opusTotalSamples48k = opusTotalSamples48k.toMap(),
            opusPacketDurationMs = opusPacketDurationMs.toMap(),
            mediaDurationMs = mediaDurationMs,
            wallElapsedMs = wallElapsedMs,
            mediaWallRatio = ratio,
            packetsPerSecond = pps,
            arrivalGapHistogram = arrivalGapHistogram.toMap(),
            maxArrivalGapMs = maxArrivalGapNanos / 1_000_000.0,
            phasePacketCounts = phasePacketCounts.entries.associate { it.key.label to it.value },
            deviceReportedFilename = deviceReportedFilename,
            sessionStartMonotonicMs = start?.div(1_000_000L),
            timelineOffsetsMs = buildTimelineOffsets(),
            queueOverflowCount = queueOverflowCount,
            nonAudioFrames = nonAudioFrames.toList(),
            txEvents = txEvents.toList(),
        )
    }

    private fun buildTimelineOffsets(): Map<String, Long> = linkedMapOf<String, Long>().apply {
        addOffset("firstAudio", firstAudioAtNanos)
        addOffset("pauseTx", pauseTxAtNanos)
        addOffset("devicePaused", devicePausedAtNanos)
        addOffset("resumeTx", resumeTxAtNanos)
        addOffset("deviceResumed", deviceResumedAtNanos)
        addOffset("firstAudioAfterResume", firstAudioAfterResumeAtNanos)
        addOffset("stopTx", stopTxAtNanos)
        addOffset("lastAudio", lastAudioAtNanos)
    }

    private fun MutableMap<String, Long>.addOffset(label: String, atNanos: Long?) {
        relativeMs(atNanos)?.let { this[label] = it }
    }

    private fun relativeMs(atNanos: Long?): Long? {
        val start = sessionStartAtNanos ?: return null
        val at = atNanos ?: return null
        return (at - start) / 1_000_000L
    }

    private fun gapBucket(gapMs: Double): String = when {
        gapMs < 25.0 -> GAP_LT_25
        gapMs < 50.0 -> GAP_25_49
        gapMs < 100.0 -> GAP_50_99
        gapMs < 250.0 -> GAP_100_249
        else -> GAP_GE_250
    }

    private fun decodeFilename(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val zeroIndex = body.indexOfFirst { it.toInt() == 0 }
        val end = if (zeroIndex >= 0) zeroIndex else body.size
        if (end <= 0) return null
        return body.copyOfRange(0, end)
            .toString(Charsets.US_ASCII)
            .trim()
            .takeIf { it.isNotEmpty() }
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

    private companion object {
        const val TX_START = "START"
        const val TX_PAUSE = "PAUSE"
        const val TX_RESUME = "RESUME"
        const val TX_STOP = "STOP"
        const val DEVICE_STATE_RESUMED = 0
        const val DEVICE_STATE_PAUSED = 1
        const val GAP_LT_25 = "<25ms"
        const val GAP_25_49 = "25-49ms"
        const val GAP_50_99 = "50-99ms"
        const val GAP_100_249 = "100-249ms"
        const val GAP_GE_250 = ">=250ms"
    }
}
