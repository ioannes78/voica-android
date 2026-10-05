package io.github.ioannes78.voica

import android.content.res.AssetManager
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.database.CanonicalTranscriptionLineage
import io.github.ioannes78.voica.database.NewTranscriptionRequest
import io.github.ioannes78.voica.database.TranscriptSegmentWrite
import io.github.ioannes78.voica.database.TranscriptTokenSourceValue
import io.github.ioannes78.voica.database.TranscriptTokenWrite
import io.github.ioannes78.voica.database.TranscriptionModeValue
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.TranscriptionStateValue
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelLease
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.sherpa.LargeOfflineAsrSettings
import io.github.ioannes78.voica.sherpa.PunctuationSettings
import io.github.ioannes78.voica.sherpa.SenseVoiceSettings
import io.github.ioannes78.voica.sherpa.SherpaCtTransformerPunctuationEngine
import io.github.ioannes78.voica.sherpa.SherpaSenseVoiceEngine
import io.github.ioannes78.voica.sherpa.SherpaSileroVadEngine
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.sherpa.SherpaVadModelLocation
import io.github.ioannes78.voica.sherpa.SileroVadSettings
import io.github.ioannes78.voica.sherpa.StreamingZipformerSettings
import io.github.ioannes78.voica.sherpa.createSherpaLargeOfflineAsrEngine
import io.github.ioannes78.voica.sherpa.createSherpaStreamingAsrEngine
import io.github.ioannes78.voica.transcript.FastTranscriptionPipeline
import io.github.ioannes78.voica.transcript.HighQualityTranscriptionPipeline
import io.github.ioannes78.voica.transcript.PunctuationEngineFactory
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SecondPassAsrEngineFactory
import io.github.ioannes78.voica.transcript.StreamingAsrEngineFactory
import io.github.ioannes78.voica.transcript.TokenSource
import io.github.ioannes78.voica.transcript.TranscriptSegment
import io.github.ioannes78.voica.transcript.TranscriptionMode
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import io.github.ioannes78.voica.transcript.VadEngineFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Stage8ModelIds {
    const val VAD = "silero-vad-int8"
    const val FIRST_PASS_ASR = "zipformer-small-bilingual"
    const val PUNCTUATION = "ct-transformer-zh-en-int8"
    const val SECOND_PASS_ASR = "sensevoice-2024-int8"
}

sealed interface TranscriptionRunState {
    data object Idle : TranscriptionRunState

    data class Running(
        val recordingId: String,
        val transcriptionId: String?,
        val mode: TranscriptionMode,
        val modelId: String?,
        val progress: TranscriptionProgress,
    ) : TranscriptionRunState

    data class Completed(
        val recordingId: String,
        val transcriptionId: String,
        val mode: TranscriptionMode,
        val segments: List<TranscriptSegment>,
        val warning: String? = null,
    ) : TranscriptionRunState

    data class Failed(
        val recordingId: String,
        val mode: TranscriptionMode,
        val message: String,
        val missingModelIds: List<String> = emptyList(),
    ) : TranscriptionRunState

    data class Cancelled(
        val recordingId: String,
        val mode: TranscriptionMode,
    ) : TranscriptionRunState
}

class MissingTranscriptionModelsException(
    val modelIds: List<String>,
) : IllegalStateException(
    "missing active transcription models: " + modelIds.joinToString(),
)

interface Stage8TranscriptionEngineProvider {
    fun vadFactory(
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings = LocalVadSettings(),
    ): VadEngineFactory

    fun firstPassFactory(
        model: ActiveModel,
        numThreads: Int,
        realtimeSettings: LocalRealtimeAsrSettings = LocalRealtimeAsrSettings(),
    ): StreamingAsrEngineFactory

    fun punctuationFactory(
        model: ActiveModel,
        numThreads: Int,
    ): PunctuationEngineFactory

    fun secondPassFactory(
        model: ActiveModel,
        numThreads: Int,
        senseVoiceSettings: LocalSenseVoiceSettings = LocalSenseVoiceSettings(),
        qwenSettings: LocalQwenAsrSettings = LocalQwenAsrSettings(),
    ): SecondPassAsrEngineFactory
}

class SherpaStage8TranscriptionEngineProvider(
    private val assetManager: AssetManager,
) : Stage8TranscriptionEngineProvider {
    override fun vadFactory(
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings,
    ): VadEngineFactory {
        require(model.descriptor.kind == ModelKind.VAD)
        return VadEngineFactory {
            val location =
                model.installedDirectory?.let { directory ->
                    val file =
                        model.descriptor.files
                            .singleOrNull { it.relativePath.endsWith(".onnx") }
                            ?.let { File(directory, it.relativePath) }
                            ?: error("VAD model must contain one ONNX file")
                    SherpaVadModelLocation.File(file.canonicalPath)
                } ?: SherpaVadModelLocation.Asset(
                    assetManager = assetManager,
                    assetPath =
                        model.descriptor.builtinAssetPath
                            ?: error("builtin VAD is missing asset path"),
                )
            SherpaSileroVadEngine(
                model = model.descriptor,
                modelLocation = location,
                settings =
                    SileroVadSettings(
                        threshold = vadSettings.threshold,
                        minSilenceDurationSeconds = vadSettings.minSilenceDurationSeconds,
                        minSpeechDurationSeconds = vadSettings.minSpeechDurationSeconds,
                        maxSpeechDurationSeconds = vadSettings.maxSpeechDurationSeconds,
                        numThreads = numThreads,
                    ),
            )
        }
    }

    override fun firstPassFactory(
        model: ActiveModel,
        numThreads: Int,
        realtimeSettings: LocalRealtimeAsrSettings,
    ): StreamingAsrEngineFactory {
        require(model.descriptor.kind == ModelKind.ASR_STREAMING)
        val directory =
            model.installedDirectory
                ?: error("first-pass ASR must be a managed installed model")
        val resolved =
            realtimeSettings.resolveFor(
                model.descriptor.capabilities.supportedParameters,
            )
        return StreamingAsrEngineFactory {
            createSherpaStreamingAsrEngine(
                model = model.descriptor,
                modelDirectory = directory,
                settings =
                    StreamingZipformerSettings(
                        numThreads = numThreads,
                        decodingMethod = resolved.decodingMethod.runtimeValue,
                        maxActivePaths = resolved.maxActivePaths,
                    ),
            )
        }
    }

    override fun punctuationFactory(
        model: ActiveModel,
        numThreads: Int,
    ): PunctuationEngineFactory {
        require(model.descriptor.kind == ModelKind.PUNCTUATION)
        val directory =
            model.installedDirectory
                ?: error("punctuation model must be a managed installed model")
        return PunctuationEngineFactory {
            SherpaCtTransformerPunctuationEngine(
                model = model.descriptor,
                modelDirectory = directory,
                settings = PunctuationSettings(numThreads = numThreads),
            )
        }
    }

    override fun secondPassFactory(
        model: ActiveModel,
        numThreads: Int,
        senseVoiceSettings: LocalSenseVoiceSettings,
        qwenSettings: LocalQwenAsrSettings,
    ): SecondPassAsrEngineFactory {
        require(
            model.descriptor.kind == ModelKind.ASR_SECOND_PASS ||
                model.descriptor.kind == ModelKind.ASR_LARGE,
        )
        val directory =
            model.installedDirectory
                ?: error("second-pass ASR must be a managed installed model")
        return SecondPassAsrEngineFactory {
            when (model.descriptor.kind) {
                ModelKind.ASR_SECOND_PASS ->
                    SherpaSenseVoiceEngine(
                        model = model.descriptor,
                        modelDirectory = directory,
                        settings =
                            SenseVoiceSettings(
                                numThreads = numThreads,
                                language = senseVoiceSettings.language.runtimeValue,
                                useInverseTextNormalization =
                                    senseVoiceSettings.useInverseTextNormalization,
                            ),
                    )
                ModelKind.ASR_LARGE ->
                    createSherpaLargeOfflineAsrEngine(
                        model = model.descriptor,
                        modelDirectory = directory,
                        settings =
                            LargeOfflineAsrSettings(
                                numThreads = numThreads,
                                qwenMaxTotalLen = qwenSettings.maxTotalLen,
                                qwenMaxNewTokens = qwenSettings.maxNewTokens,
                                qwenTemperature = qwenSettings.temperature,
                                qwenTopP = qwenSettings.topP,
                                qwenSeed = qwenSettings.seed,
                                qwenHotwords = qwenSettings.hotwords,
                            ),
                    )
                else -> error("unsupported second-pass ASR kind")
            }
        }
    }
}

class TranscriptionCoordinator(
    private val scope: CoroutineScope,
    private val pcmSourceResolver: PcmSourceResolver,
    private val transcriptionRepository: TranscriptionRepository,
    private val loadCanonicalLineage:
        suspend (recordingId: String, profileId: String) -> CanonicalTranscriptionLineage?,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val engineProvider: Stage8TranscriptionEngineProvider,
    private val localSpeechSettings: () -> LocalSpeechSettings = { LocalSpeechSettings() },
    private val isRecordingActive: suspend (String) -> Boolean = { true },
) {
    private val lock = Any()
    private var currentJob: Job? = null
    private var currentRecordingId: String? = null
    private val mutableState =
        MutableStateFlow<TranscriptionRunState>(TranscriptionRunState.Idle)

    val state: StateFlow<TranscriptionRunState> = mutableState.asStateFlow()

    fun selectedOfflineModelId(): String = localSpeechSettings().selectedOfflineModelId()

    fun start(
        recordingId: String,
        mode: TranscriptionMode,
    ): Boolean {
        require(recordingId.isNotBlank())
        synchronized(lock) {
            if (currentJob?.isActive == true) return false
            val job =
                scope.launch {
                    runTranscription(recordingId, mode)
                }
            currentJob = job
            currentRecordingId = recordingId
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (currentJob === job) {
                        currentJob = null
                        currentRecordingId = null
                    }
                }
            }
            return true
        }
    }

    fun cancel() {
        synchronized(lock) {
            currentJob?.cancel(CancellationException("user cancelled transcription"))
        }
    }

    suspend fun cancelAndAwait(recordingId: String) {
        val job =
            synchronized(lock) {
                currentJob?.takeIf { currentRecordingId == recordingId }
            } ?: return
        job.cancel(CancellationException("recording deletion"))
        runCatching { job.join() }
    }

    suspend fun reconcileOnStartup(): Int =
        transcriptionRepository.reconcileInterruptedOnStartup()

    private suspend fun runTranscription(
        recordingId: String,
        mode: TranscriptionMode,
    ) {
        var transcriptionId: String? = null
        var leases: List<ModelLease> = emptyList()
        val settings = localSpeechSettings()
        val requestedModelId =
            when (mode) {
                TranscriptionMode.FAST -> settings.selectedRealtimeModelId()
                TranscriptionMode.HIGH_QUALITY -> settings.selectedOfflineModelId()
            }

        try {
            if (!isRecordingActive(recordingId)) return

            mutableState.value =
                TranscriptionRunState.Running(
                    recordingId = recordingId,
                    transcriptionId = null,
                    mode = mode,
                    modelId = requestedModelId,
                    progress = TranscriptionProgress(TranscriptionPhase.PREPARING),
                )

            val lineage =
                loadCanonicalLineage(
                    recordingId,
                    CanonicalPcmProfile.PROFILE_ID,
                ) ?: error("verified canonical PCM is not ready")

            val totalSampleCount =
                pcmSourceResolver.resolvePcmSource(recordingId)?.use { source ->
                    source.totalSampleCount
                } ?: error("canonical PCM source is unavailable")

            val models = resolveActiveModels(mode, settings)
            leases =
                models.all.map { active ->
                    modelUseRegistry.acquire(
                        modelId = active.descriptor.modelId,
                        version = active.descriptor.version,
                        revision = active.descriptor.revision,
                    )
                }

            val request =
                buildRequest(
                    recordingId = recordingId,
                    mode = mode,
                    lineage = lineage,
                    totalSampleCount = totalSampleCount,
                    models = models,
                )
            if (!isRecordingActive(recordingId)) {
                throw CancellationException("recording is being deleted")
            }
            val createdTranscriptionId = transcriptionRepository.create(request)
            transcriptionId = createdTranscriptionId

            val progressListener =
                ProgressListener { progress ->
                    transitionForProgress(createdTranscriptionId, progress.phase)
                    mutableState.value =
                        TranscriptionRunState.Running(
                            recordingId = recordingId,
                            transcriptionId = createdTranscriptionId,
                            mode = mode,
                            modelId = models.primaryAsrModelId,
                            progress = progress,
                        )
                }

            val completed =
                when (mode) {
                    TranscriptionMode.FAST -> {
                        val result =
                            FastTranscriptionPipeline(
                                pcmSourceResolver = pcmSourceResolver,
                                vadEngineFactory =
                                    engineProvider.vadFactory(
                                        models.vad,
                                        models.performance.effectiveThreads,
                                        models.vadSettings,
                                    ),
                                asrEngineFactory =
                                    engineProvider.firstPassFactory(
                                        checkNotNull(models.firstPass),
                                        models.performance.effectiveThreads,
                                        models.realtimeSettings,
                                    ),
                                punctuationEngineFactory =
                                    engineProvider.punctuationFactory(
                                        checkNotNull(models.punctuation),
                                        models.performance.effectiveThreads,
                                    ),
                            ).transcribe(
                                recordingId = recordingId,
                                progressListener = progressListener,
                            )
                        CompletedPipeline(
                            segments = result.transcriptSegments,
                            warning = null,
                        )
                    }

                    TranscriptionMode.HIGH_QUALITY -> {
                        val secondPass =
                            models.secondPass
                                ?: error("offline mode is missing offline ASR")
                        val result =
                            HighQualityTranscriptionPipeline(
                                pcmSourceResolver = pcmSourceResolver,
                                vadEngineFactory =
                                    engineProvider.vadFactory(
                                        models.vad,
                                        models.performance.effectiveThreads,
                                        models.vadSettings,
                                    ),
                                secondPassAsrEngineFactory =
                                    engineProvider.secondPassFactory(
                                        model = secondPass,
                                        numThreads = models.performance.effectiveThreads,
                                        senseVoiceSettings = models.senseVoiceSettings,
                                        qwenSettings = models.qwenSettings,
                                    ),
                                punctuationEngineFactory =
                                    models.punctuation?.let { punctuation ->
                                        engineProvider.punctuationFactory(
                                            punctuation,
                                            models.performance.effectiveThreads,
                                        )
                                    },
                                timelineAlignmentAsrEngineFactory =
                                    models.timelineAlignment?.let { alignmentModel ->
                                        engineProvider.firstPassFactory(
                                            alignmentModel,
                                            models.performance.effectiveThreads,
                                            LocalRealtimeAsrSettings(),
                                        )
                                    },
                            ).transcribe(
                                recordingId = recordingId,
                                progressListener = progressListener,
                            )
                        CompletedPipeline(
                            segments = result.transcriptSegments,
                            warning =
                                result.secondPassFallbackError
                                    ?: result.punctuationFallbackError
                                    ?: result.timelineAlignmentFallbackError,
                        )
                    }
                }

            transcriptionRepository.persistCompleted(
                transcriptionId = createdTranscriptionId,
                segments = completed.segments.map(::toWrite),
            )

            leases.asReversed().forEach { lease ->
                lease.close()
            }
            leases = emptyList()

            mutableState.value =
                TranscriptionRunState.Completed(
                    recordingId = recordingId,
                    transcriptionId = createdTranscriptionId,
                    mode = mode,
                    segments = completed.segments,
                    warning = completed.warning,
                )
        } catch (cancelled: CancellationException) {
            transcriptionId?.let { id ->
                withContext(NonCancellable) {
                    runCatching {
                        transcriptionRepository.cancel(id)
                    }
                }
            }
            mutableState.value =
                TranscriptionRunState.Cancelled(
                    recordingId = recordingId,
                    mode = mode,
                )
        } catch (missing: MissingTranscriptionModelsException) {
            mutableState.value =
                TranscriptionRunState.Failed(
                    recordingId = recordingId,
                    mode = mode,
                    message = "请先在设置中下载并启用所需模型",
                    missingModelIds = missing.modelIds,
                )
        } catch (error: Throwable) {
            transcriptionId?.let { id ->
                withContext(NonCancellable) {
                    runCatching {
                        transcriptionRepository.failRecoverable(
                            transcriptionId = id,
                            errorCode = "TRANSCRIPTION_FAILED",
                            errorMessage = error.message,
                        )
                    }
                }
            }
            mutableState.value =
                TranscriptionRunState.Failed(
                    recordingId = recordingId,
                    mode = mode,
                    message = error.message ?: error::class.java.simpleName,
                )
        } finally {
            leases.asReversed().forEach { lease ->
                runCatching { lease.close() }
            }
        }
    }

    private suspend fun resolveActiveModels(
        mode: TranscriptionMode,
        settings: LocalSpeechSettings,
    ): ActiveModels {
        val performance = settings.resolvePerformance()
        val vad = modelManager.activeModel(Stage8ModelIds.VAD)
        val punctuation = modelManager.activeModel(Stage8ModelIds.PUNCTUATION)

        var firstPassSelection: ActiveModel? = null
        var secondPassSelection: ActiveModel? = null
        var timelineAlignment: ActiveModel? = null

        val realtimeCandidates = settings.realtimeAsrModel.preferredModelIds()
        if (mode == TranscriptionMode.FAST) {
            firstPassSelection =
                realtimeCandidates.firstNotNullOfOrNull { modelId ->
                    modelManager.activeModel(modelId)
                }?.also { model ->
                    require(model.descriptor.kind == ModelKind.ASR_STREAMING) {
                        "selected realtime ASR is not ASR_STREAMING: ${model.descriptor.modelId}"
                    }
                }
        }

        val offlineCandidates =
            if (mode == TranscriptionMode.HIGH_QUALITY) {
                settings.offlineAsrQuality.preferredModelIds()
            } else {
                emptyList()
            }
        if (mode == TranscriptionMode.HIGH_QUALITY) {
            secondPassSelection =
                offlineCandidates.firstNotNullOfOrNull { modelId ->
                    modelManager.activeModel(modelId)
                }?.also { model ->
                    require(
                        model.descriptor.kind == ModelKind.ASR_SECOND_PASS ||
                            model.descriptor.kind == ModelKind.ASR_LARGE,
                    ) {
                        "selected offline ASR is not an offline model: ${model.descriptor.modelId}"
                    }
                }

            if (secondPassSelection?.descriptor?.modelId == Stage13AOfflineModelIds.QWEN3_ASR) {
                timelineAlignment =
                    modelManager.activeModel(Stage13ARealtimeModelIds.SMALL_BILINGUAL)
                        ?.takeIf {
                            it.descriptor.kind == ModelKind.ASR_STREAMING &&
                                it.descriptor.capabilities.supportsTokenTiming
                        }
            }
        }

        val externalPunctuationRequired =
            when (secondPassSelection?.descriptor?.modelId) {
                Stage13AOfflineModelIds.SENSEVOICE ->
                    !settings.senseVoice.useInverseTextNormalization
                Stage13AOfflineModelIds.QWEN3_ASR -> false
                else -> false
            }

        val missing =
            buildList {
                if (vad == null) add(Stage8ModelIds.VAD)
                when (mode) {
                    TranscriptionMode.FAST -> {
                        if (firstPassSelection == null) addAll(realtimeCandidates)
                        if (punctuation == null) add(Stage8ModelIds.PUNCTUATION)
                    }

                    TranscriptionMode.HIGH_QUALITY -> {
                        if (secondPassSelection == null) addAll(offlineCandidates)
                        if (externalPunctuationRequired && punctuation == null) {
                            add(Stage8ModelIds.PUNCTUATION)
                        }
                    }
                }
            }.distinct().sorted()

        if (missing.isNotEmpty()) {
            throw MissingTranscriptionModelsException(missing)
        }

        return ActiveModels(
            vad = checkNotNull(vad),
            firstPass = firstPassSelection,
            punctuation =
                when {
                    mode == TranscriptionMode.FAST -> checkNotNull(punctuation)
                    externalPunctuationRequired -> checkNotNull(punctuation)
                    else -> null
                },
            secondPass = secondPassSelection,
            timelineAlignment = timelineAlignment,
            realtimeAsrChoice = settings.realtimeAsrModel,
            offlineAsrQualityChoice = settings.offlineAsrQuality,
            realtimeSettings = settings.realtime,
            performance = performance,
            senseVoiceSettings = settings.senseVoice,
            qwenSettings = settings.qwen,
            vadSettings = settings.vad,
        )
    }

    private fun buildRequest(
        recordingId: String,
        mode: TranscriptionMode,
        lineage: CanonicalTranscriptionLineage,
        totalSampleCount: Long,
        models: ActiveModels,
    ): NewTranscriptionRequest {
        val modelList = models.all
        val lineageAsr =
            when (mode) {
                TranscriptionMode.FAST -> checkNotNull(models.firstPass)
                TranscriptionMode.HIGH_QUALITY ->
                    models.timelineAlignment ?: checkNotNull(models.secondPass)
            }
        return NewTranscriptionRequest(
            recordingId = recordingId,
            mode =
                when (mode) {
                    TranscriptionMode.FAST -> TranscriptionModeValue.FAST
                    TranscriptionMode.HIGH_QUALITY -> TranscriptionModeValue.HIGH_QUALITY
                },
            sourceCanonicalAssetId = lineage.canonicalAssetId,
            sourceCanonicalSha256 = lineage.canonicalSha256,
            canonicalProfileId = lineage.canonicalProfileId,
            totalSampleCount = totalSampleCount,
            pipelineVersion = 2,
            runtimeId = SherpaRuntime.RUNTIME_ID,
            runtimeVersion = SherpaRuntime.RUNTIME_VERSION,
            vadModelId = models.vad.descriptor.modelId,
            vadModelVersion = models.vad.descriptor.version,
            firstPassAsrModelId = lineageAsr.descriptor.modelId,
            firstPassAsrModelVersion = lineageAsr.descriptor.version,
            secondPassAsrModelId = models.secondPass?.descriptor?.modelId,
            secondPassAsrModelVersion = models.secondPass?.descriptor?.version,
            punctuationModelId = models.punctuation?.descriptor?.modelId,
            punctuationModelVersion = models.punctuation?.descriptor?.version,
            languageConfig = models.effectiveLanguageConfig(),
            configSnapshot = configSnapshot(mode, models),
            modelManifestDigest = lineageDigest(modelList),
            vadModelRevision = models.vad.descriptor.revision,
            firstPassAsrModelRevision = lineageAsr.descriptor.revision,
            secondPassAsrModelRevision = models.secondPass?.descriptor?.revision,
            punctuationModelRevision = models.punctuation?.descriptor?.revision,
            configSnapshotSchemaVersion = 2,
            requestedConfigSnapshot = requestedConfigSnapshot(mode, models),
            effectiveConfigSnapshot = effectiveConfigSnapshot(mode, models),
        )
    }

    private suspend fun transitionForProgress(
        transcriptionId: String,
        phase: TranscriptionPhase,
    ) {
        val target =
            when (phase) {
                TranscriptionPhase.PREPARING -> null
                TranscriptionPhase.VAD -> TranscriptionStateValue.VAD_ANALYZING
                TranscriptionPhase.FIRST_PASS ->
                    TranscriptionStateValue.FIRST_PASS_TRANSCRIBING
                TranscriptionPhase.SECOND_PASS ->
                    TranscriptionStateValue.SECOND_PASS_TRANSCRIBING
                TranscriptionPhase.PUNCTUATION ->
                    TranscriptionStateValue.PUNCTUATING
                TranscriptionPhase.PERSISTING ->
                    TranscriptionStateValue.PERSISTING
            }
        if (target != null) {
            transcriptionRepository.transition(transcriptionId, target)
        }
    }

    private fun toWrite(segment: TranscriptSegment) =
        TranscriptSegmentWrite(
            segmentIndex = segment.segmentIndex,
            startSampleIndex = segment.startSampleIndex,
            endSampleIndexExclusive = segment.endSampleIndexExclusive,
            firstPassRawText = segment.firstPassRawText,
            secondPassRawText = segment.secondPassRawText,
            finalText = segment.finalText,
            detectedLanguage = segment.detectedLanguage,
            confidence = segment.confidence,
            tokens =
                segment.tokens.map { token ->
                    TranscriptTokenWrite(
                        text = token.text,
                        startSampleIndex = token.startSampleIndex,
                        endSampleIndexExclusive = token.endSampleIndexExclusive,
                        source =
                            when (token.source) {
                                TokenSource.FIRST_PASS ->
                                    TranscriptTokenSourceValue.FIRST_PASS
                                TokenSource.SECOND_PASS ->
                                    TranscriptTokenSourceValue.SECOND_PASS
                            },
                    )
                },
        )

    private data class ActiveModels(
        val vad: ActiveModel,
        val firstPass: ActiveModel?,
        val punctuation: ActiveModel?,
        val secondPass: ActiveModel?,
        val timelineAlignment: ActiveModel?,
        val realtimeAsrChoice: RealtimeAsrModelChoice,
        val offlineAsrQualityChoice: OfflineAsrQualityChoice,
        val realtimeSettings: LocalRealtimeAsrSettings,
        val performance: ResolvedSpeechPerformance,
        val senseVoiceSettings: LocalSenseVoiceSettings,
        val qwenSettings: LocalQwenAsrSettings,
        val vadSettings: LocalVadSettings,
    ) {
        val primaryAsrModelId: String?
            get() = secondPass?.descriptor?.modelId ?: firstPass?.descriptor?.modelId

        val all: List<ActiveModel>
            get() =
                listOfNotNull(vad, firstPass, punctuation, secondPass, timelineAlignment)
                    .distinctBy {
                        Triple(
                            it.descriptor.modelId,
                            it.descriptor.version,
                            it.descriptor.revision,
                        )
                    }
    }

    private data class CompletedPipeline(
        val segments: List<TranscriptSegment>,
        val warning: String?,
    )

    private fun configSnapshot(
        mode: TranscriptionMode,
        models: ActiveModels,
    ): String {
        val requested = requestedConfigSnapshot(mode, models)
        val effective = effectiveConfigSnapshot(mode, models)
        return "{\"schemaVersion\":2,\"requested\":$requested,\"effective\":$effective}"
    }

    private fun requestedConfigSnapshot(
        mode: TranscriptionMode,
        models: ActiveModels,
    ): String =
        buildString {
            append("{\"schemaVersion\":2,\"mode\":")
            appendJsonString(mode.name)
            append(",\"language\":")
            appendJsonString(models.effectiveLanguageConfig())
            append(",\"realtimeAsrModel\":")
            appendJsonString(models.realtimeAsrChoice.name)
            append(",\"offlineAsrQuality\":")
            appendJsonString(models.offlineAsrQualityChoice.name)
            append(",\"realtimeDecoder\":")
            appendRealtimeRequestedSnapshot(models.realtimeSettings)
            append(",\"performanceProfile\":")
            appendJsonString(models.performance.profile.name)
            append(",\"requestedThreads\":")
            val requestedThreads = models.performance.requestedThreads
            if (requestedThreads == null) append("null") else append(requestedThreads)
            if (
                mode == TranscriptionMode.HIGH_QUALITY &&
                models.offlineAsrQualityChoice == OfflineAsrQualityChoice.BALANCED
            ) {
                append(",\"senseVoice\":")
                appendSenseVoiceSnapshot(models.senseVoiceSettings)
            }
            if (
                mode == TranscriptionMode.HIGH_QUALITY &&
                models.offlineAsrQualityChoice == OfflineAsrQualityChoice.HIGH_QUALITY
            ) {
                append(",\"qwen\":")
                appendQwenSnapshot(models.qwenSettings)
            }
            append(",\"vad\":")
            appendVadSnapshot(models.vadSettings)
            append('}')
        }

    private fun effectiveConfigSnapshot(
        mode: TranscriptionMode,
        models: ActiveModels,
    ): String =
        buildString {
            append("{\"schemaVersion\":2,\"mode\":")
            appendJsonString(mode.name)
            append(",\"language\":")
            appendJsonString(models.effectiveLanguageConfig())
            append(",\"pipeline\":")
            appendJsonString(
                if (mode == TranscriptionMode.HIGH_QUALITY) {
                    "OFFLINE_DIRECT"
                } else {
                    "STREAMING_FIRST_PASS"
                },
            )
            append(",\"realtimeAsrModelId\":")
            val realtimeModel = models.firstPass
            if (realtimeModel == null) {
                append("null")
            } else {
                appendJsonString(realtimeModel.descriptor.modelId)
            }
            append(",\"realtimeAsrFallbackUsed\":false")
            append(",\"realtimeDecoder\":")
            if (realtimeModel == null) {
                append("null")
            } else {
                appendRealtimeEffectiveSnapshot(
                    models.realtimeSettings.resolveFor(
                        realtimeModel.descriptor.capabilities.supportedParameters,
                    ),
                )
            }
            append(",\"offlineAsrModelId\":")
            val offlineAsrModelId = models.secondPass?.descriptor?.modelId
            if (offlineAsrModelId == null) append("null") else appendJsonString(offlineAsrModelId)
            append(",\"offlineAsrQuality\":")
            appendJsonString(models.offlineAsrQualityChoice.name)
            append(",\"timelineAlignmentModelId\":")
            val timelineAlignmentModelId = models.timelineAlignment?.descriptor?.modelId
            if (timelineAlignmentModelId == null) {
                append("null")
            } else {
                appendJsonString(timelineAlignmentModelId)
            }
            append(",\"punctuationModelId\":")
            val punctuationModelId = models.punctuation?.descriptor?.modelId
            if (punctuationModelId == null) {
                append("null")
            } else {
                appendJsonString(punctuationModelId)
            }
            append(",\"performanceProfile\":")
            appendJsonString(models.performance.profile.name)
            append(",\"effectiveThreads\":")
            append(models.performance.effectiveThreads)
            append(",\"logicalProcessors\":")
            append(models.performance.logicalProcessors)
            if (offlineAsrModelId == Stage13AOfflineModelIds.SENSEVOICE) {
                append(",\"senseVoice\":")
                appendSenseVoiceSnapshot(models.senseVoiceSettings)
            }
            if (offlineAsrModelId == Stage13AOfflineModelIds.QWEN3_ASR) {
                append(",\"qwen\":")
                appendQwenSnapshot(models.qwenSettings)
            }
            append(",\"vad\":")
            appendVadSnapshot(models.vadSettings)
            append(",\"vadWindowSizeSamples\":512")
            append(",\"runtime\":{\"id\":")
            appendJsonString(SherpaRuntime.RUNTIME_ID)
            append(",\"version\":")
            appendJsonString(SherpaRuntime.RUNTIME_VERSION)
            append(",\"provider\":")
            appendJsonString(SherpaRuntime.PROVIDER_CPU)
            append("},\"models\":[")
            models.all.forEachIndexed { index, model ->
                if (index > 0) append(',')
                append("{\"id\":")
                appendJsonString(model.descriptor.modelId)
                append(",\"kind\":")
                appendJsonString(model.descriptor.kind.name)
                append(",\"version\":")
                appendJsonString(model.descriptor.version)
                append(",\"revision\":").append(model.descriptor.revision)
                append(",\"runtimeId\":")
                appendJsonString(model.descriptor.runtimeId)
                append(",\"runtimeModelType\":")
                val runtimeModelType = model.descriptor.runtimeModelType
                if (runtimeModelType == null) append("null") else appendJsonString(runtimeModelType)
                append(",\"quantization\":")
                val quantization = model.descriptor.quantization
                if (quantization == null) append("null") else appendJsonString(quantization)
                append(",\"manifestDigest\":")
                appendJsonString(model.manifestDigest)
                append('}')
            }
            append("]}")
        }

    private fun ActiveModels.effectiveLanguageConfig(): String =
        if (secondPass?.descriptor?.modelId == Stage13AOfflineModelIds.SENSEVOICE) {
            senseVoiceSettings.language.runtimeValue.ifBlank { "auto" }
        } else {
            "auto"
        }

    private fun StringBuilder.appendSenseVoiceSnapshot(settings: LocalSenseVoiceSettings) {
        append("{\"language\":")
        appendJsonString(settings.language.runtimeValue.ifBlank { "auto" })
        append(",\"useInverseTextNormalization\":")
            .append(settings.useInverseTextNormalization)
        append('}')
    }

    private fun StringBuilder.appendRealtimeRequestedSnapshot(
        settings: LocalRealtimeAsrSettings,
    ) {
        append("{\"decodingMethod\":")
        appendJsonString(settings.decodingMethod.runtimeValue)
        append(",\"maxActivePaths\":").append(settings.maxActivePaths)
        append('}')
    }

    private fun StringBuilder.appendRealtimeEffectiveSnapshot(
        settings: ResolvedRealtimeAsrSettings,
    ) {
        append("{\"decodingMethod\":")
        appendJsonString(settings.decodingMethod.runtimeValue)
        append(",\"maxActivePaths\":").append(settings.maxActivePaths)
        append('}')
    }

    private fun StringBuilder.appendQwenSnapshot(settings: LocalQwenAsrSettings) {
        append("{\"maxTotalLen\":").append(settings.maxTotalLen)
        append(",\"maxNewTokens\":").append(settings.maxNewTokens)
        append(",\"temperature\":").append(settings.temperature)
        append(",\"topP\":").append(settings.topP)
        append(",\"seed\":").append(settings.seed)
        append(",\"hotwords\":")
        appendJsonString(settings.hotwords)
        append('}')
    }

    private fun StringBuilder.appendVadSnapshot(settings: LocalVadSettings) {
        append("{\"threshold\":").append(settings.threshold)
        append(",\"minSilenceDurationSeconds\":").append(settings.minSilenceDurationSeconds)
        append(",\"minSpeechDurationSeconds\":").append(settings.minSpeechDurationSeconds)
        append(",\"maxSpeechDurationSeconds\":").append(settings.maxSpeechDurationSeconds)
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }

    private fun lineageDigest(models: List<ActiveModel>): String {
        val canonical =
            models.sortedBy { it.descriptor.modelId }
                .joinToString("\n") { model ->
                    model.descriptor.modelId + "|" +
                        model.descriptor.version + "|" +
                        model.descriptor.revision + "|" +
                        model.manifestDigest
                }
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
