package io.github.ioannes78.voica

import android.content.Context
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.audio.RealtimePcmWavSegmentWriter
import io.github.ioannes78.voica.database.PhoneMicrophoneRecordingRegistration
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class PhoneMicrophoneRecordingPhase {
    IDLE,
    STARTING,
    RECORDING,
    PAUSING,
    PAUSED,
    RESUMING,
    STOPPING,
    COMPLETED,
    EMPTY,
    FAILED,
    CLOSED,
}

/** Optional best-effort consumer that runs only after the formal WAV writer accepted the frame. */
internal fun interface PhoneMicrophoneCanonicalPostWriteSink {
    fun offer(frame: RealtimeCanonicalPcmFrame)
}

internal data class PhoneMicrophoneRecordingSnapshot(
    val phase: PhoneMicrophoneRecordingPhase = PhoneMicrophoneRecordingPhase.IDLE,
    val recordingId: String? = null,
    val displayName: String? = null,
    val relativePath: String? = null,
    val canonicalSessionId: Long? = null,
    val activeEpochId: Long? = null,
    val completedEpochs: Int = 0,
    val writtenSamples: Long = 0L,
    val postWriteOffers: Long = 0L,
    val postWriteOfferFailures: Long = 0L,
    val routeBoundaryCount: Long = 0L,
    val discontinuityEpochCount: Long = 0L,
    val lastRouteBoundaryReason: PhoneMicrophoneRouteBoundaryReason? = null,
    val captureRecoveryAttempts: Long = 0L,
    val captureRecoverySuccesses: Long = 0L,
    val captureRecoveryFailures: Long = 0L,
    val lastCaptureRecoveryTrigger: PhoneMicrophoneCaptureRecoveryTrigger? = null,
    val hadCaptureInterruption: Boolean = false,
    val finalizedSizeBytes: Long? = null,
    val finalizedSha256: String? = null,
    val lastPostWriteFailure: String? = null,
    val lastFailure: String? = null,
) {
    val durationMs: Long
        get() = writtenSamples * 1_000L / CanonicalPcmProfile.SAMPLE_RATE_HZ
}

internal sealed interface PhoneMicrophoneRecordingStartResult {
    data class Started(
        val recordingId: String,
        val displayName: String,
    ) : PhoneMicrophoneRecordingStartResult

    data class Rejected(val message: String) : PhoneMicrophoneRecordingStartResult
}

internal sealed interface PhoneMicrophoneRecordingActionResult {
    data object Success : PhoneMicrophoneRecordingActionResult
    data class Rejected(val message: String) : PhoneMicrophoneRecordingActionResult
}

internal sealed interface PhoneMicrophoneRecordingStopResult {
    data class Saved(
        val recordingId: String,
        val file: File,
        val relativePath: String,
        val sampleCount: Long,
        val sizeBytes: Long,
        val sha256: String,
    ) : PhoneMicrophoneRecordingStopResult

    data object Empty : PhoneMicrophoneRecordingStopResult

    data class Failed(
        val message: String,
        val preservedFile: File? = null,
    ) : PhoneMicrophoneRecordingStopResult
}

internal interface PhoneMicrophoneCaptureDriver : Closeable {
    val state: StateFlow<PhoneMicrophoneCaptureSnapshot>

    fun start(
        consumer: PhoneMicrophonePcmConsumer,
        foregroundLabel: String,
    ): PhoneMicrophoneStartResult

    suspend fun stopAndAwait(timeoutMs: Long): Boolean
}

internal class AndroidPhoneMicrophoneCaptureDriver(
    context: Context,
) : PhoneMicrophoneCaptureDriver {
    private val capture = PhoneMicrophoneCapture(context)

    override val state: StateFlow<PhoneMicrophoneCaptureSnapshot>
        get() = capture.state

    override fun start(
        consumer: PhoneMicrophonePcmConsumer,
        foregroundLabel: String,
    ): PhoneMicrophoneStartResult = capture.start(consumer, foregroundLabel)

    override suspend fun stopAndAwait(timeoutMs: Long): Boolean = capture.stopAndAwait(timeoutMs)

    override fun close() = capture.close()
}

/**
 * Stage 16A.1 formal phone recording owner.
 *
 * The synchronous writer is the primary consumer of microphone PCM. No bounded ASR queue sits in
 * front of the formal recording path. Pause ends the current capture epoch without finalizing the
 * WAV; resume opens a new CONTROL_BOUNDARY epoch, so paused wall-clock time is never synthesized as
 * silence. AUTO input reroutes open a DISCONTINUITY epoch before the first PCM frame from the new
 * route is committed. If AUTO capture itself becomes invalid, one bounded sequential recreation is
 * allowed after the old capture fully stops; the same formal WAV continues with a DISCONTINUITY
 * epoch. Optional realtime fan-out is invoked only after the formal WAV accepted the same frame.
 */
internal class PhoneMicrophoneRecordingRuntime(
    private val capture: PhoneMicrophoneCaptureDriver,
    private val recordingsRoot: File,
    private val registerRecording: suspend (PhoneMicrophoneRecordingRegistration) -> String,
    private val nowInstant: () -> Instant = Instant::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idFactory: () -> UUID = UUID::randomUUID,
    private val recoveryScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {
    private val lock = Any()
    private val mutableState = MutableStateFlow(PhoneMicrophoneRecordingSnapshot())
    val state: StateFlow<PhoneMicrophoneRecordingSnapshot> = mutableState.asStateFlow()
    val captureState: StateFlow<PhoneMicrophoneCaptureSnapshot>
        get() = capture.state

    private var active: ActiveRecording? = null
    private var closed = false
    private val routeBoundarySubscription =
        PhoneMicrophoneRouteBoundaryHub.subscribe(::onRouteBoundary)
    private val captureFailureObserver =
        recoveryScope.launch {
            capture.state.collect { snapshot ->
                PhoneMicrophoneCaptureAutoRecoveryPolicy.triggerFor(snapshot)?.let(
                    ::scheduleAutoCaptureRecovery,
                )
            }
        }

    suspend fun start(
        postWriteSink: PhoneMicrophoneCanonicalPostWriteSink? = null,
    ): PhoneMicrophoneRecordingStartResult = withContext(ioDispatcher) {
        val prepared = synchronized(lock) {
            if (closed) {
                return@withContext PhoneMicrophoneRecordingStartResult.Rejected(
                    "手机录音已关闭，请重新进入录音页面",
                )
            }
            if (active != null) {
                return@withContext PhoneMicrophoneRecordingStartResult.Rejected("已有手机录音正在进行")
            }
            when (mutableState.value.phase) {
                PhoneMicrophoneRecordingPhase.STARTING,
                PhoneMicrophoneRecordingPhase.RECORDING,
                PhoneMicrophoneRecordingPhase.PAUSING,
                PhoneMicrophoneRecordingPhase.PAUSED,
                PhoneMicrophoneRecordingPhase.RESUMING,
                PhoneMicrophoneRecordingPhase.STOPPING,
                -> return@withContext PhoneMicrophoneRecordingStartResult.Rejected("手机录音正在切换状态")
                else -> Unit
            }

            val startedAt = nowInstant()
            val localTime = startedAt.atZone(zoneId())
            val recordingId = idFactory().toString()
            val filename = "phone_${FILE_TIME_FORMAT.format(localTime)}.wav"
            val displayName = "手机录音 ${DISPLAY_TIME_FORMAT.format(localTime)}"
            val relativePath = "phone/$recordingId/$filename"
            val targetFile = File(recordingsRoot, relativePath)
            val sessionId = positiveIdentity(idFactory())
            val writer = RealtimePcmWavSegmentWriter(targetFile, sessionId)
            ActiveRecording(
                recordingId = recordingId,
                originalFilename = filename,
                displayName = displayName,
                relativePath = relativePath,
                targetFile = targetFile,
                startedAt = startedAt,
                canonicalSessionId = sessionId,
                writer = writer,
                postWriteSink = postWriteSink,
            ).also { context ->
                active = context
                publishLocked(context, PhoneMicrophoneRecordingPhase.STARTING)
            }
        }

        when (
            val result = capture.start(
                consumer = consumerFor(prepared),
                foregroundLabel = "正在录制：${prepared.displayName}",
            )
        ) {
            is PhoneMicrophoneStartResult.Started -> {
                synchronized(lock) {
                    if (active === prepared) {
                        publishLocked(prepared, PhoneMicrophoneRecordingPhase.RECORDING)
                    }
                }
                PhoneMicrophoneRecordingStartResult.Started(
                    recordingId = prepared.recordingId,
                    displayName = prepared.displayName,
                )
            }

            is PhoneMicrophoneStartResult.Rejected -> {
                synchronized(lock) {
                    if (active === prepared) active = null
                }
                prepared.writer.close()
                prepared.targetFile.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
                synchronized(lock) {
                    mutableState.value =
                        mutableState.value.copy(
                            phase = PhoneMicrophoneRecordingPhase.FAILED,
                            lastFailure = result.message,
                        )
                }
                PhoneMicrophoneRecordingStartResult.Rejected(result.message)
            }
        }
    }

    suspend fun pause(): PhoneMicrophoneRecordingActionResult {
        val context = synchronized(lock) {
            val current = active
                ?: return PhoneMicrophoneRecordingActionResult.Rejected("当前没有手机录音")
            if (mutableState.value.phase != PhoneMicrophoneRecordingPhase.RECORDING) {
                return PhoneMicrophoneRecordingActionResult.Rejected("当前状态不能暂停")
            }
            current.pendingRouteBoundary = null
            current.recoveryInFlight = false
            markCaptureInterruptionLocked(current, capture.state.value)
            publishLocked(
                current,
                PhoneMicrophoneRecordingPhase.PAUSING,
                failure = current.lastCaptureInterruption,
            )
            current
        }

        if (!capture.stopAndAwait(CAPTURE_STOP_TIMEOUT_MS)) {
            return failAction(context, "暂停手机录音超时，录音文件尚未结束")
        }

        synchronized(lock) {
            if (active === context) {
                if (context.activeEpochSamples > 0L) {
                    context.completedEpochs += 1
                    context.activeEpochSamples = 0L
                }
                publishLocked(
                    context,
                    PhoneMicrophoneRecordingPhase.PAUSED,
                    failure = context.lastCaptureInterruption,
                )
            }
        }
        return PhoneMicrophoneRecordingActionResult.Success
    }

    suspend fun resume(): PhoneMicrophoneRecordingActionResult = withContext(ioDispatcher) {
        val context = synchronized(lock) {
            val current = active
                ?: return@withContext PhoneMicrophoneRecordingActionResult.Rejected("当前没有手机录音")
            if (mutableState.value.phase != PhoneMicrophoneRecordingPhase.PAUSED) {
                return@withContext PhoneMicrophoneRecordingActionResult.Rejected("当前状态不能继续录音")
            }
            current.epochId = Math.addExact(current.epochId, 1L)
            current.epochStartReason = RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY
            current.activeEpochSamples = 0L
            current.captureEpochBaseSampleIndex = 0L
            current.pendingRouteBoundary = null
            current.recoveryAttempted = false
            current.recoveryInFlight = false
            publishLocked(
                current,
                PhoneMicrophoneRecordingPhase.RESUMING,
                failure = current.lastCaptureInterruption,
            )
            current
        }

        when (
            val result = capture.start(
                consumer = consumerFor(context),
                foregroundLabel = "正在录制：${context.displayName}",
            )
        ) {
            is PhoneMicrophoneStartResult.Started -> {
                synchronized(lock) {
                    if (active === context) {
                        publishLocked(
                            context,
                            PhoneMicrophoneRecordingPhase.RECORDING,
                            failure = context.lastCaptureInterruption,
                        )
                    }
                }
                PhoneMicrophoneRecordingActionResult.Success
            }

            is PhoneMicrophoneStartResult.Rejected -> {
                synchronized(lock) {
                    if (active === context) {
                        publishLocked(
                            context,
                            PhoneMicrophoneRecordingPhase.PAUSED,
                            failure = result.message,
                        )
                    }
                }
                PhoneMicrophoneRecordingActionResult.Rejected(result.message)
            }
        }
    }

    suspend fun stop(): PhoneMicrophoneRecordingStopResult = withContext(ioDispatcher) {
        val preparation = synchronized(lock) {
            val current = active
                ?: return@withContext PhoneMicrophoneRecordingStopResult.Failed("当前没有手机录音")
            val phase = mutableState.value.phase
            if (
                phase != PhoneMicrophoneRecordingPhase.RECORDING &&
                phase != PhoneMicrophoneRecordingPhase.PAUSED
            ) {
                return@withContext PhoneMicrophoneRecordingStopResult.Failed("当前状态不能停止录音")
            }
            if (phase == PhoneMicrophoneRecordingPhase.RECORDING) {
                markCaptureInterruptionLocked(current, capture.state.value)
            }
            current.pendingRouteBoundary = null
            current.recoveryInFlight = false
            val interrupted = current.hadCaptureInterruption
            publishLocked(current, PhoneMicrophoneRecordingPhase.STOPPING)
            StopPreparation(
                context = current,
                interrupted = interrupted,
                interruptionMessage = current.lastCaptureInterruption,
            )
        }
        val context = preparation.context

        val wasRecording = capture.state.value.phase == PhoneMicrophoneCapturePhase.RECORDING ||
            capture.state.value.phase == PhoneMicrophoneCapturePhase.STOPPING
        if (wasRecording && !capture.stopAndAwait(CAPTURE_STOP_TIMEOUT_MS)) {
            return@withContext failStop(context, "停止手机录音超时，已保留临时录音数据")
        }

        val sampleCount = synchronized(lock) { context.writtenSamples }
        if (sampleCount == 0L) {
            context.writer.close()
            synchronized(lock) {
                if (active === context) active = null
                publishTerminalLocked(context, PhoneMicrophoneRecordingPhase.EMPTY)
            }
            return@withContext PhoneMicrophoneRecordingStopResult.Empty
        }

        val finalized =
            try {
                context.writer.finalizeSegment()
            } catch (failure: Throwable) {
                return@withContext failStop(
                    context,
                    "无法完成手机录音 WAV：${failure.message.orEmpty()}",
                    context.targetFile.takeIf(File::exists),
                )
            }

        val completedAt = nowInstant()
        val savedDisplayName =
            if (preparation.interrupted) interruptedDisplayName(context.displayName) else context.displayName
        val registration =
            PhoneMicrophoneRecordingRegistration(
                recordingId = context.recordingId,
                originalFilename = context.originalFilename,
                displayName = savedDisplayName,
                recordedAtLocalIso = context.startedAt.atZone(zoneId()).toOffsetDateTime().toString(),
                startedAtMs = context.startedAt.toEpochMilli(),
                completedAtMs = completedAt.toEpochMilli(),
                relativePath = context.relativePath,
                mediaDurationMs =
                    finalized.sampleCount * 1_000L / CanonicalPcmProfile.SAMPLE_RATE_HZ,
                sizeBytes = finalized.sizeBytes,
                sha256 = finalized.sha256,
                sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
            )

        try {
            registerRecording(registration)
        } catch (failure: Throwable) {
            return@withContext failStop(
                context,
                "录音文件已保存，但写入录音库失败：${failure.message.orEmpty()}",
                finalized.file,
            )
        }

        val interruptedMessage =
            if (preparation.interrupted) buildInterruptedMessage(preparation.interruptionMessage) else null
        synchronized(lock) {
            val totalCompletedEpochs =
                context.completedEpochs + if (context.activeEpochSamples > 0L) 1 else 0
            if (active === context) active = null
            mutableState.value =
                PhoneMicrophoneRecordingSnapshot(
                    phase =
                        if (preparation.interrupted) {
                            PhoneMicrophoneRecordingPhase.FAILED
                        } else {
                            PhoneMicrophoneRecordingPhase.COMPLETED
                        },
                    recordingId = context.recordingId,
                    displayName = savedDisplayName,
                    relativePath = context.relativePath,
                    canonicalSessionId = context.canonicalSessionId,
                    activeEpochId = context.epochId,
                    completedEpochs = totalCompletedEpochs,
                    writtenSamples = finalized.sampleCount,
                    postWriteOffers = context.postWriteOffers,
                    postWriteOfferFailures = context.postWriteOfferFailures,
                    routeBoundaryCount = context.routeBoundaryCount,
                    discontinuityEpochCount = context.discontinuityEpochCount,
                    lastRouteBoundaryReason = context.lastRouteBoundaryReason,
                    captureRecoveryAttempts = context.captureRecoveryAttempts,
                    captureRecoverySuccesses = context.captureRecoverySuccesses,
                    captureRecoveryFailures = context.captureRecoveryFailures,
                    lastCaptureRecoveryTrigger = context.lastCaptureRecoveryTrigger,
                    hadCaptureInterruption = context.hadCaptureInterruption,
                    finalizedSizeBytes = finalized.sizeBytes,
                    finalizedSha256 = finalized.sha256,
                    lastPostWriteFailure = context.lastPostWriteFailure,
                    lastFailure = interruptedMessage,
                )
        }

        if (preparation.interrupted) {
            return@withContext PhoneMicrophoneRecordingStopResult.Failed(
                message = checkNotNull(interruptedMessage),
                preservedFile = finalized.file,
            )
        }

        PhoneMicrophoneRecordingStopResult.Saved(
            recordingId = context.recordingId,
            file = finalized.file,
            relativePath = context.relativePath,
            sampleCount = finalized.sampleCount,
            sizeBytes = finalized.sizeBytes,
            sha256 = finalized.sha256,
        )
    }

    override fun close() {
        val context = synchronized(lock) {
            if (closed) return
            closed = true
            val current = active
            active = null
            mutableState.value =
                mutableState.value.copy(phase = PhoneMicrophoneRecordingPhase.CLOSED)
            current
        }
        routeBoundarySubscription.close()
        captureFailureObserver.cancel()
        recoveryScope.cancel()
        capture.close()
        context?.writer?.close()
    }

    private fun onRouteBoundary(boundary: PhoneMicrophoneRouteBoundary) {
        var recoverRouteLoss = false
        synchronized(lock) {
            val context = active ?: return
            if (mutableState.value.phase != PhoneMicrophoneRecordingPhase.RECORDING) return
            val requestedInput = capture.state.value.requestedInput
            if (requestedInput !is PhoneMicrophoneInputRequest.Auto) return
            context.pendingRouteBoundary = boundary
            context.routeBoundaryCount = Math.addExact(context.routeBoundaryCount, 1L)
            context.lastRouteBoundaryReason = boundary.reason
            recoverRouteLoss =
                boundary.reason == PhoneMicrophoneRouteBoundaryReason.ROUTE_LOST &&
                    PhoneMicrophoneCaptureAutoRecoveryPolicy.allowsRouteLossRecovery(requestedInput)
            publishLocked(
                context,
                PhoneMicrophoneRecordingPhase.RECORDING,
                failure = context.lastCaptureInterruption,
            )
        }
        if (recoverRouteLoss) {
            scheduleAutoCaptureRecovery(PhoneMicrophoneCaptureRecoveryTrigger.ROUTE_LOST)
        }
    }

    private fun scheduleAutoCaptureRecovery(trigger: PhoneMicrophoneCaptureRecoveryTrigger) {
        val context = synchronized(lock) {
            if (closed || mutableState.value.phase != PhoneMicrophoneRecordingPhase.RECORDING) return
            val current = active ?: return
            if (capture.state.value.requestedInput !is PhoneMicrophoneInputRequest.Auto) return
            if (current.recoveryAttempted || current.recoveryInFlight) return
            current.recoveryAttempted = true
            current.recoveryInFlight = true
            current.captureRecoveryAttempts = Math.addExact(current.captureRecoveryAttempts, 1L)
            current.lastCaptureRecoveryTrigger = trigger
            publishLocked(
                current,
                PhoneMicrophoneRecordingPhase.RECORDING,
                failure = current.lastCaptureInterruption,
            )
            current
        }
        recoveryScope.launch { performAutoCaptureRecovery(context, trigger) }
    }

    private suspend fun performAutoCaptureRecovery(
        context: ActiveRecording,
        trigger: PhoneMicrophoneCaptureRecoveryTrigger,
    ) {
        val oldSnapshot = capture.state.value
        val stopped = runCatching { capture.stopAndAwait(CAPTURE_STOP_TIMEOUT_MS) }.getOrDefault(false)
        if (!stopped) {
            recordCaptureRecoveryFailure(context, trigger, "自动恢复前无法完整停止旧麦克风采集")
            return
        }

        val stillEligible = synchronized(lock) {
            !closed &&
                active === context &&
                mutableState.value.phase == PhoneMicrophoneRecordingPhase.RECORDING &&
                context.recoveryInFlight
        }
        if (!stillEligible) return

        // The old capture is fully joined/released here. Emit the canonical boundary before a new
        // AudioRecord can start, so its first successful PCM frame cannot outrun DISCONTINUITY.
        PhoneMicrophoneRouteBoundaryHub.emit(
            PhoneMicrophoneRouteBoundary(
                reason = PhoneMicrophoneRouteBoundaryReason.CAPTURE_RECREATED,
                oldRoute = oldSnapshot.route,
                newRoute = null,
                routeGeneration = Math.addExact(oldSnapshot.routeGeneration, 1L),
            ),
        )

        val result =
            capture.start(
                consumer = consumerFor(context),
                foregroundLabel = "正在录制：${context.displayName}",
            )
        synchronized(lock) {
            if (active !== context) return
            context.recoveryInFlight = false
            when (result) {
                is PhoneMicrophoneStartResult.Started -> {
                    context.captureRecoverySuccesses =
                        Math.addExact(context.captureRecoverySuccesses, 1L)
                    publishLocked(
                        context,
                        PhoneMicrophoneRecordingPhase.RECORDING,
                        failure = context.lastCaptureInterruption,
                    )
                }
                is PhoneMicrophoneStartResult.Rejected -> {
                    context.captureRecoveryFailures =
                        Math.addExact(context.captureRecoveryFailures, 1L)
                    context.hadCaptureInterruption = true
                    context.lastCaptureInterruption =
                        "自动恢复手机麦克风失败（${trigger.name}）：${result.message}"
                    publishLocked(
                        context,
                        PhoneMicrophoneRecordingPhase.RECORDING,
                        failure = context.lastCaptureInterruption,
                    )
                }
            }
        }
    }

    private fun recordCaptureRecoveryFailure(
        context: ActiveRecording,
        trigger: PhoneMicrophoneCaptureRecoveryTrigger,
        message: String,
    ) {
        synchronized(lock) {
            if (active !== context) return
            context.recoveryInFlight = false
            context.captureRecoveryFailures = Math.addExact(context.captureRecoveryFailures, 1L)
            context.hadCaptureInterruption = true
            context.lastCaptureInterruption = "$message（${trigger.name}）"
            publishLocked(
                context,
                PhoneMicrophoneRecordingPhase.RECORDING,
                failure = context.lastCaptureInterruption,
            )
        }
    }

    private fun consumerFor(context: ActiveRecording): PhoneMicrophonePcmConsumer =
        PhoneMicrophonePcmConsumer { frame ->
            val epoch = synchronized(lock) {
                check(active === context) { "stale phone microphone capture frame" }
                val boundary = context.pendingRouteBoundary
                if (boundary != null) {
                    check(boundary.reason != PhoneMicrophoneRouteBoundaryReason.ROUTE_LOST) {
                        "phone microphone route lost before PCM delivery"
                    }
                    if (context.activeEpochSamples > 0L) {
                        context.completedEpochs += 1
                    }
                    context.epochId = Math.addExact(context.epochId, 1L)
                    context.epochStartReason = RealtimeCanonicalEpochStartReason.DISCONTINUITY
                    context.activeEpochSamples = 0L
                    context.captureEpochBaseSampleIndex = frame.startSampleIndex
                    context.pendingRouteBoundary = null
                    context.discontinuityEpochCount =
                        Math.addExact(context.discontinuityEpochCount, 1L)
                }
                val epochLocalStart = frame.startSampleIndex - context.captureEpochBaseSampleIndex
                check(epochLocalStart >= 0L) { "phone microphone epoch sample index moved backwards" }
                EpochDescriptor(
                    id = context.epochId,
                    reason = context.epochStartReason,
                    startSampleIndex = epochLocalStart,
                )
            }
            val canonical =
                RealtimeCanonicalPcmFrame(
                    sessionId = context.canonicalSessionId,
                    epochId = epoch.id,
                    startSampleIndex = epoch.startSampleIndex,
                    samples = frame.samples,
                    epochStartReason = if (epoch.startSampleIndex == 0L) epoch.reason else null,
                )

            // Formal recording truth is committed synchronously before any realtime fan-out.
            context.writer.accept(canonical)
            synchronized(lock) {
                check(active === context) { "phone recording ended while accepting PCM" }
                context.writtenSamples = Math.addExact(context.writtenSamples, frame.samples.size.toLong())
                context.activeEpochSamples =
                    Math.addExact(context.activeEpochSamples, frame.samples.size.toLong())
                publishLocked(
                    context,
                    mutableState.value.phase,
                    failure = context.lastCaptureInterruption,
                )
            }

            val postWriteSink = context.postWriteSink
            if (postWriteSink != null) {
                val failure = runCatching { postWriteSink.offer(canonical) }.exceptionOrNull()
                synchronized(lock) {
                    if (active === context) {
                        context.postWriteOffers += 1
                        if (failure != null) {
                            context.postWriteOfferFailures += 1
                            context.lastPostWriteFailure =
                                "${failure.javaClass.simpleName}:${failure.message.orEmpty()}"
                        }
                        publishLocked(
                            context,
                            mutableState.value.phase,
                            failure = context.lastCaptureInterruption,
                        )
                    }
                }
            }
        }

    private fun markCaptureInterruptionLocked(
        context: ActiveRecording,
        captureSnapshot: PhoneMicrophoneCaptureSnapshot,
    ) {
        if (captureSnapshot.phase !in INTERRUPTED_CAPTURE_PHASES) return
        context.hadCaptureInterruption = true
        val detail = captureSnapshot.lastFailure?.trim()?.takeIf { it.isNotEmpty() }
        if (detail != null) {
            context.lastCaptureInterruption = detail
        } else if (context.lastCaptureInterruption == null) {
            context.lastCaptureInterruption = "capture=${captureSnapshot.phase.name}"
        }
    }

    private fun failAction(
        context: ActiveRecording,
        message: String,
    ): PhoneMicrophoneRecordingActionResult.Rejected {
        synchronized(lock) {
            if (active === context) {
                publishLocked(context, PhoneMicrophoneRecordingPhase.FAILED, failure = message)
            }
        }
        return PhoneMicrophoneRecordingActionResult.Rejected(message)
    }

    private fun failStop(
        context: ActiveRecording,
        message: String,
        preservedFile: File? = null,
    ): PhoneMicrophoneRecordingStopResult.Failed {
        synchronized(lock) {
            if (active === context) {
                publishLocked(context, PhoneMicrophoneRecordingPhase.FAILED, failure = message)
            }
        }
        return PhoneMicrophoneRecordingStopResult.Failed(message, preservedFile)
    }

    private fun publishLocked(
        context: ActiveRecording,
        phase: PhoneMicrophoneRecordingPhase,
        failure: String? = null,
    ) {
        mutableState.value =
            PhoneMicrophoneRecordingSnapshot(
                phase = phase,
                recordingId = context.recordingId,
                displayName = context.displayName,
                relativePath = context.relativePath,
                canonicalSessionId = context.canonicalSessionId,
                activeEpochId = context.epochId,
                completedEpochs = context.completedEpochs,
                writtenSamples = context.writtenSamples,
                postWriteOffers = context.postWriteOffers,
                postWriteOfferFailures = context.postWriteOfferFailures,
                routeBoundaryCount = context.routeBoundaryCount,
                discontinuityEpochCount = context.discontinuityEpochCount,
                lastRouteBoundaryReason = context.lastRouteBoundaryReason,
                captureRecoveryAttempts = context.captureRecoveryAttempts,
                captureRecoverySuccesses = context.captureRecoverySuccesses,
                captureRecoveryFailures = context.captureRecoveryFailures,
                lastCaptureRecoveryTrigger = context.lastCaptureRecoveryTrigger,
                hadCaptureInterruption = context.hadCaptureInterruption,
                lastPostWriteFailure = context.lastPostWriteFailure,
                lastFailure = failure,
            )
    }

    private fun publishTerminalLocked(
        context: ActiveRecording,
        phase: PhoneMicrophoneRecordingPhase,
    ) {
        mutableState.value =
            PhoneMicrophoneRecordingSnapshot(
                phase = phase,
                recordingId = context.recordingId,
                displayName = context.displayName,
                relativePath = context.relativePath,
                canonicalSessionId = context.canonicalSessionId,
                activeEpochId = context.epochId,
                completedEpochs = context.completedEpochs,
                writtenSamples = context.writtenSamples,
                postWriteOffers = context.postWriteOffers,
                postWriteOfferFailures = context.postWriteOfferFailures,
                routeBoundaryCount = context.routeBoundaryCount,
                discontinuityEpochCount = context.discontinuityEpochCount,
                lastRouteBoundaryReason = context.lastRouteBoundaryReason,
                captureRecoveryAttempts = context.captureRecoveryAttempts,
                captureRecoverySuccesses = context.captureRecoverySuccesses,
                captureRecoveryFailures = context.captureRecoveryFailures,
                lastCaptureRecoveryTrigger = context.lastCaptureRecoveryTrigger,
                hadCaptureInterruption = context.hadCaptureInterruption,
                lastPostWriteFailure = context.lastPostWriteFailure,
                lastFailure = context.lastCaptureInterruption,
            )
    }

    private data class StopPreparation(
        val context: ActiveRecording,
        val interrupted: Boolean,
        val interruptionMessage: String?,
    )

    private data class EpochDescriptor(
        val id: Long,
        val reason: RealtimeCanonicalEpochStartReason,
        val startSampleIndex: Long,
    )

    private data class ActiveRecording(
        val recordingId: String,
        val originalFilename: String,
        val displayName: String,
        val relativePath: String,
        val targetFile: File,
        val startedAt: Instant,
        val canonicalSessionId: Long,
        val writer: RealtimePcmWavSegmentWriter,
        val postWriteSink: PhoneMicrophoneCanonicalPostWriteSink?,
        var epochId: Long = 1L,
        var epochStartReason: RealtimeCanonicalEpochStartReason =
            RealtimeCanonicalEpochStartReason.SESSION_START,
        var completedEpochs: Int = 0,
        var writtenSamples: Long = 0L,
        var activeEpochSamples: Long = 0L,
        var captureEpochBaseSampleIndex: Long = 0L,
        var pendingRouteBoundary: PhoneMicrophoneRouteBoundary? = null,
        var routeBoundaryCount: Long = 0L,
        var discontinuityEpochCount: Long = 0L,
        var lastRouteBoundaryReason: PhoneMicrophoneRouteBoundaryReason? = null,
        var recoveryAttempted: Boolean = false,
        var recoveryInFlight: Boolean = false,
        var captureRecoveryAttempts: Long = 0L,
        var captureRecoverySuccesses: Long = 0L,
        var captureRecoveryFailures: Long = 0L,
        var lastCaptureRecoveryTrigger: PhoneMicrophoneCaptureRecoveryTrigger? = null,
        var postWriteOffers: Long = 0L,
        var postWriteOfferFailures: Long = 0L,
        var lastPostWriteFailure: String? = null,
        var hadCaptureInterruption: Boolean = false,
        var lastCaptureInterruption: String? = null,
    )

    private companion object {
        const val CAPTURE_STOP_TIMEOUT_MS = 3_000L
        val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val DISPLAY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val INTERRUPTED_CAPTURE_PHASES =
            setOf(
                PhoneMicrophoneCapturePhase.FAILED,
                PhoneMicrophoneCapturePhase.STOPPED,
                PhoneMicrophoneCapturePhase.CLOSED,
            )

        fun interruptedDisplayName(displayName: String): String =
            if (displayName.startsWith("手机录音 ")) {
                displayName.replaceFirst("手机录音 ", "手机录音（异常中断） ")
            } else {
                "$displayName（异常中断）"
            }

        fun buildInterruptedMessage(detail: String?): String =
            if (detail.isNullOrBlank()) {
                "麦克风采集异常中断，已保存中断前的可用音频"
            } else {
                "麦克风采集异常中断，已保存中断前的可用音频：$detail"
            }

        fun positiveIdentity(uuid: UUID): Long {
            val value = uuid.mostSignificantBits and Long.MAX_VALUE
            return if (value == 0L) 1L else value
        }
    }
}
