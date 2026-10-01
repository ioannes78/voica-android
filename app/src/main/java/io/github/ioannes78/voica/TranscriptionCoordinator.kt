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
import io.github.ioannes78.voica.sherpa.SherpaCtTransformerPunctuationEngine
import io.github.ioannes78.voica.sherpa.SherpaSenseVoiceEngine
import io.github.ioannes78.voica.sherpa.SherpaSileroVadEngine
import io.github.ioannes78.voica.sherpa.SherpaStreamingZipformerEngine
import io.github.ioannes78.voica.sherpa.SherpaVadModelLocation
import io.github.ioannes78.voica.sherpa.SherpaRuntime
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
    fun vadFactory(model: ActiveModel): VadEngineFactory

    fun firstPassFactory(model: ActiveModel): StreamingAsrEngineFactory

    fun punctuationFactory(model: ActiveModel): PunctuationEngineFactory

    fun secondPassFactory(model: ActiveModel): SecondPassAsrEngineFactory
}

class SherpaStage8TranscriptionEngineProvider(
    private val assetManager: AssetManager,
) : Stage8TranscriptionEngineProvider {
    override fun vadFactory(model: ActiveModel): VadEngineFactory {
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
            )
        }
    }

    override fun firstPassFactory(model: ActiveModel): StreamingAsrEngineFactory {
        require(model.descriptor.kind == ModelKind.ASR_STREAMING)
        val directory =
            model.installedDirectory
                ?: error("first-pass ASR must be a managed installed model")
        return StreamingAsrEngineFactory {
            SherpaStreamingZipformerEngine(
                model = model.descriptor,
                modelDirectory = directory,
            )
        }
    }

    override fun punctuationFactory(model: ActiveModel): PunctuationEngineFactory {
        require(model.descriptor.kind == ModelKind.PUNCTUATION)
        val directory =
            model.installedDirectory
                ?: error("punctuation model must be a managed installed model")
        return PunctuationEngineFactory {
            SherpaCtTransformerPunctuationEngine(
                model = model.descriptor,
                modelDirectory = directory,
            )
        }
    }

    override fun secondPassFactory(model: ActiveModel): SecondPassAsrEngineFactory {
        require(model.descriptor.kind == ModelKind.ASR_SECOND_PASS)
        val directory =
            model.installedDirectory
                ?: error("second-pass ASR must be a managed installed model")
        return SecondPassAsrEngineFactory {
            SherpaSenseVoiceEngine(
                model = model.descriptor,
                modelDirectory = directory,
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
) {
    private val lock = Any()
    private var currentJob: Job? = null
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
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (currentJob === job) {
                        currentJob = null
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

    suspend fun reconcileOnStartup(): Int =
        transcriptionRepository.reconcileInterruptedOnStartup()

    private suspend fun runTranscription(
        recordingId: String,
        mode: TranscriptionMode,
    ) {
        var transcriptionId: String? = null
        var leases: List<ModelLease> = emptyList()

        try {
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
                                vadEngineFactory = engineProvider.vadFactory(models.vad),
                                asrEngineFactory = engineProvider.firstPassFactory(models.firstPass),
                                punctuationEngineFactory =
                                    engineProvider.punctuationFactory(models.punctuation),
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
                                vadEngineFactory = engineProvider.vadFactory(models.vad),
                                asrEngineFactory = engineProvider.firstPassFactory(models.firstPass),
                                secondPassAsrEngineFactory =
                                    engineProvider.secondPassFactory(secondPass),
                                punctuationEngineFactory =
                                    engineProvider.punctuationFactory(models.punctuation),
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
        val requiredIds =
            buildList {
                add(Stage8ModelIds.VAD)
                add(Stage8ModelIds.FIRST_PASS_ASR)
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
            firstPass = checkNotNull(active[Stage8ModelIds.FIRST_PASS_ASR]),
            punctuation = checkNotNull(active[Stage8ModelIds.PUNCTUATION]),
            secondPass =
                if (mode == TranscriptionMode.HIGH_QUALITY) {
                    checkNotNull(active[Stage8ModelIds.SECOND_PASS_ASR])
                } else {
                    null
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
            configSnapshot = configSnapshot(mode, modelList),
            modelManifestDigest = lineageDigest(modelList),
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
    ) {
        val all: List<ActiveModel>
            get() = listOfNotNull(vad, firstPass, punctuation, secondPass)
    }

    private data class CompletedPipeline(
        val segments: List<TranscriptSegment>,
        val warning: String?,
    )

    private fun configSnapshot(
        mode: TranscriptionMode,
        models: List<ActiveModel>,
    ): String =
        buildString {
            append("{\"mode\":\"").append(mode.name).append("\",\"models\":[")
            models.forEachIndexed { index, model ->
                if (index > 0) append(',')
                append("{\"id\":\"")
                    .append(model.descriptor.modelId)
                    .append("\",\"version\":\"")
                    .append(model.descriptor.version)
                    .append("\",\"revision\":")
                    .append(model.descriptor.revision)
                    .append(",\"manifestDigest\":\"")
                    .append(model.manifestDigest)
                    .append("\"}")
            }
            append("]}")
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
