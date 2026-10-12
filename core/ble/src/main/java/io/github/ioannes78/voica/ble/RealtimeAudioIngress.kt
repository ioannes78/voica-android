package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolConstants
import java.io.Closeable
import kotlinx.coroutines.channels.Channel

enum class RealtimeAudioOfferResult {
    ACCEPTED,
    ACCEPTED_AFTER_DISCONTINUITY,
    IGNORED,
    REJECTED,
}

interface RealtimeAudioConsumer {
    /**
     * Called synchronously from the GATT notification path.
     *
     * Implementations must stay non-blocking and must not decode Opus, create PCM, touch Room,
     * or invoke ASR. The production ingress only copies the confirmed 160-byte transport body
     * into a bounded encoded queue and returns.
     */
    fun offer(
        notification: RoutedNotification,
        sessionGeneration: Long,
        receivedAtNanos: Long,
    ): RealtimeAudioOfferResult

    /** Called when the owning GATT generation closes. */
    fun onSessionClosed(sessionGeneration: Long)
}

data class RealtimeEncodedBlock(
    val sessionGeneration: Long,
    val continuityId: Long,
    val source: NotificationSource,
    /** Raw protocol header sequence. Stage 15.0 proved this is not an audio ordering sequence. */
    val protocolSequence: Int,
    /** Monotonic transport-arrival timestamp for diagnostics only; never canonical media time. */
    val receivedAtNanos: Long,
    val encodedBytes: ByteArray,
    /** True only on the first accepted block after a known transport discontinuity. */
    val discontinuityBefore: Boolean,
)

data class RealtimeOpusPacket(
    val sessionGeneration: Long,
    val continuityId: Long,
    val source: NotificationSource,
    val protocolSequence: Int,
    val receivedAtNanos: Long,
    val packetIndexInBlock: Int,
    val encodedBytes: ByteArray,
    val discontinuityBefore: Boolean,
)

data class RealtimeAudioIngressSnapshot(
    val activeSessionGeneration: Long? = null,
    val continuityId: Long = 0,
    val acceptedBlocks: Long = 0,
    val acceptedBytes: Long = 0,
    val malformedBlocks: Long = 0,
    val rejectedAfterClose: Long = 0,
    val overflowEvents: Long = 0,
    val generationTransitions: Long = 0,
    val sessionClosures: Long = 0,
    val droppedBlocks: Long = 0,
    val droppedBytes: Long = 0,
    val queueBlocks: Int = 0,
    val queueBytes: Int = 0,
    val peakQueueBlocks: Int = 0,
    val peakQueueBytes: Int = 0,
)

/**
 * Stage 15 production encoded ingress.
 *
 * True-device gates froze the realtime transport unit as one 160-byte TYPE=1/CMD=1 body every
 * ~80 ms. The queue is bounded to 32 blocks (~2.56 s) so short Android scheduling/background
 * bursts do not create avoidable media loss while the pipeline can still catch up quickly. Overflow
 * still prefers current/live data: stale queued blocks are discarded, continuity advances, and the
 * incoming block is accepted as the first block of the new continuity segment.
 */
class RealtimeAudioIngress(
    private val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
    private val maxEncodedBytes: Int = DEFAULT_MAX_ENCODED_BYTES,
) : RealtimeAudioConsumer, Closeable {
    init {
        require(maxBlocks > 0)
        require(maxEncodedBytes >= REALTIME_BLOCK_BYTES)
    }

    private val lock = Any()
    private val queue = ArrayDeque<RealtimeEncodedBlock>()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    private var closed = false
    private var activeSessionGeneration: Long? = null
    private var continuityId = 0L
    private var pendingDiscontinuity = false
    private var queueBytes = 0

    private var acceptedBlocks = 0L
    private var acceptedBytes = 0L
    private var malformedBlocks = 0L
    private var rejectedAfterClose = 0L
    private var overflowEvents = 0L
    private var generationTransitions = 0L
    private var sessionClosures = 0L
    private var droppedBlocks = 0L
    private var droppedBytes = 0L
    private var peakQueueBlocks = 0
    private var peakQueueBytes = 0

    override fun offer(
        notification: RoutedNotification,
        sessionGeneration: Long,
        receivedAtNanos: Long,
    ): RealtimeAudioOfferResult {
        if (!notification.isRealtimeAudioFrame()) return RealtimeAudioOfferResult.IGNORED

        var shouldSignal = false
        val result = synchronized(lock) {
            if (closed) {
                rejectedAfterClose += 1
                return@synchronized RealtimeAudioOfferResult.REJECTED
            }

            if (activeSessionGeneration == null) {
                activeSessionGeneration = sessionGeneration
            } else if (activeSessionGeneration != sessionGeneration) {
                generationTransitions += 1
                advanceContinuityLocked(clearBacklog = true)
                activeSessionGeneration = sessionGeneration
            }

            val data = notification.frame.data
            val bodySize = (data.size - PROTOCOL_PREFIX_BYTES).coerceAtLeast(0)
            if (bodySize != REALTIME_BLOCK_BYTES) {
                malformedBlocks += 1
                advanceContinuityLocked(clearBacklog = true)
                return@synchronized RealtimeAudioOfferResult.REJECTED
            }

            val wouldOverflow =
                queue.size + 1 > maxBlocks || queueBytes + bodySize > maxEncodedBytes
            val recoveredOverflow = if (wouldOverflow) {
                overflowEvents += 1
                advanceContinuityLocked(clearBacklog = true)
                true
            } else {
                false
            }

            val block = RealtimeEncodedBlock(
                sessionGeneration = sessionGeneration,
                continuityId = continuityId,
                source = notification.source,
                protocolSequence = notification.frame.sequence,
                receivedAtNanos = receivedAtNanos,
                encodedBytes = data.copyOfRange(PROTOCOL_PREFIX_BYTES, data.size),
                discontinuityBefore = pendingDiscontinuity,
            )
            pendingDiscontinuity = false
            queue.addLast(block)
            queueBytes += block.encodedBytes.size
            acceptedBlocks += 1
            acceptedBytes += block.encodedBytes.size
            peakQueueBlocks = maxOf(peakQueueBlocks, queue.size)
            peakQueueBytes = maxOf(peakQueueBytes, queueBytes)
            shouldSignal = true

            if (recoveredOverflow || block.discontinuityBefore) {
                RealtimeAudioOfferResult.ACCEPTED_AFTER_DISCONTINUITY
            } else {
                RealtimeAudioOfferResult.ACCEPTED
            }
        }

        if (shouldSignal) signal.trySend(Unit)
        return result
    }

    fun poll(): RealtimeEncodedBlock? = synchronized(lock) {
        removeFirstLocked()
    }

    /** Waits for the next encoded block without doing work on the GATT callback thread. */
    suspend fun receive(): RealtimeEncodedBlock? {
        while (true) {
            synchronized(lock) {
                removeFirstLocked()?.let { return it }
                if (closed) return null
            }
            if (signal.receiveCatching().getOrNull() == null) return null
        }
    }

    fun snapshot(): RealtimeAudioIngressSnapshot = synchronized(lock) {
        RealtimeAudioIngressSnapshot(
            activeSessionGeneration = activeSessionGeneration,
            continuityId = continuityId,
            acceptedBlocks = acceptedBlocks,
            acceptedBytes = acceptedBytes,
            malformedBlocks = malformedBlocks,
            rejectedAfterClose = rejectedAfterClose,
            overflowEvents = overflowEvents,
            generationTransitions = generationTransitions,
            sessionClosures = sessionClosures,
            droppedBlocks = droppedBlocks,
            droppedBytes = droppedBytes,
            queueBlocks = queue.size,
            queueBytes = queueBytes,
            peakQueueBlocks = peakQueueBlocks,
            peakQueueBytes = peakQueueBytes,
        )
    }

    /** Probe/test helper; production lifecycle normally creates a fresh ingress owner. */
    fun reset() = synchronized(lock) {
        check(!closed) { "RealtimeAudioIngress is closed" }
        queue.clear()
        queueBytes = 0
        activeSessionGeneration = null
        continuityId = 0
        pendingDiscontinuity = false
        acceptedBlocks = 0
        acceptedBytes = 0
        malformedBlocks = 0
        rejectedAfterClose = 0
        overflowEvents = 0
        generationTransitions = 0
        sessionClosures = 0
        droppedBlocks = 0
        droppedBytes = 0
        peakQueueBlocks = 0
        peakQueueBytes = 0
    }

    override fun onSessionClosed(sessionGeneration: Long) {
        synchronized(lock) {
            if (closed || activeSessionGeneration != sessionGeneration) return
            sessionClosures += 1
            advanceContinuityLocked(clearBacklog = true)
            activeSessionGeneration = null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            dropBacklogLocked()
            activeSessionGeneration = null
        }
        signal.close()
    }

    private fun removeFirstLocked(): RealtimeEncodedBlock? {
        if (queue.isEmpty()) return null
        val block = queue.removeFirst()
        queueBytes -= block.encodedBytes.size
        return block
    }

    private fun advanceContinuityLocked(clearBacklog: Boolean) {
        if (clearBacklog) dropBacklogLocked()
        continuityId += 1
        pendingDiscontinuity = true
    }

    private fun dropBacklogLocked() {
        if (queue.isEmpty()) return
        droppedBlocks += queue.size.toLong()
        droppedBytes += queueBytes.toLong()
        queue.clear()
        queueBytes = 0
    }

    companion object {
        const val REALTIME_BLOCK_BYTES = 160
        const val OPUS_PACKET_BYTES = 40
        const val OPUS_PACKETS_PER_BLOCK = 4
        const val DEFAULT_MAX_BLOCKS = 32
        const val DEFAULT_MAX_ENCODED_BYTES = DEFAULT_MAX_BLOCKS * REALTIME_BLOCK_BYTES
        private const val PROTOCOL_PREFIX_BYTES = 2
    }
}

/**
 * Pure transport deaggregation. No Opus parsing/decoding happens here.
 */
object RealtimeOpusPacketizer {
    fun split(block: RealtimeEncodedBlock): List<RealtimeOpusPacket> {
        require(block.encodedBytes.size == RealtimeAudioIngress.REALTIME_BLOCK_BYTES) {
            "realtime block must be ${RealtimeAudioIngress.REALTIME_BLOCK_BYTES} bytes"
        }
        return List(RealtimeAudioIngress.OPUS_PACKETS_PER_BLOCK) { index ->
            val start = index * RealtimeAudioIngress.OPUS_PACKET_BYTES
            val end = start + RealtimeAudioIngress.OPUS_PACKET_BYTES
            RealtimeOpusPacket(
                sessionGeneration = block.sessionGeneration,
                continuityId = block.continuityId,
                source = block.source,
                protocolSequence = block.protocolSequence,
                receivedAtNanos = block.receivedAtNanos,
                packetIndexInBlock = index,
                encodedBytes = block.encodedBytes.copyOfRange(start, end),
                discontinuityBefore = block.discontinuityBefore && index == 0,
            )
        }
    }
}

internal fun RoutedNotification.isRealtimeAudioFrame(): Boolean =
    frame.type == ProtocolConstants.Type.REALTIME &&
        frame.command == ProtocolConstants.Realtime.AUDIO_DATA
