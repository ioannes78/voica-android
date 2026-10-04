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
import io.github.ioannes78.voica.sherpa.PunctuationSettings
import io.github.ioannes78.voica.sherpa.SenseVoiceSettings
import io.github.ioannes78.voica.sherpa.SherpaCtTransformerPunctuationEngine
import io.github.ioannes78.voica.sherpa.SherpaSenseVoiceEngine
import io.github.ioannes78.voica.sherpa.SherpaSileroVadEngine
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.sherpa.SherpaVadModelLocation
import io.github.ioannes78.voica.sherpa.SileroVadSettings
import io.github.ioannes78.voica.sherpa.StreamingZipformerSettings
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
    ): VadEngineFactory

    fun firstPassFactory(
        model: ActiveModel,
        numThreads: Int,
    ): StreamingAsrEngineFactory

    fun punctuationFactory(
        model: ActiveModel,
        numThreads: Int,
    ): PunctuationEngineFactory

    fun secondPassFactory(
        model: ActiveModel,
        numThreads: Int,
    ): SecondPassAsrEngineFactory
}

class SherpaStage8TranscriptionEngineProvider(
    private val assetManager: AssetManager,
) : Stage8TranscriptionEngineProvider {
    override fun vadFactory(
        model: ActiveModel,
        numThreads: Int,
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
                settings = SileroVadSettings(numThreads = numThreads),
            )
        }
    }

    override fun firstPassFactory(
        model: ActiveModel,
        numThreads: Int,
    ): StreamingAsrEngineFactory {
        require(model.descriptor.kind == ModelKind.ASR_STREAMING)
        val directory =
            model.installedDirectory
                ?: error("first-pass ASR must be a managed installed model")
        return StreamingAsrEngineFactory {
            createSherpaStreamingAsrEngine(
                model = model.descriptor,
                modelDirectory = directory,
                settings = StreamingZipformerSettings(numThreads = numThreads),
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
    ): SecondPassAsrEngineFactory {
        require(model.descriptor.kind == ModelKind.ASR_SECOND_PASS)
        val directory =
            model.installedDirectory
                ?: error("second-pass ASR must be a managed installed model")
        return SecondPassAsrEngineFactory {
            SherpaSenseVoiceEngine(
                model = model.descriptor,
                modelDirectory = directory,
                settings = SenseVoiceSettings(numThreads = numThreads),
            )
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

        try {
            if (!isRecordingActive(recordingId)) return

            mutableState.value =
                TranscriptionRunState.Running(
                    recordingId = recordingId,
                    transcriptionId = null,
                    mode = mode,
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

            val models = resolveActiveModels(mode)
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
                                    ),
                                asrEngineFactory =
                                    engineProvider.firstPassFactory(
                                        models.firstPass,
                                        models.performance.effectiveThreads,
                                    ),
                                punctuationEngineFactory =
                                    engineProvider.punctuationFactory(
                                        models.punctuation,
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
                                ?: error("high-quality mode is missing second-pass ASR")
                        val result =
                            HighQualityTranscriptionPipeline(
                                pcmSourceResolver = pcmSourceResolver,
                                vadEngineFactory =
                                    engineProvider.vadFactory(
                                        models.vad,
                                        models.performance.effectiveThreads,
                                    ),
                                asrEngineFactory =
                                    engineProvider.firstPassFactory(
                                        models.firstPass,
                                        models.performance.effectiveThreads,
                                    ),
                                secondPassAsrEngineFactory =
                                    engineProvider.secondPassFactory(
                                        secondPass,
                                        models.performance.effectiveThreads,
                                    ),
                                punctuationEngineFactory =
                                    engineProvider.punctuationFactory(
                                        models.punctuation,
                                        models.performance.effectiveThreads,
                                    ),
                            ).transcribe(
                                recordingId = recordingId,
                                progressListener = progressListener,
                            )
                        CompletedPipeline(
                            segments = result.transcriptSegments,
                            warning =
                                result.secondPassFallbackError
                                    ?: result.punctuationFallbackError,
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

    private suspend fun resolveActiveModels(mode: TranscriptionMode): ActiveModels {
        val settings = localSpeechSettings()
        val performance = settings.resolvePerformance()
        val firstPassSelection = resolveRealtimeAsr(settings.realtimeAsrModel)
        val requiredIds =
            buildList {
                add(Stage8ModelIds.VAD)
                add(Stage8ModelIds.PUNCTUATION)
                if (mode == TranscriptionMode.HIGH_QUALITY) {
                    add(Stage8ModelIds.SECOND_PASS_ASR)
                }
            }
        val active =
            requiredIds.associateWith { id ->
                modelManager.activeModel(id)
            }
        val missing =
            active.filterValues { it == null }
                .keys
                .sorted()
        if (missing.isNotEmpty()) {
            throw MissingTranscriptionModelsException(missing)
        }
        return ActiveModels(
            vad = checkNotNull(active[Stage8ModelIds.VAD]),
            firstPass = firstPassSelection.model,
            punctuation = checkNotNull(active[Stage8ModelIds.PUNCTUATION]),
            secondPass =
                if (mode == TranscriptionMode.HIGH_QUALITY) {
                    checkNotNull(active[Stage8ModelIds.SECOND_PASS_ASR])
                } else {
                    null
                },
            realtimeAsrChoice = settings.realtimeAsrModel,
            realtimeAsrFallbackUsed = firstPassSelection.fallbackUsed,
            performance = performance,
        )
    }

    private suspend fun resolveRealtimeAsr(
        choice: RealtimeAsrModelChoice,
    ): ResolvedRealtimeAsr {
        val candidates = choice.preferredModelIds()
        for ((index, modelId) in candidates.withIndex()) {
            val model = modelManager.activeModel(modelId) ?: continue
            require(model.descriptor.kind == ModelKind.ASR_STREAMING) {
                "selected realtime ASR is not ASR_STREAMING: $modelId"
            }
            return ResolvedRealtimeAsr(
                model = model,
                fallbackUsed = choice == RealtimeAsrModelChoice.AUTO && index > 0,
            )
        }

        throw MissingTranscriptionModelsException(
            if (choice == RealtimeAsrModelChoice.AUTO) {
                Stage13ARealtimeModelIds.ALL
            } else {
                candidates
            },
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
            pipelineVersion = 1,
            runtimeId = SherpaRuntime.RUNTIME_ID,
            runtimeVersion = SherpaRuntime.RUNTIME_VERSION,
            vadModelId = models.vad.descriptor.modelId,
            vadModelVersion = models.vad.descriptor.version,
            firstPassAsrModelId = models.firstPass.descriptor.modelId,
            firstPassAsrModelVersion = models.firstPass.descriptor.version,
            secondPassAsrModelId = models.secondPass?.descriptor?.modelId,
            secondPassAsrModelVersion = models.secondPass?.descriptor?.version,
            punctuationModelId = models.punctuation.descriptor.modelId,
            punctuationModelVersion = models.punctuation.descriptor.version,
            languageConfig = "auto",
            configSnapshot = configSnapshot(mode, models),
            modelManifestDigest = lineageDigest(modelList),
            vadModelRevision = models.vad.descriptor.revision,
            firstPassAsrModelRevision = models.firstPass.descriptor.revision,
            secondPassAsrModelRevision = models.secondPass?.descriptor?.revision,
            punctuationModelRevision = models.punctuation.descriptor.revision,
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
        val firstPass: ActiveModel,
        val punctuation: ActiveModel,
        val secondPass: ActiveModel?,
        val realtimeAsrChoice: RealtimeAsrModelChoice,
        val realtimeAsrFallbackUsed: Boolean,
        val performance: ResolvedSpeechPerformance,
    ) {
        val all: List<ActiveModel>
            get() = listOfNotNull(vad, firstPass, punctuation, secondPass)
    }

    private data class ResolvedRealtimeAsr(
        val model: ActiveModel,
        val fallbackUsed: Boolean,
    )

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
            append(",\"language\":\"auto\"")
            append(",\"realtimeAsrModel\":")
            appendJsonString(models.realtimeAsrChoice.name)
            append(",\"performanceProfile\":")
            appendJsonString(models.performance.profile.name)
            append(",\"requestedThreads\":")
            val requestedThreads = models.performance.requestedThreads
            if (requestedThreads == null) append("null") else append(requestedThreads)
            append('}')
        }

    private fun effectiveConfigSnapshot(
        mode: TranscriptionMode,
        models: ActiveModels,
    ): String =
        buildString {
            append("{\"schemaVersion\":2,\"mode\":")
            appendJsonString(mode.name)
            append(",\"language\":\"auto\"")
            append(",\"realtimeAsrModelId\":")
            appendJsonString(models.firstPass.descriptor.modelId)
            append(",\"realtimeAsrFallbackUsed\":")
            append(models.realtimeAsrFallbackUsed)
            append(",\"performanceProfile\":")
            appendJsonString(models.performance.profile.name)
            append(",\"effectiveThreads\":")
            append(models.performance.effectiveThreads)
            append(",\"logicalProcessors\":")
            append(models.performance.logicalProcessors)
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
