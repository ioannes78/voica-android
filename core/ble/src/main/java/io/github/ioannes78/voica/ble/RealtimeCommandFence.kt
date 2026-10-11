package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolFrame
import java.util.concurrent.atomic.AtomicLong

enum class RealtimeCommandFenceCommitResult {
    COMMITTED,
    AMBIGUOUS,
    DOWNSTREAM_REJECTED,
    STALE_TOKEN,
}

data class RealtimeCommandFenceToken(
    val id: Long,
    val command: RealtimeControlCommand,
    val transportGeneration: Long,
)

data class RealtimeCommandFenceSnapshot(
    val activeCommand: RealtimeControlCommand? = null,
    val activeGeneration: Long? = null,
    val bufferedFrames: Int = 0,
    val bufferedBytes: Int = 0,
    val peakBufferedFrames: Int = 0,
    val peakBufferedBytes: Int = 0,
    val preparedCount: Long = 0,
    val committedCount: Long = 0,
    val abortedCount: Long = 0,
    val ambiguousCount: Long = 0,
    val overflowCount: Long = 0,
    val staleOfferCount: Long = 0,
    val staleTokenCount: Long = 0,
    val droppedFrames: Long = 0,
    val droppedBytes: Long = 0,
    val downstreamRejectedCount: Long = 0,
)

/**
 * Bounded generation-aware fence used while START/RESUME write completion is unresolved.
 *
 * The fence stays on the BLE callback path, so offer() only copies already-parsed realtime frames
 * into a small bounded queue. It never decodes Opus, creates PCM, suspends, or performs ASR work.
 */
class RealtimeCommandFence(
    private val downstream: RealtimeAudioConsumer,
    private val maxBufferedFrames: Int = DEFAULT_MAX_BUFFERED_FRAMES,
    private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES,
) : RealtimeAudioConsumer {
    init {
        require(maxBufferedFrames > 0)
        require(maxBufferedBytes >= MIN_REALTIME_FRAME_BYTES)
    }

    private val lock = Any()
    private val tokenCounter = AtomicLong(0)
    private var active: ActiveFence? = null

    private var preparedCount = 0L
    private var committedCount = 0L
    private var abortedCount = 0L
    private var ambiguousCount = 0L
    private var overflowCount = 0L
    private var staleOfferCount = 0L
    private var staleTokenCount = 0L
    private var droppedFrames = 0L
    private var droppedBytes = 0L
    private var downstreamRejectedCount = 0L
    private var peakBufferedFrames = 0
    private var peakBufferedBytes = 0

    fun prepare(
        command: RealtimeControlCommand,
        transportGeneration: Long,
    ): RealtimeCommandFenceToken = synchronized(lock) {
        require(command == RealtimeControlCommand.START || command == RealtimeControlCommand.RESUME) {
            "Only START/RESUME require an audio command fence"
        }
        require(transportGeneration > 0L)
        check(active == null) { "Realtime command fence is already active" }
        val token = RealtimeCommandFenceToken(
            id = tokenCounter.incrementAndGet(),
            command = command,
            transportGeneration = transportGeneration,
        )
        active = ActiveFence(token)
        preparedCount += 1
        token
    }

    override fun offer(
        notification: RoutedNotification,
        sessionGeneration: Long,
        receivedAtNanos: Long,
    ): RealtimeAudioOfferResult = synchronized(lock) {
        val current = active
        if (current == null) {
            return@synchronized downstream.offer(notification, sessionGeneration, receivedAtNanos)
        }
        if (!notification.isRealtimeAudioFrame()) {
            return@synchronized downstream.offer(notification, sessionGeneration, receivedAtNanos)
        }
        if (sessionGeneration != current.token.transportGeneration) {
            staleOfferCount += 1
            current.ambiguous = true
            return@synchronized RealtimeAudioOfferResult.REJECTED
        }

        val frameBytes = notification.frame.data.size
        val wouldOverflow =
            current.buffer.size + 1 > maxBufferedFrames ||
                current.bufferedBytes + frameBytes > maxBufferedBytes
        if (wouldOverflow) {
            overflowCount += 1
            current.ambiguous = true
            dropBufferedLocked(current)
            droppedFrames += 1
            droppedBytes += frameBytes.toLong()
            return@synchronized RealtimeAudioOfferResult.REJECTED
        }

        val copied = BufferedFrame(
            notification =
                RoutedNotification(
                    source = notification.source,
                    frame = ProtocolFrame(
                        sequence = notification.frame.sequence,
                        data = notification.frame.data.copyOf(),
                    ),
                ),
            sessionGeneration = sessionGeneration,
            receivedAtNanos = receivedAtNanos,
        )
        current.buffer.addLast(copied)
        current.bufferedBytes += frameBytes
        peakBufferedFrames = maxOf(peakBufferedFrames, current.buffer.size)
        peakBufferedBytes = maxOf(peakBufferedBytes, current.bufferedBytes)
        RealtimeAudioOfferResult.ACCEPTED
    }

    fun commit(token: RealtimeCommandFenceToken): RealtimeCommandFenceCommitResult = synchronized(lock) {
        val current = active
        if (current == null || current.token != token) {
            staleTokenCount += 1
            return@synchronized RealtimeCommandFenceCommitResult.STALE_TOKEN
        }
        if (current.ambiguous) {
            ambiguousCount += 1
            dropBufferedLocked(current)
            active = null
            return@synchronized RealtimeCommandFenceCommitResult.AMBIGUOUS
        }

        var rejected = false
        while (current.buffer.isNotEmpty()) {
            val frame = current.buffer.removeFirst()
            current.bufferedBytes -= frame.notification.frame.data.size
            val result = downstream.offer(
                notification = frame.notification,
                sessionGeneration = frame.sessionGeneration,
                receivedAtNanos = frame.receivedAtNanos,
            )
            if (result == RealtimeAudioOfferResult.REJECTED) {
                rejected = true
                downstreamRejectedCount += 1
            }
        }
        active = null
        committedCount += 1
        if (rejected) {
            RealtimeCommandFenceCommitResult.DOWNSTREAM_REJECTED
        } else {
            RealtimeCommandFenceCommitResult.COMMITTED
        }
    }

    fun abort(token: RealtimeCommandFenceToken): Boolean = synchronized(lock) {
        val current = active
        if (current == null || current.token != token) {
            staleTokenCount += 1
            return@synchronized false
        }
        dropBufferedLocked(current)
        active = null
        abortedCount += 1
        true
    }

    override fun onSessionClosed(sessionGeneration: Long) {
        synchronized(lock) {
            val current = active
            if (current != null && current.token.transportGeneration == sessionGeneration) {
                dropBufferedLocked(current)
                active = null
                abortedCount += 1
            }
        }
        downstream.onSessionClosed(sessionGeneration)
    }

    fun snapshot(): RealtimeCommandFenceSnapshot = synchronized(lock) {
        val current = active
        RealtimeCommandFenceSnapshot(
            activeCommand = current?.token?.command,
            activeGeneration = current?.token?.transportGeneration,
            bufferedFrames = current?.buffer?.size ?: 0,
            bufferedBytes = current?.bufferedBytes ?: 0,
            peakBufferedFrames = peakBufferedFrames,
            peakBufferedBytes = peakBufferedBytes,
            preparedCount = preparedCount,
            committedCount = committedCount,
            abortedCount = abortedCount,
            ambiguousCount = ambiguousCount,
            overflowCount = overflowCount,
            staleOfferCount = staleOfferCount,
            staleTokenCount = staleTokenCount,
            droppedFrames = droppedFrames,
            droppedBytes = droppedBytes,
            downstreamRejectedCount = downstreamRejectedCount,
        )
    }

    private fun dropBufferedLocked(current: ActiveFence) {
        if (current.buffer.isEmpty()) return
        droppedFrames += current.buffer.size.toLong()
        droppedBytes += current.bufferedBytes.toLong()
        current.buffer.clear()
        current.bufferedBytes = 0
    }

    private data class BufferedFrame(
        val notification: RoutedNotification,
        val sessionGeneration: Long,
        val receivedAtNanos: Long,
    )

    private class ActiveFence(
        val token: RealtimeCommandFenceToken,
    ) {
        val buffer = ArrayDeque<BufferedFrame>()
        var bufferedBytes: Int = 0
        var ambiguous: Boolean = false
    }

    companion object {
        const val DEFAULT_MAX_BUFFERED_FRAMES = 4
        const val DEFAULT_MAX_BUFFERED_BYTES = 1_024
        private const val MIN_REALTIME_FRAME_BYTES = 162
    }
}
