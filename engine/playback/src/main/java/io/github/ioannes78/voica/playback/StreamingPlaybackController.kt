package io.github.ioannes78.voica.playback

import io.github.ioannes78.voica.audio.AudioContainerKind
import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PlaybackAudioSource
import io.github.ioannes78.voica.audio.PlaybackAudioSourceDescriptor
import io.github.ioannes78.voica.audio.PlaybackController
import io.github.ioannes78.voica.audio.PlaybackError
import io.github.ioannes78.voica.audio.PlaybackErrorCategory
import io.github.ioannes78.voica.audio.PlaybackErrorCode
import io.github.ioannes78.voica.audio.PlaybackSnapshot
import io.github.ioannes78.voica.audio.PlaybackState
import io.github.ioannes78.voica.audio.sampleIndexToPcmByteOffset
import io.github.ioannes78.voica.audio.sampleIndexToTimeUs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StreamingPlaybackController(
    private val sourceResolver: AudioSourceResolver,
    private val sinkFactory: PlaybackAudioSinkFactory,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackController {
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot())
    private val mutableState = MutableStateFlow(PlaybackState.IDLE)
    private val mutablePosition = MutableStateFlow(0L)
    private val latestSeekRequest = AtomicLong(0L)
    private val playbackGeneration = AtomicLong(0L)

    private var session: Session? = null
    private var writerJob: Job? = null
    private var positionJob: Job? = null

    override val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()
    override val stateFlow: StateFlow<PlaybackState> = mutableState.asStateFlow()
    override val positionFlow: StateFlow<Long> = mutablePosition.asStateFlow()

    override suspend fun load(recordingId: String) {
        require(recordingId.isNotBlank())
        mutex.withLock {
            ensureNotReleased()
            unloadLocked(nextState = PlaybackState.PREPARING)
            publish(
                PlaybackSnapshot(
                    recordingId = recordingId,
                    state = PlaybackState.PREPARING,
                ),
            )

            val source = runCatching {
                sourceResolver.resolvePlaybackSource(recordingId)
            }.getOrElse { error ->
                publishSourceError(
                    recordingId,
                    PlaybackErrorCode.SOURCE_INTEGRITY_FAILED,
                    error,
                )
                return
            }

            if (source == null) {
                publishSourceError(
                    recordingId,
                    PlaybackErrorCode.SOURCE_NOT_AVAILABLE,
                    null,
                )
                return
            }

            val descriptor = source.descriptor
            val validationError = validateDescriptor(descriptor)
            if (validationError != null) {
                source.handle.close()
                publish(
                    PlaybackSnapshot(
                        recordingId = recordingId,
                        state = PlaybackState.ERROR,
                        error = PlaybackError(
                            code = PlaybackErrorCode.INVALID_CANONICAL_WAV,
                            category = PlaybackErrorCategory.SOURCE,
                            recoverable = false,
                            diagnosticDetail = validationError,
                        ),
                    ),
                )
                return
            }

            val sink = runCatching { sinkFactory.create(descriptor) }
                .getOrElse { error ->
                    source.handle.close()
                    publish(
                        PlaybackSnapshot(
                            recordingId = recordingId,
                            state = PlaybackState.ERROR,
                            error = PlaybackError(
                                code = PlaybackErrorCode.AUDIO_TRACK_INIT_FAILED,
                                category = PlaybackErrorCategory.OUTPUT,
                                recoverable = true,
                                diagnosticDetail = error.message,
                            ),
                        ),
                    )
                    return
                }

            val totalSamples = requireNotNull(descriptor.totalSampleCount)
            val tracker = PlaybackPositionTracker().also {
                it.rebase(0L, sink.position().framePosition)
            }
            session = Session(
                generation = playbackGeneration.incrementAndGet(),
                source = source,
                sink = sink,
                totalSamples = totalSamples,
                tracker = tracker,
            )
            publish(
                PlaybackSnapshot(
                    recordingId = recordingId,
                    state = PlaybackState.READY,
                    durationSampleCount = totalSamples,
                    durationUs = sampleIndexToTimeUs(
                        totalSamples,
                        CanonicalPcmProfile.SAMPLE_RATE_HZ,
                    ),
                ),
            )
        }
    }

    override suspend fun play() {
        mutex.withLock {
            ensureNotReleased()
            val current = session ?: return
            if (mutableSnapshot.value.state == PlaybackState.COMPLETED) {
                performSeekLocked(
                    requestGeneration = latestSeekRequest.incrementAndGet(),
                    targetSample = 0L,
                    resumeAfterSeek = false,
                    preserveReadyState = true,
                )
            }

            val state = mutableSnapshot.value.state
            if (state != PlaybackState.READY && state != PlaybackState.PAUSED) return
            current.sink.play()
            startWriterLocked(current)
            startPositionObserverLocked(current)
            publish(
                mutableSnapshot.value.copy(
                    state = PlaybackState.PLAYING,
                    error = null,
                ),
            )
        }
    }

    override suspend fun pause() {
        mutex.withLock {
            ensureNotReleased()
            pauseLocked()
        }
    }

    override suspend fun seekToSample(sampleIndex: Long) {
        val request = latestSeekRequest.incrementAndGet()
        mutex.withLock {
            ensureNotReleased()
            if (request != latestSeekRequest.get()) return
            val stateBefore = mutableSnapshot.value.state
            val resume = stateBefore == PlaybackState.PLAYING
            val preserveReady =
                stateBefore == PlaybackState.READY &&
                    sampleIndex.coerceAtLeast(0L) == 0L
            performSeekLocked(
                requestGeneration = request,
                targetSample = sampleIndex,
                resumeAfterSeek = resume,
                preserveReadyState = preserveReady,
            )
        }
    }

    override suspend fun setSpeed(speed: Float) {
        mutex.withLock {
            ensureNotReleased()
            val current = session ?: return
            val normalized = SUPPORTED_SPEEDS.firstOrNull {
                kotlin.math.abs(it - speed) < SPEED_EPSILON
            }
            if (normalized == null || !current.sink.setSpeed(speed)) {
                publish(
                    mutableSnapshot.value.copy(
                        error = PlaybackError(
                            code = PlaybackErrorCode.PLAYBACK_SPEED_UNSUPPORTED,
                            category = PlaybackErrorCategory.SPEED,
                            recoverable = true,
                            diagnosticDetail = "requested=$speed",
                        ),
                    ),
                )
                return
            }
            publish(
                mutableSnapshot.value.copy(
                    speed = normalized,
                    error = null,
                ),
            )
        }
    }

    override suspend fun unload() {
        mutex.withLock {
            ensureNotReleased()
            unloadLocked(nextState = PlaybackState.IDLE)
        }
    }

    override suspend fun release() {
        mutex.withLock {
            if (mutableSnapshot.value.state == PlaybackState.RELEASED) return
            unloadLocked(nextState = PlaybackState.RELEASED)
        }
    }

    suspend fun reportRecoverableError(error: PlaybackError) {
        mutex.withLock {
            if (mutableSnapshot.value.state == PlaybackState.RELEASED) return
            publish(mutableSnapshot.value.copy(error = error))
        }
    }

    suspend fun rebaseOutputPosition() {
        mutex.withLock {
            val current = session ?: return
            val sample = updatePresentedPositionLocked(current)
            current.tracker.rebase(
                sample,
                current.sink.position().framePosition,
            )
        }
    }

    private suspend fun pauseLocked() {
        val current = session ?: return
        if (mutableSnapshot.value.state != PlaybackState.PLAYING) return
        current.sink.pause()
        updatePresentedPositionLocked(current)
        writerJob?.cancelAndJoin()
        writerJob = null
        positionJob?.cancel()
        positionJob = null
        publish(
            mutableSnapshot.value.copy(
                state = PlaybackState.PAUSED,
            ),
        )
    }

    private suspend fun performSeekLocked(
        requestGeneration: Long,
        targetSample: Long,
        resumeAfterSeek: Boolean,
        preserveReadyState: Boolean,
    ) {
        val current = session ?: return
        val target = targetSample.coerceIn(0L, current.totalSamples)

        writerJob?.cancelAndJoin()
        writerJob = null
        positionJob?.cancel()
        positionJob = null
        current.sink.pause()
        current.sink.flush()
        current.sourceSampleCursor.set(target)
        current.submittedSample.set(target)
        current.sourceExhausted.set(target >= current.totalSamples)
        current.tracker.rebase(target, current.sink.position().framePosition)

        val discontinuity = mutableSnapshot.value.discontinuityGeneration + 1L
        publish(
            mutableSnapshot.value.copy(
                state = PlaybackState.SEEKING,
                positionSampleIndex = target,
                positionUs = sampleIndexToTimeUs(
                    target,
                    CanonicalPcmProfile.SAMPLE_RATE_HZ,
                ),
                seekGeneration = requestGeneration,
                discontinuityGeneration = discontinuity,
                error = null,
            ),
        )

        if (requestGeneration != latestSeekRequest.get()) return

        if (target >= current.totalSamples) {
            publish(
                mutableSnapshot.value.copy(
                    state = PlaybackState.COMPLETED,
                    positionSampleIndex = current.totalSamples,
                    positionUs = mutableSnapshot.value.durationUs,
                ),
            )
            return
        }

        when {
            resumeAfterSeek -> {
                current.sink.play()
                startWriterLocked(current)
                startPositionObserverLocked(current)
                publish(mutableSnapshot.value.copy(state = PlaybackState.PLAYING))
            }
            preserveReadyState ->
                publish(mutableSnapshot.value.copy(state = PlaybackState.READY))
            else ->
                publish(mutableSnapshot.value.copy(state = PlaybackState.PAUSED))
        }
    }

    private fun startWriterLocked(current: Session) {
        if (writerJob?.isActive == true || current.sourceExhausted.get()) return
        val generation = current.generation
        writerJob = scope.launch {
            val descriptor = current.source.descriptor
            val bytesPerFrame = requireNotNull(descriptor.bytesPerFrame)
            val pcmDataOffset = requireNotNull(descriptor.pcmDataOffsetBytes)
            val buffer = ByteArray(READ_BUFFER_BYTES)

            try {
                while (
                    currentCoroutineContext().isActive &&
                    session?.generation == generation
                ) {
                    val cursor = current.sourceSampleCursor.get()
                    val remainingSamples = current.totalSamples - cursor
                    if (remainingSamples <= 0L) {
                        current.sourceExhausted.set(true)
                        break
                    }

                    val maxBytes = minOf(
                        buffer.size.toLong(),
                        remainingSamples * bytesPerFrame,
                    ).toInt()
                    val alignedBytes = maxBytes - (maxBytes % bytesPerFrame)
                    if (alignedBytes <= 0) {
                        current.sourceExhausted.set(true)
                        break
                    }

                    val offset = sampleIndexToPcmByteOffset(
                        sampleIndex = cursor,
                        totalSampleCount = current.totalSamples,
                        pcmDataOffsetBytes = pcmDataOffset,
                        bytesPerFrame = bytesPerFrame,
                    )
                    val read = withContext(ioDispatcher) {
                        current.source.handle.readAt(
                            offset = offset,
                            target = buffer,
                            length = alignedBytes,
                        )
                    }
                    if (read < 0) {
                        current.sourceExhausted.set(true)
                        break
                    }
                    if (read == 0) {
                        delay(IDLE_WRITE_DELAY_MS)
                        continue
                    }
                    if (read % bytesPerFrame != 0) {
                        throw IllegalStateException(
                            "unaligned source read: $read / $bytesPerFrame",
                        )
                    }

                    var writtenTotal = 0
                    while (
                        writtenTotal < read &&
                        currentCoroutineContext().isActive &&
                        session?.generation == generation
                    ) {
                        val written = current.sink.write(
                            buffer,
                            writtenTotal,
                            read - writtenTotal,
                        )
                        if (written < 0) {
                            throw IllegalStateException(
                                "AudioTrack write failed: $written",
                            )
                        }
                        if (written == 0) {
                            delay(IDLE_WRITE_DELAY_MS)
                            continue
                        }
                        if (written % bytesPerFrame != 0) {
                            throw IllegalStateException(
                                "unaligned sink write: $written / $bytesPerFrame",
                            )
                        }
                        writtenTotal += written
                        val frames = written / bytesPerFrame
                        current.sourceSampleCursor.addAndGet(frames.toLong())
                        current.submittedSample.addAndGet(frames.toLong())
                    }
                }
            } catch (error: Throwable) {
                if (currentCoroutineContext().isActive) {
                    reportFatalAsync(
                        generation,
                        PlaybackError(
                            code = PlaybackErrorCode.AUDIO_READ_FAILED,
                            category = PlaybackErrorCategory.OUTPUT,
                            recoverable = true,
                            diagnosticDetail = error.message,
                        ),
                    )
                }
            }
        }
    }

    private fun startPositionObserverLocked(current: Session) {
        if (positionJob?.isActive == true) return
        val generation = current.generation
        positionJob = scope.launch {
            while (
                currentCoroutineContext().isActive &&
                session?.generation == generation
            ) {
                delay(POSITION_UPDATE_MS)
                var completed = false
                mutex.withLock {
                    val active = session
                    if (
                        active == null ||
                        active.generation != generation ||
                        mutableSnapshot.value.state != PlaybackState.PLAYING
                    ) {
                        return@withLock
                    }
                    val presented = updatePresentedPositionLocked(active)
                    if (
                        active.sourceExhausted.get() &&
                        presented >= active.totalSamples
                    ) {
                        active.sink.pause()
                        publish(
                            mutableSnapshot.value.copy(
                                state = PlaybackState.COMPLETED,
                                positionSampleIndex = active.totalSamples,
                                positionUs = mutableSnapshot.value.durationUs,
                            ),
                        )
                        completed = true
                    }
                }
                if (completed) return@launch
            }
        }
    }

    private fun updatePresentedPositionLocked(current: Session): Long {
        val raw = current.sink.position().framePosition
        val sample = current.tracker.absoluteSample(raw, current.totalSamples)
        val previous = mutableSnapshot.value.positionSampleIndex
        val monotonic = maxOf(previous, sample).coerceAtMost(current.totalSamples)
        publish(
            mutableSnapshot.value.copy(
                positionSampleIndex = monotonic,
                positionUs = sampleIndexToTimeUs(
                    monotonic,
                    CanonicalPcmProfile.SAMPLE_RATE_HZ,
                ),
            ),
        )
        return monotonic
    }

    private suspend fun unloadLocked(nextState: PlaybackState) {
        writerJob?.cancelAndJoin()
        writerJob = null
        positionJob?.cancel()
        positionJob = null
        session?.let { current ->
            runCatching { current.sink.pause() }
            runCatching { current.sink.close() }
            runCatching { current.source.handle.close() }
        }
        session = null
        playbackGeneration.incrementAndGet()
        publish(
            PlaybackSnapshot(
                state = nextState,
                discontinuityGeneration =
                    mutableSnapshot.value.discontinuityGeneration +
                        if (nextState == PlaybackState.IDLE) 1L else 0L,
            ),
        )
    }

    private fun validateDescriptor(
        descriptor: PlaybackAudioSourceDescriptor,
    ): String? {
        if (descriptor.container != AudioContainerKind.WAV) return "container is not WAV"
        if (!descriptor.seekable) return "source is not seekable"
        if (descriptor.sampleRateHz != CanonicalPcmProfile.SAMPLE_RATE_HZ) {
            return "sample rate is not canonical"
        }
        if (descriptor.channelCount != CanonicalPcmProfile.CHANNEL_COUNT) {
            return "channel count is not canonical"
        }
        if (descriptor.bitsPerSample != CanonicalPcmProfile.BITS_PER_SAMPLE) {
            return "bit depth is not canonical"
        }
        if (descriptor.bytesPerFrame != CanonicalPcmProfile.BYTES_PER_SAMPLE) {
            return "frame size is not canonical"
        }
        val offset = descriptor.pcmDataOffsetBytes ?: return "missing PCM data offset"
        val size = descriptor.pcmDataSizeBytes ?: return "missing PCM data size"
        val total = descriptor.totalSampleCount ?: return "missing sample count"
        if (offset < 0L || size < 0L || total < 0L) return "negative PCM metadata"
        if (size % CanonicalPcmProfile.BYTES_PER_SAMPLE != 0L) {
            return "PCM data is not frame aligned"
        }
        if (total != size / CanonicalPcmProfile.BYTES_PER_SAMPLE) {
            return "sample count does not match PCM size"
        }
        if (offset + size > descriptor.lengthBytes) {
            return "PCM data exceeds source length"
        }
        return null
    }

    private fun publishSourceError(
        recordingId: String,
        code: PlaybackErrorCode,
        error: Throwable?,
    ) {
        publish(
            PlaybackSnapshot(
                recordingId = recordingId,
                state = PlaybackState.ERROR,
                error = PlaybackError(
                    code = code,
                    category = PlaybackErrorCategory.SOURCE,
                    recoverable = true,
                    diagnosticDetail = error?.message,
                ),
            ),
        )
    }

    private fun publish(value: PlaybackSnapshot) {
        mutableSnapshot.value = value
        mutableState.value = value.state
        mutablePosition.value = value.positionSampleIndex
    }

    private fun reportFatalAsync(
        generation: Long,
        error: PlaybackError,
    ) {
        scope.launch {
            mutex.withLock {
                val current = session
                if (current == null || current.generation != generation) return@withLock
                runCatching { current.sink.pause() }
                positionJob?.cancel()
                positionJob = null
                publish(
                    mutableSnapshot.value.copy(
                        state = PlaybackState.ERROR,
                        error = error,
                    ),
                )
            }
        }
    }

    private fun ensureNotReleased() {
        check(mutableSnapshot.value.state != PlaybackState.RELEASED) {
            "PlaybackController is released"
        }
    }

    private data class Session(
        val generation: Long,
        val source: PlaybackAudioSource,
        val sink: PlaybackAudioSink,
        val totalSamples: Long,
        val tracker: PlaybackPositionTracker,
        val sourceSampleCursor: AtomicLong = AtomicLong(0L),
        val submittedSample: AtomicLong = AtomicLong(0L),
        val sourceExhausted: AtomicBoolean = AtomicBoolean(false),
    )

    private companion object {
        const val READ_BUFFER_BYTES = 32 * 1024
        const val POSITION_UPDATE_MS = 50L
        const val IDLE_WRITE_DELAY_MS = 5L
        const val SPEED_EPSILON = 0.0001f

        val SUPPORTED_SPEEDS =
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}
