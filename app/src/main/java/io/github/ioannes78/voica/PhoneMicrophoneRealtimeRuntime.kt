package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.RealtimeCanonicalSessionEndReason
import io.github.ioannes78.voica.audio.RealtimePcmWavSegmentFinalization
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.RealtimeAudioSourceTypeValue
import io.github.ioannes78.voica.database.RealtimeRecordingIntegrationRepository
import io.github.ioannes78.voica.database.RealtimeTranscriptPersistenceRepository
import io.github.ioannes78.voica.database.RealtimeTranscriptionRunEntity
import io.github.ioannes78.voica.database.RealtimeTranscriptionStateValue
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.transcript.RealtimeDurableOutputFailure
import io.github.ioannes78.voica.transcript.RealtimeLocalAudioResult
import io.github.ioannes78.voica.transcript.RealtimePauseFinalizationStatus
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionSessionRunnerState
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class PhoneMicrophoneRealtimeActionOutcome(
    val success: Boolean,
    val code: String? = null,
    val message: String? = null,
    val runId: String? = null,
    val recordingId: String? = null,
    val degraded: Boolean = false,
)

internal data class PhoneMicrophoneRealtimeRuntimeSnapshot(
    val activeRunId: String?,
    val runnerState: RealtimeTranscriptionSessionRunnerState?,
    val runnerFailure: String?,
    val bridge: PhoneMicrophoneRealtimeBridgeSnapshot?,
    val recording: PhoneMicrophoneRecordingSnapshot,
)

/**
 * Stage 16A.1 product owner for `手机实时转写`.
 *
 * This runtime deliberately does not reuse the recorder-card BLE lifecycle owner. It reuses only the
 * source-neutral Stage 16A model/pipeline/persistence contracts. Formal phone WAV recording remains
 * the primary truth; realtime ASR is a bounded post-write consumer that may degrade or fail without
 * stopping microphone recording.
 */
internal class PhoneMicrophoneRealtimeRuntime(
    private val scope: CoroutineScope,
    private val recordingRuntime: PhoneMicrophoneRecordingRuntime,
    private val modelResolver: Stage16ARealtimeModelResolver,
    private val pipelineFactory: Stage16ARealtimePipelineBundleFactory,
    private val persistenceRepository: RealtimeTranscriptPersistenceRepository,
    private val recordingLibraryRepository: RecordingLibraryRepository,
    private val integrationRepository: RealtimeRecordingIntegrationRepository,
    private val recordingsRoot: File,
    private val localSpeechSettings: () -> LocalSpeechSettings,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val commandMutex = Mutex()
    private val liveStateStore = Stage16ARealtimeLiveStateStore()
    val state = liveStateStore.state
    val recordingState = recordingRuntime.state

    @Volatile
    private var activeRun: ActiveRun? = null

    suspend fun start(): PhoneMicrophoneRealtimeActionOutcome = commandMutex.withLock {
        if (activeRun != null) {
            return@withLock failure("ALREADY_ACTIVE", "手机实时转写已经在运行")
        }

        val settings = localSpeechSettings()
        val modelLeases =
            try {
                // Product contract: model availability is checked before microphone capture starts.
                modelResolver.acquire(settings)
            } catch (failure: Throwable) {
                liveStateStore.failed("MODEL_RESOLUTION_FAILED", failure.message.orEmpty())
                return@withLock failure(
                    code = "MODEL_RESOLUTION_FAILED",
                    message = failure.message ?: "实时转写模型不可用",
                )
            }

        val runId = runIdFactory()
        val models = modelLeases.models
        liveStateStore.preparing(
            runId = runId,
            modelId = models.asr.descriptor.modelId,
            modelLabel = models.asr.descriptor.displayName,
        )

        val bridge = PhoneMicrophoneRealtimeCanonicalBridge()
        val persistence =
            RealtimeTranscriptPersistenceCoordinator(
                repository = persistenceRepository,
                runId = runId,
                transportGenerationProvider = { null },
                localPathResolver = ::relativeRecordingPath,
                clockMs = clockMs,
            )

        val bundle =
            try {
                pipelineFactory.create(
                    source = bridge,
                    models = modelLeases,
                    localAudioRoot = File(recordingsRoot, "realtime"),
                    persistDurableOutput = persistence::accept,
                    onDurableFailure = { failure -> onDurableFailure(runId, failure) },
                    liveOutputSinks = listOf(liveStateStore),
                    localAudioPersistenceMode = Stage16ALocalAudioPersistenceMode.DISABLED,
                )
            } catch (failure: Throwable) {
                bridge.close()
                liveStateStore.failed("PIPELINE_CREATE_FAILED", "无法启动手机实时转写")
                return@withLock failure(
                    code = "PIPELINE_CREATE_FAILED",
                    message = failure.message ?: "无法启动手机实时转写",
                )
            }

        try {
            persistenceRepository.createRun(
                createRunEntity(
                    runId = runId,
                    settings = settings,
                    models = models,
                ),
            )
        } catch (failure: Throwable) {
            bridge.close()
            runCatching { bundle.release() }
            liveStateStore.failed("RUN_CREATE_FAILED", "无法创建实时转写记录")
            return@withLock failure(
                code = "RUN_CREATE_FAILED",
                message = failure.message ?: "无法创建实时转写记录",
            )
        }

        val run =
            ActiveRun(
                runId = runId,
                persistence = persistence,
                bundle = bundle,
                bridge = bridge,
            )
        activeRun = run
        liveStateStore.starting()
        run.runnerJob =
            scope.launch {
                try {
                    bundle.runner.run()
                    if (!run.stopRequested && activeRun === run) {
                        run.runnerFailure = "REALTIME_PIPELINE_ENDED:实时转写链路提前结束"
                        liveStateStore.failed(
                            "REALTIME_PIPELINE_ENDED",
                            "实时转写已停止，手机录音仍在继续",
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    run.runnerFailure =
                        "${failure.javaClass.simpleName}:${failure.message.orEmpty()}"
                    if (!run.stopRequested && activeRun === run) {
                        liveStateStore.failed(
                            "REALTIME_PIPELINE_FAILED",
                            "实时转写出现异常，手机录音仍在继续",
                        )
                    }
                }
            }

        when (val recordingStart = recordingRuntime.start(postWriteSink = bridge)) {
            is PhoneMicrophoneRecordingStartResult.Started -> {
                run.recordingId = recordingStart.recordingId
                PhoneMicrophoneRealtimeActionOutcome(
                    success = true,
                    runId = runId,
                    recordingId = recordingStart.recordingId,
                )
            }

            is PhoneMicrophoneRecordingStartResult.Rejected -> {
                rollbackFailedStart(run, recordingStart.message)
                failure(
                    code = "PHONE_RECORDING_START_FAILED",
                    message = recordingStart.message,
                    runId = runId,
                )
            }
        }
    }

    suspend fun pause(): PhoneMicrophoneRealtimeActionOutcome = commandMutex.withLock {
        val run = activeRun ?: return@withLock failure("NOT_ACTIVE", "当前没有手机实时转写")
        when (val recordingPause = recordingRuntime.pause()) {
            is PhoneMicrophoneRecordingActionResult.Rejected ->
                return@withLock failure("PHONE_RECORDING_PAUSE_FAILED", recordingPause.message, run.runId)
            PhoneMicrophoneRecordingActionResult.Success -> Unit
        }

        val bridgeSnapshot = run.bridge.snapshot()
        val sessionId = bridgeSnapshot.lastOfferedSessionId
        val epochId = bridgeSnapshot.lastOfferedEpochId
        val endSampleIndexExclusive = bridgeSnapshot.lastOfferedEndSampleIndexExclusive
        var degradedCode: String? = null
        var degradedMessage: String? = null

        if (sessionId != null && epochId != null && endSampleIndexExclusive != null) {
            val pause =
                run.bundle.runner.finalizePausedContinuity(
                    sessionId = sessionId,
                    epochId = epochId,
                    endSampleIndexExclusive = endSampleIndexExclusive,
                    maxWaitMs = PAUSE_DRAIN_TIMEOUT_MS,
                )
            if (pause.status == RealtimePauseFinalizationStatus.COMMITTED) {
                runCatching {
                    run.persistence.onPauseCommitted(
                        canonicalSessionId = sessionId,
                        canonicalEpochId = epochId,
                        sampleIndex = endSampleIndexExclusive,
                    )
                }.onFailure { failure ->
                    degradedCode = "PAUSE_PERSIST_FAILED"
                    degradedMessage = failure.message ?: "暂停边界保存失败"
                }
            } else {
                degradedCode = "PAUSE_${pause.status.name}"
                degradedMessage = "录音已暂停，但实时转写未能在精确音频边界完成收口"
            }
        }

        liveStateStore.paused()
        if (degradedCode != null) {
            liveStateStore.degraded(checkNotNull(degradedCode), checkNotNull(degradedMessage))
        }
        PhoneMicrophoneRealtimeActionOutcome(
            success = true,
            runId = run.runId,
            recordingId = run.recordingId,
            degraded = degradedCode != null,
            code = degradedCode,
            message = degradedMessage,
        )
    }

    suspend fun resume(): PhoneMicrophoneRealtimeActionOutcome = commandMutex.withLock {
        val run = activeRun ?: return@withLock failure("NOT_ACTIVE", "当前没有手机实时转写")
        liveStateStore.resuming()
        return@withLock when (val recordingResume = recordingRuntime.resume()) {
            PhoneMicrophoneRecordingActionResult.Success ->
                PhoneMicrophoneRealtimeActionOutcome(
                    success = true,
                    runId = run.runId,
                    recordingId = run.recordingId,
                )
            is PhoneMicrophoneRecordingActionResult.Rejected -> {
                liveStateStore.paused()
                liveStateStore.degraded("PHONE_RECORDING_RESUME_FAILED", recordingResume.message)
                failure(
                    code = "PHONE_RECORDING_RESUME_FAILED",
                    message = recordingResume.message,
                    runId = run.runId,
                    recordingId = run.recordingId,
                )
            }
        }
    }

    suspend fun stop(): PhoneMicrophoneRealtimeActionOutcome = commandMutex.withLock {
        val run = activeRun ?: return@withLock failure("NOT_ACTIVE", "当前没有手机实时转写")
        run.stopRequested = true
        liveStateStore.finalizing()

        return@withLock when (val recordingStop = recordingRuntime.stop()) {
            PhoneMicrophoneRecordingStopResult.Empty -> {
                closeRunnerWithoutMedia(run)
                runCatching {
                    run.persistence.finalizeRun(
                        state = RealtimeTranscriptionStateValue.CANCELLED,
                        endReason = "EMPTY_RECORDING",
                    )
                }
                activeRun = null
                liveStateStore.reset()
                failure(
                    code = "EMPTY_RECORDING",
                    message = "没有录到有效音频",
                    runId = run.runId,
                )
            }

            is PhoneMicrophoneRecordingStopResult.Failed -> {
                closeRunnerWithoutMedia(run)
                runCatching {
                    run.persistence.finalizeRun(
                        state = RealtimeTranscriptionStateValue.FAILED,
                        endReason = "PHONE_RECORDING_STOP_FAILED",
                        errorCode = "PHONE_RECORDING_STOP_FAILED",
                        errorMessage = recordingStop.message,
                    )
                }
                activeRun = null
                liveStateStore.failed("PHONE_RECORDING_STOP_FAILED", recordingStop.message)
                failure(
                    code = "PHONE_RECORDING_STOP_FAILED",
                    message = recordingStop.message,
                    runId = run.runId,
                    recordingId = run.recordingId,
                )
            }

            is PhoneMicrophoneRecordingStopResult.Saved ->
                finishSavedRecording(run, recordingStop)
        }
    }

    fun snapshot(): PhoneMicrophoneRealtimeRuntimeSnapshot {
        val run = activeRun
        return PhoneMicrophoneRealtimeRuntimeSnapshot(
            activeRunId = run?.runId,
            runnerState = run?.bundle?.runner?.snapshot()?.state,
            runnerFailure = run?.runnerFailure,
            bridge = run?.bridge?.snapshot(),
            recording = recordingRuntime.state.value,
        )
    }

    private suspend fun finishSavedRecording(
        run: ActiveRun,
        saved: PhoneMicrophoneRecordingStopResult.Saved,
    ): PhoneMicrophoneRealtimeActionOutcome {
        run.bridge.endAfterStop()
        val joined =
            run.runnerJob?.let { job ->
                withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) {
                    job.join()
                    true
                } ?: false
            } ?: true
        if (!joined) {
            run.runnerFailure = "STOP_FINALIZATION_TIMEOUT:实时转写最终收口超时"
            run.runnerJob?.cancelAndJoin()
        }

        // Drain durable output before attaching/finalizing media lineage.
        runCatching { run.bundle.release() }
        val bridgeSnapshot = run.bridge.snapshot()
        val sessionId = bridgeSnapshot.lastOfferedSessionId
        if (sessionId != null) {
            val finalization =
                RealtimePcmWavSegmentFinalization(
                    file = saved.file,
                    sampleCount = saved.sampleCount,
                    sizeBytes = saved.sizeBytes,
                    sha256 = saved.sha256,
                    hadDiscontinuity = false,
                )
            runCatching {
                run.persistence.onLocalAudioResult(
                    RealtimeLocalAudioResult.Finalized(
                        canonicalSessionId = sessionId,
                        finalEpochId = bridgeSnapshot.lastOfferedEpochId,
                        finalSampleIndexExclusive =
                            bridgeSnapshot.lastOfferedEndSampleIndexExclusive ?: 0L,
                        endReason = RealtimeCanonicalSessionEndReason.STOP,
                        drainTimedOut = !joined,
                        media = finalization,
                    ),
                    isCompleteDeviceRecording = false,
                )
            }.onFailure { failure ->
                run.persistenceFailure = "MEDIA_FINALIZATION:${failure.message.orEmpty()}"
            }
        }

        val attached =
            runCatching { run.persistence.attachRecording(saved.recordingId) }
                .getOrElse { failure ->
                    run.persistenceFailure = "ATTACH_RECORDING:${failure.message.orEmpty()}"
                    false
                }
        if (!attached && run.persistenceFailure == null) {
            run.persistenceFailure = "ATTACH_RECORDING:realtime run attach returned false"
        }

        val timeoutFailure = run.runnerFailure?.startsWith("STOP_FINALIZATION_TIMEOUT") == true
        val fatalFailure =
            when {
                timeoutFailure -> null
                run.runnerFailure != null -> run.runnerFailure
                run.persistenceFailure != null -> run.persistenceFailure
                else -> null
            }
        val terminalState =
            if (fatalFailure == null && !timeoutFailure) {
                RealtimeTranscriptionStateValue.COMPLETED
            } else {
                RealtimeTranscriptionStateValue.FAILED
            }
        val terminalCode =
            when {
                timeoutFailure -> Stage16AStopFinalizationPolicy.ERROR_CODE
                fatalFailure != null -> "PHONE_REALTIME_FAILED"
                else -> null
            }
        val terminalReason =
            when {
                timeoutFailure -> Stage16AStopFinalizationPolicy.ERROR_CODE
                fatalFailure != null -> "PHONE_REALTIME_FAILED"
                else -> RealtimeCanonicalSessionEndReason.STOP.name
            }

        runCatching {
            run.persistence.finalizeRun(
                state = terminalState,
                endReason = terminalReason,
                errorCode = terminalCode,
                errorMessage = fatalFailure ?: run.runnerFailure,
            )
        }.onFailure { failure ->
            run.persistenceFailure = "FINALIZE_RUN:${failure.message.orEmpty()}"
        }

        var projectionFailure: String? = null
        if (attached && fatalFailure == null) {
            runCatching { projectCompletedRun(run.runId, saved.recordingId) }
                .onFailure { projectionFailure = it.message ?: it.javaClass.simpleName }
        }

        run.bridge.close()
        activeRun = null
        return when {
            fatalFailure != null || run.persistenceFailure != null -> {
                val detail = fatalFailure ?: run.persistenceFailure ?: "实时转写保存异常"
                liveStateStore.failed(
                    "PHONE_REALTIME_FAILED",
                    "手机录音已保存，但实时转写未完整完成",
                )
                PhoneMicrophoneRealtimeActionOutcome(
                    success = true,
                    code = "PHONE_REALTIME_FAILED",
                    message = detail,
                    runId = run.runId,
                    recordingId = saved.recordingId,
                    degraded = true,
                )
            }

            timeoutFailure -> {
                liveStateStore.completedDegraded(
                    Stage16AStopFinalizationPolicy.ERROR_CODE,
                    Stage16AStopFinalizationPolicy.DEGRADED_MESSAGE,
                )
                PhoneMicrophoneRealtimeActionOutcome(
                    success = true,
                    code = Stage16AStopFinalizationPolicy.ERROR_CODE,
                    message = Stage16AStopFinalizationPolicy.DEGRADED_MESSAGE,
                    runId = run.runId,
                    recordingId = saved.recordingId,
                    degraded = true,
                )
            }

            projectionFailure != null -> {
                liveStateStore.completedDegraded(
                    "REALTIME_PROJECTION_FAILED",
                    "实时文字已保存，但录音详情投影需要稍后重建",
                )
                PhoneMicrophoneRealtimeActionOutcome(
                    success = true,
                    code = "REALTIME_PROJECTION_FAILED",
                    message = projectionFailure,
                    runId = run.runId,
                    recordingId = saved.recordingId,
                    degraded = true,
                )
            }

            else -> {
                liveStateStore.completed()
                PhoneMicrophoneRealtimeActionOutcome(
                    success = true,
                    runId = run.runId,
                    recordingId = saved.recordingId,
                )
            }
        }
    }

    private suspend fun projectCompletedRun(runId: String, recordingId: String) {
        val recording = checkNotNull(recordingLibraryRepository.loadRecording(recordingId)) {
            "phone recording disappeared before realtime projection"
        }
        val canonical =
            checkNotNull(
                recording.assets.firstOrNull { asset ->
                    asset.role == AudioAssetRole.CANONICAL_WAV &&
                        asset.integrityState == AudioIntegrityState.VERIFIED
                },
            ) { "verified phone canonical WAV is missing" }
        integrationRepository.projectCompletedRealtimeRun(
            runId = runId,
            recordingId = recordingId,
            canonicalAssetId = canonical.assetId,
            canonicalSha256 = canonical.sha256,
            canonicalProfileId = CanonicalPcmProfile.PROFILE_ID,
            canonicalPipelineVersion = CanonicalAudioCoordinator.PIPELINE_VERSION,
        )
    }

    private suspend fun rollbackFailedStart(run: ActiveRun, message: String) {
        run.stopRequested = true
        run.bridge.endImmediately(RealtimeCanonicalSessionEndReason.RUNTIME_CLOSED)
        run.bridge.close()
        run.runnerJob?.cancelAndJoin()
        runCatching { run.bundle.release() }
        runCatching {
            run.persistence.finalizeRun(
                state = RealtimeTranscriptionStateValue.FAILED,
                endReason = "PHONE_RECORDING_START_FAILED",
                errorCode = "PHONE_RECORDING_START_FAILED",
                errorMessage = message,
            )
        }
        if (activeRun === run) activeRun = null
        liveStateStore.failed("PHONE_RECORDING_START_FAILED", message)
    }

    private suspend fun closeRunnerWithoutMedia(run: ActiveRun) {
        run.bridge.endImmediately(RealtimeCanonicalSessionEndReason.RUNTIME_CLOSED)
        run.bridge.close()
        run.runnerJob?.cancelAndJoin()
        runCatching { run.bundle.release() }
    }

    private fun onDurableFailure(runId: String, failure: RealtimeDurableOutputFailure) {
        if (activeRun?.runId != runId) return
        liveStateStore.degraded(
            code = "DURABLE_${failure.code.name}",
            message = "实时文字仍在继续，但本次结果保存出现异常",
        )
    }

    private fun createRunEntity(
        runId: String,
        settings: LocalSpeechSettings,
        models: Stage16ARealtimeResolvedModels,
    ): RealtimeTranscriptionRunEntity {
        val now = clockMs()
        val asr = models.asr.descriptor
        val vad = models.vad.descriptor
        val punctuation = models.punctuation?.descriptor
        return RealtimeTranscriptionRunEntity(
            id = runId,
            recordingId = null,
            deviceRecordingIdentity = null,
            audioSourceType = RealtimeAudioSourceTypeValue.PHONE_MICROPHONE,
            state = RealtimeTranscriptionStateValue.ACTIVE,
            modelId = asr.modelId,
            modelVersion = asr.version,
            modelRevision = asr.revision,
            runtimeId = SherpaRuntime.RUNTIME_ID,
            runtimeVersion = SherpaRuntime.RUNTIME_VERSION,
            vadModelId = vad.modelId,
            vadModelVersion = vad.version,
            vadModelRevision = vad.revision,
            punctuationModelId = punctuation?.modelId,
            punctuationModelVersion = punctuation?.version,
            punctuationModelRevision = punctuation?.revision,
            languageConfig = asr.languages.sorted().joinToString(","),
            configSnapshotSchemaVersion = SNAPSHOT_SCHEMA_VERSION,
            requestedConfigSnapshot = requestedSnapshot(settings),
            effectiveConfigSnapshot = effectiveSnapshot(models),
            modelManifestDigest = manifestLineageDigest(models),
            createdAtMs = now,
            startedAtMs = now,
            updatedAtMs = now,
            endedAtMs = null,
            endReason = null,
            errorCode = null,
            errorMessage = null,
        )
    }

    private fun requestedSnapshot(settings: LocalSpeechSettings): String =
        "{" +
            "\"schemaVersion\":$SNAPSHOT_SCHEMA_VERSION," +
            "\"realtimeModel\":\"${jsonEscape(settings.realtimeAsrModel.name)}\"," +
            "\"performanceProfile\":\"${jsonEscape(settings.performanceProfile.name)}\"," +
            "\"requestedThreads\":${settings.requestedThreads ?: "null"}," +
            "\"decodingMethod\":\"${jsonEscape(settings.realtime.decodingMethod.runtimeValue)}\"," +
            "\"maxActivePaths\":${settings.realtime.maxActivePaths}," +
            "\"vadThreshold\":${settings.vad.threshold}," +
            "\"vadMinSilenceSeconds\":${settings.vad.minSilenceDurationSeconds}," +
            "\"vadMinSpeechSeconds\":${settings.vad.minSpeechDurationSeconds}," +
            "\"vadMaxSpeechSeconds\":${settings.vad.maxSpeechDurationSeconds}" +
            "}"

    private fun effectiveSnapshot(models: Stage16ARealtimeResolvedModels): String =
        "{" +
            "\"schemaVersion\":$SNAPSHOT_SCHEMA_VERSION," +
            "\"modelId\":\"${jsonEscape(models.asr.descriptor.modelId)}\"," +
            "\"effectiveThreads\":${models.performance.effectiveThreads}," +
            "\"decodingMethod\":\"${jsonEscape(models.realtimeSettings.decodingMethod.runtimeValue)}\"," +
            "\"maxActivePaths\":${models.realtimeSettings.maxActivePaths}," +
            "\"vadModelId\":\"${jsonEscape(models.vad.descriptor.modelId)}\"," +
            "\"punctuationModelId\":" +
            (models.punctuation?.descriptor?.modelId?.let { "\"${jsonEscape(it)}\"" } ?: "null") +
            ",\"runtimeId\":\"${jsonEscape(SherpaRuntime.RUNTIME_ID)}\"," +
            "\"runtimeVersion\":\"${jsonEscape(SherpaRuntime.RUNTIME_VERSION)}\"" +
            "}"

    private fun manifestLineageDigest(models: Stage16ARealtimeResolvedModels): String {
        val rows =
            models.all
                .map { active ->
                    "${active.descriptor.modelId}|${active.descriptor.version}|" +
                        "${active.descriptor.revision}|${active.manifestDigest}"
                }
                .sorted()
                .joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(rows.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun relativeRecordingPath(file: File): String =
        runCatching { file.canonicalFile.relativeTo(recordingsRoot.canonicalFile).path }
            .getOrElse { file.name }

    private fun jsonEscape(value: String): String =
        buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }

    private fun failure(
        code: String,
        message: String,
        runId: String? = null,
        recordingId: String? = null,
    ) = PhoneMicrophoneRealtimeActionOutcome(
        success = false,
        code = code,
        message = message,
        runId = runId,
        recordingId = recordingId,
    )

    private class ActiveRun(
        val runId: String,
        val persistence: RealtimeTranscriptPersistenceCoordinator,
        val bundle: Stage16ARealtimePipelineBundle,
        val bridge: PhoneMicrophoneRealtimeCanonicalBridge,
    ) {
        @Volatile
        var runnerJob: Job? = null

        @Volatile
        var recordingId: String? = null

        @Volatile
        var stopRequested: Boolean = false

        @Volatile
        var runnerFailure: String? = null

        @Volatile
        var persistenceFailure: String? = null
    }

    private companion object {
        const val SNAPSHOT_SCHEMA_VERSION = 1
        const val PAUSE_DRAIN_TIMEOUT_MS = 1_500L
        const val STOP_JOIN_TIMEOUT_MS = 5_000L
    }
}
