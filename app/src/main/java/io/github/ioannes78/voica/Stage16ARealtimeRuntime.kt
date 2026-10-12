package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.Stage15CanonicalRealtimeEventSourceAdapter
import io.github.ioannes78.voica.database.RealtimeAudioSourceTypeValue
import io.github.ioannes78.voica.database.RealtimeTranscriptPersistenceRepository
import io.github.ioannes78.voica.database.RealtimeTranscriptionRunEntity
import io.github.ioannes78.voica.database.RealtimeTranscriptionStateValue
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.transcript.RealtimeAutoRecoveryContext
import io.github.ioannes78.voica.transcript.RealtimeAutoRecoveryDecision
import io.github.ioannes78.voica.transcript.RealtimeAutoRecoveryPolicy
import io.github.ioannes78.voica.transcript.RealtimeDurableOutputFailure
import io.github.ioannes78.voica.transcript.RealtimePauseFinalizationStatus
import io.github.ioannes78.voica.transcript.RealtimeProcessedCanonicalBoundary
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionSessionRunner
import io.github.ioannes78.voica.transcript.RealtimeTranscriptionSessionRunnerState
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class Stage16ARealtimeActionOutcome(
    val success: Boolean,
    val code: String? = null,
    val message: String? = null,
    val degraded: Boolean = false,
)

data class Stage16ARealtimeRecoveryOutcome(
    val decision: RealtimeAutoRecoveryDecision,
    val attached: Boolean = false,
    val failure: RealtimeExistingRecordingAttachFailure? = null,
)

/**
 * Application-level Stage 16A product owner above the frozen Stage 15 realtime media runtime.
 *
 * The owner never consumes BLE/Opus/GATT directly. One pipeline bundle is the sole destructive
 * consumer of [RealtimeAudioRuntime.canonicalSource] for the complete physical realtime run, including
 * transport reconnect segments. Model choice is resolved once at start and held by leases until the
 * logical run is terminal.
 */
class Stage16ARealtimeRuntime(
    private val scope: CoroutineScope,
    private val recorderRuntime: RealtimeAudioRuntime,
    private val modelResolver: Stage16ARealtimeModelResolver,
    private val pipelineFactory: Stage16ARealtimePipelineBundleFactory,
    private val persistenceRepository: RealtimeTranscriptPersistenceRepository,
    private val localAudioRoot: File,
    private val localSpeechSettings: () -> LocalSpeechSettings,
    private val currentDeviceFilename: () -> String? = { null },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val commandMutex = Mutex()
    private val liveStateStore = Stage16ARealtimeLiveStateStore()
    val state = liveStateStore.state

    @Volatile
    private var activeRun: ActiveRun? = null

    suspend fun start(): Stage16ARealtimeActionOutcome = commandMutex.withLock {
        if (activeRun != null) {
            return@withLock failure("ALREADY_ACTIVE", "实时转写已经在运行")
        }

        val settings = localSpeechSettings()
        val runId = runIdFactory()
        val modelLeases =
            try {
                modelResolver.acquire(settings)
            } catch (failure: Throwable) {
                liveStateStore.failed("MODEL_RESOLUTION_FAILED", failure.message.orEmpty())
                return@withLock failure(
                    "MODEL_RESOLUTION_FAILED",
                    failure.message ?: "实时转写模型不可用",
                )
            }

        val models = modelLeases.models
        liveStateStore.preparing(
            runId = runId,
            modelId = models.asr.descriptor.modelId,
            modelLabel = models.asr.descriptor.displayName,
        )

        val persistence =
            RealtimeTranscriptPersistenceCoordinator(
                repository = persistenceRepository,
                runId = runId,
                transportGenerationProvider = { recorderRuntime.snapshot().transportGeneration },
                localPathResolver = { file -> relativeRealtimePath(file) },
                clockMs = clockMs,
            )
        try {
            persistenceRepository.createRun(
                createRunEntity(
                    runId = runId,
                    settings = settings,
                    models = models,
                ),
            )
        } catch (failure: Throwable) {
            modelLeases.close()
            liveStateStore.failed("RUN_PERSISTENCE_FAILED", failure.message.orEmpty())
            return@withLock failure(
                "RUN_PERSISTENCE_FAILED",
                failure.message ?: "无法创建实时转写记录",
            )
        }

        val bundle =
            try {
                pipelineFactory.create(
                    source = Stage15CanonicalRealtimeEventSourceAdapter(recorderRuntime.canonicalSource),
                    models = modelLeases,
                    localAudioRoot = localAudioRoot,
                    persistDurableOutput = persistence::accept,
                    onDurableFailure = { durableFailure ->
                        onDurableFailure(runId, durableFailure)
                    },
                    onLocalAudioResult = { result ->
                        persistence.onLocalAudioResult(
                            result = result,
                            deviceFilename = currentDeviceFilename(),
                            isCompleteDeviceRecording = false,
                        )
                    },
                    liveOutputSinks = listOf(liveStateStore),
                )
            } catch (failure: Throwable) {
                runCatching {
                    persistence.finalizeRun(
                        state = RealtimeTranscriptionStateValue.FAILED,
                        endReason = "PIPELINE_PREPARE_FAILED",
                        errorCode = "PIPELINE_PREPARE_FAILED",
                        errorMessage = failure.message,
                    )
                }
                liveStateStore.failed("PIPELINE_PREPARE_FAILED", failure.message.orEmpty())
                return@withLock failure(
                    "PIPELINE_PREPARE_FAILED",
                    failure.message ?: "无法初始化实时转写引擎",
                )
            }

        val run = ActiveRun(runId, persistence, bundle)
        activeRun = run
        run.runnerJob =
            scope.launch {
                try {
                    bundle.runner.run()
                } catch (_: CancellationException) {
                    // Product owner controls cancellation during failed start/local termination.
                } catch (failure: Throwable) {
                    liveStateStore.failed(
                        "REALTIME_RUNNER_FAILED",
                        failure.message ?: failure.javaClass.simpleName,
                    )
                }
            }

        liveStateStore.starting()
        val command = recorderRuntime.start()
        if (!command.success) {
            val code = "START_${command.failure?.name ?: "FAILED"}"
            terminateRunLocked(
                run = run,
                terminalState = RealtimeTranscriptionStateValue.FAILED,
                endReason = "START_FAILED",
                errorCode = code,
                errorMessage = command.failure?.name,
                finalUiFailure = code to "录音启动失败，实时转写未开始",
            )
            return@withLock failure(code, "录音启动失败，实时转写未开始")
        }

        Stage16ARealtimeActionOutcome(success = true)
    }

    suspend fun pause(): Stage16ARealtimeActionOutcome = commandMutex.withLock {
        val run = activeRun
            ?: return@withLock failure("NOT_ACTIVE", "当前没有实时转写任务")
        val command = recorderRuntime.pause()
        if (!command.success) {
            return@withLock failure(
                "PAUSE_${command.failure?.name ?: "FAILED"}",
                "暂停录音失败",
            )
        }

        val drain = awaitPauseDrain(run.bundle.runner)
        val boundary = drain.boundary
        var degradedCode: String? = null
        if (!drain.drained) {
            degradedCode = "PAUSE_DRAIN_TIMEOUT"
        } else if (boundary != null) {
            val finalization =
                run.bundle.runner.finalizePausedContinuity(
                    sessionId = boundary.sessionId,
                    epochId = boundary.epochId,
                    endSampleIndexExclusive = boundary.endSampleIndexExclusive,
                )
            if (finalization.status == RealtimePauseFinalizationStatus.COMMITTED) {
                run.persistence.onPauseCommitted(
                    canonicalSessionId = boundary.sessionId,
                    canonicalEpochId = boundary.epochId,
                    sampleIndex = boundary.endSampleIndexExclusive,
                )
            } else {
                degradedCode = "PAUSE_${finalization.status.name}"
            }
        }

        liveStateStore.paused()
        if (degradedCode != null) {
            liveStateStore.degraded(
                degradedCode,
                "录音已暂停，但实时转写边界收口未完全确认",
            )
        }
        Stage16ARealtimeActionOutcome(
            success = true,
            code = degradedCode,
            message = degradedCode?.let { "录音已暂停，但实时转写边界收口未完全确认" },
            degraded = degradedCode != null,
        )
    }

    suspend fun resume(): Stage16ARealtimeActionOutcome = commandMutex.withLock {
        if (activeRun == null) {
            return@withLock failure("NOT_ACTIVE", "当前没有实时转写任务")
        }
        liveStateStore.resuming()
        val command = recorderRuntime.resume()
        if (!command.success) {
            liveStateStore.paused()
            return@withLock failure(
                "RESUME_${command.failure?.name ?: "FAILED"}",
                "继续录音失败",
            )
        }
        Stage16ARealtimeActionOutcome(success = true)
    }

    suspend fun stop(): Stage16ARealtimeActionOutcome = commandMutex.withLock {
        val run = activeRun
            ?: return@withLock failure("NOT_ACTIVE", "当前没有实时转写任务")
        liveStateStore.finalizing()
        val command = recorderRuntime.stop()
        if (!command.success) {
            val code = "STOP_${command.failure?.name ?: "FAILED"}"
            terminateRunLocked(
                run = run,
                terminalState = RealtimeTranscriptionStateValue.INTERRUPTED,
                endReason = "STOP_COMMAND_FAILED",
                errorCode = code,
                errorMessage = "设备停止命令未确认；设备可能仍在录音",
                interruptedMessage = "实时转写已停止 · 设备录音状态未确认",
            )
            return@withLock failure(code, "设备停止命令未确认；设备可能仍在录音")
        }

        val job = run.runnerJob
        val joined =
            job != null &&
                withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) {
                    job.join()
                    true
                } == true
        if (!joined || run.bundle.runner.snapshot().state == RealtimeTranscriptionSessionRunnerState.RUNNING) {
            job?.cancelAndJoin()
            terminateRunLocked(
                run = run,
                terminalState = RealtimeTranscriptionStateValue.FAILED,
                endReason = "STOP_FINALIZATION_TIMEOUT",
                errorCode = "STOP_FINALIZATION_TIMEOUT",
                errorMessage = "设备已停止，但实时转写最终收口超时",
                finalUiFailure = "STOP_FINALIZATION_TIMEOUT" to "设备已停止，但实时转写最终收口超时",
                runnerAlreadyStopped = true,
            )
            return@withLock failure(
                "STOP_FINALIZATION_TIMEOUT",
                "设备已停止，但实时转写最终收口超时",
            )
        }

        return@withLock try {
            run.bundle.release()
            run.persistence.finalizeRun(
                state = RealtimeTranscriptionStateValue.COMPLETED,
                endReason = "STOP",
            )
            if (activeRun === run) activeRun = null
            liveStateStore.completed()
            Stage16ARealtimeActionOutcome(success = true)
        } catch (failure: Throwable) {
            if (activeRun === run) activeRun = null
            liveStateStore.failed(
                "FINAL_PERSISTENCE_FAILED",
                failure.message ?: "实时转写最终保存失败",
            )
            Stage16ARealtimeActionOutcome(
                success = false,
                code = "FINAL_PERSISTENCE_FAILED",
                message = failure.message ?: "实时转写最终保存失败",
            )
        }
    }

    /**
     * Conservative reconnect seam. The caller supplies independently verified device/recording
     * identities; unknown identity never auto-resumes. This method never sends recorder START/RESUME.
     */
    suspend fun recoverAfterReconnect(
        context: RealtimeAutoRecoveryContext,
    ): Stage16ARealtimeRecoveryOutcome = commandMutex.withLock {
        val run = activeRun
            ?: return@withLock Stage16ARealtimeRecoveryOutcome(
                decision = RealtimeAutoRecoveryDecision.NO_ACTIVE_REALTIME_INTENT,
            )
        val decision = RealtimeAutoRecoveryPolicy.evaluate(context)
        if (decision != RealtimeAutoRecoveryDecision.RESUME) {
            return@withLock Stage16ARealtimeRecoveryOutcome(decision = decision)
        }

        run.persistence.markNextSessionAsReconnected()
        val attach = recorderRuntime.attachConfirmedExistingRecording()
        if (!attach.success) {
            run.persistence.cancelPendingReconnectMarker()
            return@withLock Stage16ARealtimeRecoveryOutcome(
                decision = decision,
                attached = false,
                failure = attach.failure,
            )
        }
        Stage16ARealtimeRecoveryOutcome(
            decision = decision,
            attached = true,
        )
    }

    private suspend fun awaitPauseDrain(
        runner: RealtimeTranscriptionSessionRunner,
    ): PauseDrainResult {
        val result: PauseDrainResult? =
            withTimeoutOrNull(PAUSE_DRAIN_TIMEOUT_MS) {
                var consecutiveDrainedObservations = 0
                var boundary: RealtimeProcessedCanonicalBoundary? = null
                while (consecutiveDrainedObservations < 2) {
                    val source = recorderRuntime.snapshot().source
                    val runnerSnapshot = runner.snapshot()
                    val drained =
                        source.queuedPcmFrames == 0 &&
                            runnerSnapshot.processedEvents ==
                            runnerSnapshot.coordinator.consumedSourceEvents
                    if (drained) {
                        consecutiveDrainedObservations += 1
                        boundary = runnerSnapshot.lastProcessedBoundary
                    } else {
                        consecutiveDrainedObservations = 0
                        boundary = null
                    }
                    if (consecutiveDrainedObservations < 2) {
                        delay(PAUSE_DRAIN_POLL_MS)
                    }
                }
                PauseDrainResult(
                    drained = true,
                    boundary = boundary,
                )
            }
        return result ?: PauseDrainResult(
            drained = false,
            boundary = runner.snapshot().lastProcessedBoundary,
        )
    }

    private suspend fun terminateRunLocked(
        run: ActiveRun,
        terminalState: String,
        endReason: String,
        errorCode: String?,
        errorMessage: String?,
        finalUiFailure: Pair<String, String>? = null,
        interruptedMessage: String? = null,
        runnerAlreadyStopped: Boolean = false,
    ) {
        if (!runnerAlreadyStopped) {
            run.runnerJob?.cancelAndJoin()
        }
        runCatching { run.bundle.release() }
        runCatching {
            run.persistence.finalizeRun(
                state = terminalState,
                endReason = endReason,
                errorCode = errorCode,
                errorMessage = errorMessage,
            )
        }
        if (activeRun === run) activeRun = null
        when {
            finalUiFailure != null ->
                liveStateStore.failed(finalUiFailure.first, finalUiFailure.second)
            interruptedMessage != null ->
                liveStateStore.interrupted(interruptedMessage, errorCode, errorMessage)
        }
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
            audioSourceType = RealtimeAudioSourceTypeValue.RECORDER_CARD,
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

    private fun relativeRealtimePath(file: File): String =
        runCatching { file.canonicalFile.relativeTo(localAudioRoot.canonicalFile).path }
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

    private fun failure(code: String, message: String) =
        Stage16ARealtimeActionOutcome(
            success = false,
            code = code,
            message = message,
        )

    private class ActiveRun(
        val runId: String,
        val persistence: RealtimeTranscriptPersistenceCoordinator,
        val bundle: Stage16ARealtimePipelineBundle,
    ) {
        var runnerJob: Job? = null
    }

    private data class PauseDrainResult(
        val drained: Boolean,
        val boundary: RealtimeProcessedCanonicalBoundary?,
    )

    private companion object {
        const val SNAPSHOT_SCHEMA_VERSION = 1
        const val PAUSE_DRAIN_TIMEOUT_MS = 1_500L
        const val PAUSE_DRAIN_POLL_MS = 5L
        const val STOP_JOIN_TIMEOUT_MS = 5_000L
    }
}