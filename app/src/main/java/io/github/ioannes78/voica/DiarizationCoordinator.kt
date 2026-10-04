package io.github.ioannes78.voica

import android.content.res.AssetManager
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.database.CanonicalTranscriptionLineage
import io.github.ioannes78.voica.database.DiarizationRepository
import io.github.ioannes78.voica.database.DiarizationStateValue
import io.github.ioannes78.voica.database.NewDiarizationRunRequest
import io.github.ioannes78.voica.database.NewTranscriptSpeakerAlignmentRequest
import io.github.ioannes78.voica.database.SpeakerAssignmentQualityValue
import io.github.ioannes78.voica.database.SpeakerTurnWrite
import io.github.ioannes78.voica.database.TranscriptSpeakerAlignmentStateValue
import io.github.ioannes78.voica.database.TranscriptSpeakerSpanWrite
import io.github.ioannes78.voica.database.TranscriptTokenSourceValue
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelLease
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.model.SpeakerModelRole
import io.github.ioannes78.voica.sherpa.SherpaDiarizationBundleValidator
import io.github.ioannes78.voica.sherpa.SherpaDiarizationSettings
import io.github.ioannes78.voica.sherpa.SherpaOfflineDiarizationEngine
import io.github.ioannes78.voica.sherpa.SherpaRuntime
import io.github.ioannes78.voica.sherpa.SherpaSpeakerEmbeddingEngine
import io.github.ioannes78.voica.transcript.DiarizationChunkStitchInput
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.DiarizationEngine
import io.github.ioannes78.voica.transcript.DiarizationPhase
import io.github.ioannes78.voica.transcript.DiarizationProgress
import io.github.ioannes78.voica.transcript.GlobalSpeakerTurn
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SpeakerAlignedTextSpan
import io.github.ioannes78.voica.transcript.SpeakerAnchorEmbedding
import io.github.ioannes78.voica.transcript.SpeakerAssignmentQuality
import io.github.ioannes78.voica.transcript.SpeakerEmbeddingEngine
import io.github.ioannes78.voica.transcript.TokenSource
import io.github.ioannes78.voica.transcript.TranscriptSegment
import io.github.ioannes78.voica.transcript.TranscriptToken
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.transcript.VadEngineFactory
import io.github.ioannes78.voica.transcript.alignTranscriptSegmentsToSpeakers
import io.github.ioannes78.voica.transcript.consumeDiarizationWindowsSequentially
import io.github.ioannes78.voica.transcript.planDiarizationWindows
import io.github.ioannes78.voica.transcript.stitchDiarizationChunks
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Stage9ModelIds {
    const val SEGMENTATION = "pyannote-segmentation-3-int8"
    const val EMBEDDING = "3dspeaker-eres2net-base-zh-cn-16k"
}

sealed interface DiarizationRunState {
    data object Idle : DiarizationRunState

    data class Running(
        val recordingId: String,
        val runId: String?,
        val progress: DiarizationProgress,
    ) : DiarizationRunState

    data class Completed(
        val recordingId: String,
        val runId: String,
        val speakerCount: Int,
        val turns: List<GlobalSpeakerTurn>,
    ) : DiarizationRunState

    data class Failed(
        val recordingId: String,
        val message: String,
        val missingModelIds: List<String> = emptyList(),
    ) : DiarizationRunState

    data class Cancelled(
        val recordingId: String,
    ) : DiarizationRunState
}

sealed interface SpeakerAlignmentRunState {
    data object Idle : SpeakerAlignmentRunState

    data class Running(
        val transcriptionId: String,
        val diarizationRunId: String,
        val alignmentId: String?,
    ) : SpeakerAlignmentRunState

    data class Completed(
        val transcriptionId: String,
        val diarizationRunId: String,
        val alignmentId: String,
        val spanCount: Int,
    ) : SpeakerAlignmentRunState

    data class Failed(
        val transcriptionId: String,
        val diarizationRunId: String,
        val message: String,
    ) : SpeakerAlignmentRunState

    data class Cancelled(
        val transcriptionId: String,
        val diarizationRunId: String,
    ) : SpeakerAlignmentRunState
}

class MissingDiarizationModelsException(
    val modelIds: List<String>,
) : IllegalStateException(
    "missing active diarization models: " + modelIds.joinToString(),
)

interface Stage9DiarizationEngineProvider {
    fun vadFactory(
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings,
    ): VadEngineFactory

    suspend fun validateBundle(
        segmentation: ActiveModel,
        embedding: ActiveModel,
    )

    fun diarizationEngine(
        segmentation: ActiveModel,
        embedding: ActiveModel,
        numThreads: Int,
    ): DiarizationEngine

    fun embeddingEngine(
        model: ActiveModel,
        numThreads: Int,
    ): SpeakerEmbeddingEngine
}

class SherpaStage9DiarizationEngineProvider(
    assetManager: AssetManager,
) : Stage9DiarizationEngineProvider {
    private val stage8Provider = SherpaStage8TranscriptionEngineProvider(assetManager)
    private val bundleValidator = SherpaDiarizationBundleValidator()
    private val validatedBundles = ConcurrentHashMap.newKeySet<String>()

    override fun vadFactory(
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings,
    ): VadEngineFactory =
        stage8Provider.vadFactory(
            model = model,
            numThreads = numThreads,
            vadSettings = vadSettings,
        )

    override suspend fun validateBundle(
        segmentation: ActiveModel,
        embedding: ActiveModel,
    ) {
        val segmentationDirectory =
            segmentation.installedDirectory
                ?: error("speaker segmentation must be a managed installed model")
        val embeddingDirectory =
            embedding.installedDirectory
                ?: error("speaker embedding must be a managed installed model")
        val key =
            listOf(
                segmentation.descriptor.modelId,
                segmentation.descriptor.version,
                segmentation.descriptor.revision.toString(),
                embedding.descriptor.modelId,
                embedding.descriptor.version,
                embedding.descriptor.revision.toString(),
            ).joinToString("|")
        if (key in validatedBundles) return

        bundleValidator.validate(
            segmentationModel = segmentation.descriptor,
            segmentationDirectory = segmentationDirectory,
            embeddingModel = embedding.descriptor,
            embeddingDirectory = embeddingDirectory,
        )
        validatedBundles += key
    }

    override fun diarizationEngine(
        segmentation: ActiveModel,
        embedding: ActiveModel,
        numThreads: Int,
    ): DiarizationEngine {
        val segmentationDirectory =
            segmentation.installedDirectory
                ?: error("speaker segmentation must be a managed installed model")
        val embeddingDirectory =
            embedding.installedDirectory
                ?: error("speaker embedding must be a managed installed model")
        return SherpaOfflineDiarizationEngine(
            segmentationModel = segmentation.descriptor,
            segmentationModelDirectory = segmentationDirectory,
            embeddingModel = embedding.descriptor,
            embeddingModelDirectory = embeddingDirectory,
            settings = SherpaDiarizationSettings(numThreads = numThreads),
        )
    }

    override fun embeddingEngine(
        model: ActiveModel,
        numThreads: Int,
    ): SpeakerEmbeddingEngine {
        val directory =
            model.installedDirectory
                ?: error("speaker embedding must be a managed installed model")
        return SherpaSpeakerEmbeddingEngine(
            model = model.descriptor,
            modelDirectory = directory,
            numThreads = numThreads,
        )
    }
}

class DiarizationCoordinator(
    private val scope: CoroutineScope,
    private val pcmSourceResolver: PcmSourceResolver,
    private val diarizationRepository: DiarizationRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val loadCanonicalLineage:
        suspend (recordingId: String, profileId: String) -> CanonicalTranscriptionLineage?,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val engineProvider: Stage9DiarizationEngineProvider,
    private val localSpeechSettings: () -> LocalSpeechSettings = { LocalSpeechSettings() },
    private val isRecordingActive: suspend (String) -> Boolean = { true },
) {
    private val lock = Any()
    private var currentJob: Job? = null
    private var currentTarget: OperationTarget? = null

    private sealed interface OperationTarget {
        data class Recording(val recordingId: String) : OperationTarget
        data class Alignment(
            val transcriptionId: String,
            val diarizationRunId: String,
        ) : OperationTarget
    }

    private val mutableState =
        MutableStateFlow<DiarizationRunState>(DiarizationRunState.Idle)
    val state: StateFlow<DiarizationRunState> = mutableState.asStateFlow()

    private val mutableAlignmentState =
        MutableStateFlow<SpeakerAlignmentRunState>(SpeakerAlignmentRunState.Idle)
    val alignmentState: StateFlow<SpeakerAlignmentRunState> =
        mutableAlignmentState.asStateFlow()

    fun start(
        recordingId: String,
        config: DiarizationConfig? = null,
    ): Boolean {
        require(recordingId.isNotBlank())
        synchronized(lock) {
            if (currentJob?.isActive == true) return false
            val speechSettings = localSpeechSettings()
            val requestedSpeakerCount =
                if (config == null) speechSettings.speakerCount else null
            val effectiveConfig =
                config ?: speechSettings.speakerCount.toDiarizationConfig()
            val job =
                scope.launch {
                    runDiarization(
                        recordingId = recordingId,
                        config = effectiveConfig,
                        speechSettings = speechSettings,
                        requestedSpeakerCount = requestedSpeakerCount,
                    )
                }
            installCurrentJob(job, OperationTarget.Recording(recordingId))
            return true
        }
    }

    fun alignTranscription(
        transcriptionId: String,
        diarizationRunId: String,
    ): Boolean {
        require(transcriptionId.isNotBlank())
        require(diarizationRunId.isNotBlank())
        synchronized(lock) {
            if (currentJob?.isActive == true) return false
            val job =
                scope.launch {
                    runAlignment(
                        transcriptionId = transcriptionId,
                        diarizationRunId = diarizationRunId,
                    )
                }
            installCurrentJob(
                job,
                OperationTarget.Alignment(
                    transcriptionId = transcriptionId,
                    diarizationRunId = diarizationRunId,
                ),
            )
            return true
        }
    }

    fun cancel() {
        synchronized(lock) {
            currentJob?.cancel(CancellationException("user cancelled Stage 9 operation"))
        }
    }

    suspend fun cancelAndAwait(recordingId: String) {
        val snapshot =
            synchronized(lock) {
                currentJob to currentTarget
            }
        val job = snapshot.first ?: return
        val target = snapshot.second ?: return
        val matches =
            when (target) {
                is OperationTarget.Recording ->
                    target.recordingId == recordingId

                is OperationTarget.Alignment -> {
                    val transcriptionRecordingId =
                        transcriptionRepository.find(target.transcriptionId)?.recordingId
                    val diarizationRecordingId =
                        diarizationRepository.findRun(target.diarizationRunId)?.recordingId
                    transcriptionRecordingId == recordingId ||
                        diarizationRecordingId == recordingId
                }
            }
        if (!matches) return
        job.cancel(CancellationException("recording deletion"))
        runCatching { job.join() }
    }

    suspend fun reconcileOnStartup() =
        diarizationRepository.reconcileInterruptedOnStartup()

    private fun installCurrentJob(
        job: Job,
        target: OperationTarget,
    ) {
        currentJob = job
        currentTarget = target
        job.invokeOnCompletion {
            synchronized(lock) {
                if (currentJob === job) {
                    currentJob = null
                    currentTarget = null
                }
            }
        }
    }

    private suspend fun releaseOperationSlot() {
        val job = currentCoroutineContext()[Job] ?: return
        synchronized(lock) {
            if (currentJob === job) {
                currentJob = null
                currentTarget = null
            }
        }
    }

    private suspend fun runDiarization(
        recordingId: String,
        config: DiarizationConfig,
        speechSettings: LocalSpeechSettings,
        requestedSpeakerCount: SpeakerCountChoice?,
    ) {
        var runId: String? = null
        var leases: List<ModelLease> = emptyList()

        try {
            if (!isRecordingActive(recordingId)) return

            mutableState.value =
                DiarizationRunState.Running(
                    recordingId = recordingId,
                    runId = null,
                    progress = DiarizationProgress(DiarizationPhase.PREPARING),
                )

            val lineage =
                loadCanonicalLineage(
                    recordingId,
                    CanonicalPcmProfile.PROFILE_ID,
                ) ?: error("verified canonical PCM is not ready")

            val totalSampleCount =
                pcmSourceResolver.resolvePcmSource(recordingId)?.use { source ->
                    require(source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ)
                    require(source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT)
                    source.totalSampleCount
                } ?: error("canonical PCM source is unavailable")

            val models = resolveActiveModels()
            val performance = speechSettings.resolvePerformance()
            leases =
                models.all.map { active ->
                    modelUseRegistry.acquire(
                        modelId = active.descriptor.modelId,
                        version = active.descriptor.version,
                        revision = active.descriptor.revision,
                    )
                }

            engineProvider.validateBundle(
                segmentation = models.segmentation,
                embedding = models.embedding,
            )

            if (!isRecordingActive(recordingId)) {
                throw CancellationException("recording is being deleted")
            }

            val createdRunId =
                diarizationRepository.createRun(
                    NewDiarizationRunRequest(
                        recordingId = recordingId,
                        sourceCanonicalAssetId = lineage.canonicalAssetId,
                        sourceCanonicalSha256 = lineage.canonicalSha256,
                        canonicalProfileId = lineage.canonicalProfileId,
                        totalSampleCount = totalSampleCount,
                        pipelineVersion = DIARIZATION_PIPELINE_VERSION,
                        runtimeId = SherpaRuntime.RUNTIME_ID,
                        runtimeVersion = SherpaRuntime.RUNTIME_VERSION,
                        vadModelId = models.vad.descriptor.modelId,
                        vadModelVersion = models.vad.descriptor.version,
                        vadModelRevision = models.vad.descriptor.revision,
                        segmentationModelId = models.segmentation.descriptor.modelId,
                        segmentationModelVersion = models.segmentation.descriptor.version,
                        segmentationModelRevision = models.segmentation.descriptor.revision,
                        embeddingModelId = models.embedding.descriptor.modelId,
                        embeddingModelVersion = models.embedding.descriptor.version,
                        embeddingModelRevision = models.embedding.descriptor.revision,
                        modelManifestDigest = lineageDigest(models.all),
                        configSnapshot =
                            configSnapshot(
                                config = config,
                                models = models.all,
                                vadSettings = speechSettings.vad,
                                performance = performance,
                                requestedSpeakerCount = requestedSpeakerCount,
                            ),
                    ),
                )
            runId = createdRunId

            val speechSegments =
                runVad(
                    recordingId = recordingId,
                    runId = createdRunId,
                    model = models.vad,
                    numThreads = performance.effectiveThreads,
                    vadSettings = speechSettings.vad,
                )

            val ranges =
                planDiarizationWindows(
                    speechSegments = speechSegments,
                    totalSampleCount = totalSampleCount,
                    config = config,
                )

            if (ranges.isEmpty()) {
                publishProgress(
                    recordingId = recordingId,
                    runId = createdRunId,
                    phase = DiarizationPhase.PERSISTING,
                )
                diarizationRepository.persistCompletedRun(
                    runId = createdRunId,
                    speakerCount = 0,
                    turns = emptyList(),
                )
                leases = releaseLeases(leases)
                releaseOperationSlot()
                mutableState.value =
                    DiarizationRunState.Completed(
                        recordingId = recordingId,
                        runId = createdRunId,
                        speakerCount = 0,
                        turns = emptyList(),
                    )
                return
            }

            diarizationRepository.transitionRun(
                createdRunId,
                DiarizationStateValue.DIARIZING,
            )
            val chunks =
                runWindowedDiarization(
                    recordingId = recordingId,
                    runId = createdRunId,
                    ranges = ranges,
                    totalSampleCount = totalSampleCount,
                    models = models,
                    config = config,
                    numThreads = performance.effectiveThreads,
                )

            currentCoroutineContext().ensureActive()
            diarizationRepository.transitionRun(
                createdRunId,
                DiarizationStateValue.STITCHING,
            )
            publishProgress(
                recordingId = recordingId,
                runId = createdRunId,
                phase = DiarizationPhase.STITCHING,
            )
            val stitched = stitchDiarizationChunks(chunks, config)

            currentCoroutineContext().ensureActive()
            publishProgress(
                recordingId = recordingId,
                runId = createdRunId,
                phase = DiarizationPhase.PERSISTING,
            )
            diarizationRepository.persistCompletedRun(
                runId = createdRunId,
                speakerCount = stitched.speakerCount,
                turns =
                    stitched.turns.map { turn ->
                        SpeakerTurnWrite(
                            speakerIndex = turn.globalSpeakerIndex,
                            startSampleIndex = turn.startSampleIndex,
                            endSampleIndexExclusive = turn.endSampleIndexExclusive,
                            confidence = turn.confidence,
                            overlap = turn.overlap,
                        )
                    },
            )

            leases = releaseLeases(leases)
            releaseOperationSlot()
            mutableState.value =
                DiarizationRunState.Completed(
                    recordingId = recordingId,
                    runId = createdRunId,
                    speakerCount = stitched.speakerCount,
                    turns = stitched.turns,
                )
        } catch (cancelled: CancellationException) {
            runId?.let { id ->
                withContext(NonCancellable) {
                    runCatching { diarizationRepository.cancelRun(id) }
                }
            }
            leases = releaseLeases(leases)
            releaseOperationSlot()
            mutableState.value =
                DiarizationRunState.Cancelled(recordingId)
        } catch (missing: MissingDiarizationModelsException) {
            leases = releaseLeases(leases)
            releaseOperationSlot()
            mutableState.value =
                DiarizationRunState.Failed(
                    recordingId = recordingId,
                    message = "请先在设置中下载并启用说话人分离模型",
                    missingModelIds = missing.modelIds,
                )
        } catch (error: Throwable) {
            runId?.let { id ->
                withContext(NonCancellable) {
                    runCatching {
                        diarizationRepository.failRunRecoverable(
                            runId = id,
                            errorCode = "DIARIZATION_FAILED",
                            errorMessage = error.message,
                        )
                    }
                }
            }
            leases = releaseLeases(leases)
            releaseOperationSlot()
            mutableState.value =
                DiarizationRunState.Failed(
                    recordingId = recordingId,
                    message = error.message ?: error::class.java.simpleName,
                )
        } finally {
            releaseLeases(leases)
        }
    }

    private suspend fun runVad(
        recordingId: String,
        runId: String,
        model: ActiveModel,
        numThreads: Int,
        vadSettings: LocalVadSettings,
    ) =
        engineProvider.vadFactory(
            model = model,
            numThreads = numThreads,
            vadSettings = vadSettings,
        ).open().use { vad ->
            diarizationRepository.transitionRun(
                runId,
                DiarizationStateValue.VAD_ANALYZING,
            )
            val source =
                pcmSourceResolver.resolvePcmSource(recordingId)
                    ?: error("canonical PCM source disappeared before VAD")
            source.use {
                vad.analyze(
                    source = it,
                    progressListener =
                        ProgressListener { progress ->
                            require(progress.phase == TranscriptionPhase.VAD)
                            mutableState.value =
                                DiarizationRunState.Running(
                                    recordingId = recordingId,
                                    runId = runId,
                                    progress =
                                        DiarizationProgress(
                                            phase = DiarizationPhase.VAD,
                                            processedSamples = progress.processedUnits,
                                            totalSamples = progress.totalUnits,
                                        ),
                                )
                        },
                )
            }
        }

    private suspend fun runWindowedDiarization(
        recordingId: String,
        runId: String,
        ranges: List<io.github.ioannes78.voica.transcript.DiarizationWindowRange>,
        totalSampleCount: Long,
        models: ActiveDiarizationModels,
        config: DiarizationConfig,
        numThreads: Int,
    ): List<DiarizationChunkStitchInput> {
        val totalWindowSamples =
            ranges.fold(0L) { total, range ->
                Math.addExact(total, range.sampleCount)
            }
        var processedWindowSamples = 0L
        val chunks = ArrayList<DiarizationChunkStitchInput>(ranges.size)

        val source =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source disappeared before diarization")
        require(source.totalSampleCount == totalSampleCount) {
            "canonical PCM sample count changed during diarization"
        }

        val diarizationEngine =
            engineProvider.diarizationEngine(
                segmentation = models.segmentation,
                embedding = models.embedding,
                numThreads = numThreads,
            )
        val embeddingEngine =
            engineProvider.embeddingEngine(
                model = models.embedding,
                numThreads = numThreads,
            )

        try {
            source.use {
                consumeDiarizationWindowsSequentially(
                    source = it,
                    ranges = ranges,
                ) { index, window ->
                    currentCoroutineContext().ensureActive()
                    val result =
                        diarizationEngine.diarize(
                            window = window,
                            config = config,
                        )
                    val anchors =
                        buildSpeakerAnchors(
                            windowStartSampleIndex = window.startSampleIndex,
                            windowSamples = window.samples,
                            turns = result.turns,
                            embeddingEngine = embeddingEngine,
                            config = config,
                        )
                    chunks +=
                        DiarizationChunkStitchInput(
                            chunkIndex = index,
                            turns = result.turns,
                            anchors = anchors,
                        )
                    processedWindowSamples =
                        Math.addExact(
                            processedWindowSamples,
                            ranges[index].sampleCount,
                        )
                    mutableState.value =
                        DiarizationRunState.Running(
                            recordingId = recordingId,
                            runId = runId,
                            progress =
                                DiarizationProgress(
                                    phase = DiarizationPhase.DIARIZATION,
                                    processedSamples = processedWindowSamples,
                                    totalSamples = totalWindowSamples,
                                ),
                        )
                }
            }
        } finally {
            runCatching { embeddingEngine.close() }
            runCatching { diarizationEngine.close() }
        }
        return chunks
    }

    private suspend fun buildSpeakerAnchors(
        windowStartSampleIndex: Long,
        windowSamples: ShortArray,
        turns: List<io.github.ioannes78.voica.transcript.DiarizationSpeakerTurn>,
        embeddingEngine: SpeakerEmbeddingEngine,
        config: DiarizationConfig,
    ): List<SpeakerAnchorEmbedding> {
        val windowEndSampleIndex =
            Math.addExact(windowStartSampleIndex, windowSamples.size.toLong())

        return turns
            .filter { !it.overlap }
            .groupBy { it.speakerIndex }
            .entries
            .sortedBy { it.key }
            .mapNotNull { entry ->
                val ranges =
                    entry.value
                        .mapNotNull { turn ->
                            val start =
                                maxOf(turn.startSampleIndex, windowStartSampleIndex)
                            val end =
                                minOf(turn.endSampleIndexExclusive, windowEndSampleIndex)
                            if (end > start) {
                                AnchorRange(
                                    speakerIndex = entry.key,
                                    startSampleIndex = start,
                                    endSampleIndexExclusive = end,
                                )
                            } else {
                                null
                            }
                        }
                        .filter { it.sampleCount >= config.stitchingMinimumAnchorSamples }
                        .sortedByDescending { it.sampleCount }
                        .take(config.stitchingMaxAnchorsPerSpeaker)

                if (ranges.isEmpty()) {
                    return@mapNotNull null
                }

                var totalWeight = 0L
                var weightedEmbedding: DoubleArray? = null
                ranges.forEach { range ->
                    val startOffset =
                        Math.toIntExact(range.startSampleIndex - windowStartSampleIndex)
                    val endOffset =
                        Math.toIntExact(range.endSampleIndexExclusive - windowStartSampleIndex)
                    val normalized =
                        normalizeSpeakerEmbedding(
                            embeddingEngine.embed(
                                samples = windowSamples.copyOfRange(startOffset, endOffset),
                                sampleRateHz = config.sampleRateHz,
                            ),
                        )
                    val accumulator =
                        weightedEmbedding ?: DoubleArray(normalized.size).also {
                            weightedEmbedding = it
                        }
                    require(accumulator.size == normalized.size) {
                        "speaker embedding dimension changed while aggregating anchors"
                    }
                    normalized.indices.forEach { index ->
                        accumulator[index] +=
                            normalized[index].toDouble() * range.sampleCount.toDouble()
                    }
                    totalWeight = Math.addExact(totalWeight, range.sampleCount)
                }

                val combined =
                    checkNotNull(weightedEmbedding) {
                        "speaker anchor aggregation produced no embedding"
                    }
                SpeakerAnchorEmbedding(
                    localSpeakerIndex = entry.key,
                    embedding = FloatArray(combined.size) { index -> combined[index].toFloat() },
                    anchorSampleCount = totalWeight,
                )
            }
    }

    private suspend fun runAlignment(
        transcriptionId: String,
        diarizationRunId: String,
    ) {
        var alignmentId: String? = null
        try {
            val transcription =
                transcriptionRepository.find(transcriptionId)
                    ?: error("transcription not found")
            val diarizationRun =
                diarizationRepository.findRun(diarizationRunId)
                    ?: error("diarization run not found")
            require(transcription.recordingId == diarizationRun.recordingId) {
                "alignment inputs belong to different recordings"
            }
            if (!isRecordingActive(transcription.recordingId)) return

            mutableAlignmentState.value =
                SpeakerAlignmentRunState.Running(
                    transcriptionId = transcriptionId,
                    diarizationRunId = diarizationRunId,
                    alignmentId = null,
                )

            if (!isRecordingActive(transcription.recordingId)) {
                throw CancellationException("recording is being deleted")
            }

            val createdAlignmentId =
                diarizationRepository.createAlignment(
                    NewTranscriptSpeakerAlignmentRequest(
                        transcriptionId = transcriptionId,
                        diarizationRunId = diarizationRunId,
                        alignmentVersion = ALIGNMENT_VERSION,
                        configSnapshot =
                            "{\"algorithm\":\"token-interval-v1\",\"overlap\":\"ambiguous\"}",
                    ),
                )
            alignmentId = createdAlignmentId
            diarizationRepository.transitionAlignment(
                createdAlignmentId,
                TranscriptSpeakerAlignmentStateValue.ALIGNING,
            )
            mutableAlignmentState.value =
                SpeakerAlignmentRunState.Running(
                    transcriptionId = transcriptionId,
                    diarizationRunId = diarizationRunId,
                    alignmentId = createdAlignmentId,
                )

            val transcriptSegments = loadTranscriptDomain(transcriptionId)
            val globalTurns = loadGlobalSpeakerTurns(diarizationRunId)
            currentCoroutineContext().ensureActive()
            val result =
                alignTranscriptSegmentsToSpeakers(
                    segments = transcriptSegments,
                    speakerTurns = globalTurns,
                )

            currentCoroutineContext().ensureActive()
            diarizationRepository.persistCompletedAlignment(
                alignmentId = createdAlignmentId,
                spans = result.spans.map(::toSpanWrite),
            )

            releaseOperationSlot()
            mutableAlignmentState.value =
                SpeakerAlignmentRunState.Completed(
                    transcriptionId = transcriptionId,
                    diarizationRunId = diarizationRunId,
                    alignmentId = createdAlignmentId,
                    spanCount = result.spans.size,
                )
        } catch (cancelled: CancellationException) {
            alignmentId?.let { id ->
                withContext(NonCancellable) {
                    runCatching { diarizationRepository.cancelAlignment(id) }
                }
            }
            releaseOperationSlot()
            mutableAlignmentState.value =
                SpeakerAlignmentRunState.Cancelled(
                    transcriptionId = transcriptionId,
                    diarizationRunId = diarizationRunId,
                )
        } catch (error: Throwable) {
            alignmentId?.let { id ->
                withContext(NonCancellable) {
                    runCatching {
                        diarizationRepository.failAlignmentRecoverable(
                            alignmentId = id,
                            errorCode = "SPEAKER_ALIGNMENT_FAILED",
                            errorMessage = error.message,
                        )
                    }
                }
            }
            releaseOperationSlot()
            mutableAlignmentState.value =
                SpeakerAlignmentRunState.Failed(
                    transcriptionId = transcriptionId,
                    diarizationRunId = diarizationRunId,
                    message = error.message ?: error::class.java.simpleName,
                )
        }
    }

    private suspend fun loadTranscriptDomain(
        transcriptionId: String,
    ): List<TranscriptSegment> =
        transcriptionRepository.loadSegments(transcriptionId).map { segment ->
            val tokens =
                transcriptionRepository.loadTokens(segment.id).map { token ->
                    TranscriptToken(
                        text = token.text,
                        startSampleIndex = token.startSampleIndex,
                        endSampleIndexExclusive = token.endSampleIndexExclusive,
                        source =
                            when (token.source) {
                                TranscriptTokenSourceValue.FIRST_PASS -> TokenSource.FIRST_PASS
                                TranscriptTokenSourceValue.SECOND_PASS -> TokenSource.SECOND_PASS
                                else -> error("unknown transcript token source: " + token.source)
                            },
                    )
                }
            TranscriptSegment(
                segmentIndex = segment.segmentIndex,
                startSampleIndex = segment.startSampleIndex,
                endSampleIndexExclusive = segment.endSampleIndexExclusive,
                firstPassRawText = segment.firstPassRawText,
                secondPassRawText = segment.secondPassRawText,
                finalText = segment.finalText,
                detectedLanguage = segment.detectedLanguage,
                confidence = segment.confidence,
                tokens = tokens,
            )
        }

    private suspend fun loadGlobalSpeakerTurns(
        diarizationRunId: String,
    ): List<GlobalSpeakerTurn> {
        val speakerIndexById =
            diarizationRepository
                .loadSpeakers(diarizationRunId)
                .associate { speaker ->
                    speaker.id to (speaker.speakerOrdinal - 1)
                }
        return diarizationRepository
            .loadTurns(diarizationRunId)
            .map { turn ->
                GlobalSpeakerTurn(
                    globalSpeakerIndex =
                        checkNotNull(speakerIndexById[turn.speakerId]) {
                            "speaker turn references unknown speaker"
                        },
                    startSampleIndex = turn.startSampleIndex,
                    endSampleIndexExclusive = turn.endSampleIndexExclusive,
                    confidence = turn.confidence,
                    overlap = turn.overlap,
                )
            }
    }

    private suspend fun resolveActiveModels(): ActiveDiarizationModels {
        val requiredIds =
            listOf(
                Stage8ModelIds.VAD,
                Stage9ModelIds.SEGMENTATION,
                Stage9ModelIds.EMBEDDING,
            )
        val active =
            requiredIds.associateWith { modelId ->
                modelManager.activeModel(modelId)
            }
        val missing =
            active
                .filterValues { it == null }
                .keys
                .sorted()
        if (missing.isNotEmpty()) {
            throw MissingDiarizationModelsException(missing)
        }

        val vad = checkNotNull(active[Stage8ModelIds.VAD])
        val segmentation = checkNotNull(active[Stage9ModelIds.SEGMENTATION])
        val embedding = checkNotNull(active[Stage9ModelIds.EMBEDDING])

        require(vad.descriptor.kind == ModelKind.VAD)
        require(segmentation.descriptor.kind == ModelKind.SPEAKER)
        require(
            segmentation.descriptor.speakerRole ==
                SpeakerModelRole.DIARIZATION_SEGMENTATION,
        )
        require(embedding.descriptor.kind == ModelKind.SPEAKER)
        require(embedding.descriptor.speakerRole == SpeakerModelRole.EMBEDDING)

        return ActiveDiarizationModels(
            vad = vad,
            segmentation = segmentation,
            embedding = embedding,
        )
    }

    private fun publishProgress(
        recordingId: String,
        runId: String,
        phase: DiarizationPhase,
    ) {
        mutableState.value =
            DiarizationRunState.Running(
                recordingId = recordingId,
                runId = runId,
                progress = DiarizationProgress(phase),
            )
    }

    private fun toSpanWrite(span: SpeakerAlignedTextSpan) =
        TranscriptSpeakerSpanWrite(
            spanIndex = span.spanIndex,
            sourceSegmentIndex = span.sourceSegmentIndex,
            speakerIndex = span.speakerIndex,
            startSampleIndex = span.startSampleIndex,
            endSampleIndexExclusive = span.endSampleIndexExclusive,
            tokenSource =
                when (span.tokenSource) {
                    TokenSource.FIRST_PASS -> TranscriptTokenSourceValue.FIRST_PASS
                    TokenSource.SECOND_PASS -> TranscriptTokenSourceValue.SECOND_PASS
                    null -> null
                },
            tokenStartIndex = span.tokenStartIndex,
            tokenEndIndexExclusive = span.tokenEndIndexExclusive,
            finalTextStartOffset = span.finalTextStartOffset,
            finalTextEndOffsetExclusive = span.finalTextEndOffsetExclusive,
            assignmentQuality =
                when (span.assignmentQuality) {
                    SpeakerAssignmentQuality.ASSIGNED ->
                        SpeakerAssignmentQualityValue.ASSIGNED
                    SpeakerAssignmentQuality.ASSIGNED_WITH_OVERLAP ->
                        SpeakerAssignmentQualityValue.ASSIGNED_WITH_OVERLAP
                    SpeakerAssignmentQuality.OVERLAP_AMBIGUOUS ->
                        SpeakerAssignmentQualityValue.OVERLAP_AMBIGUOUS
                    SpeakerAssignmentQuality.UNRESOLVED ->
                        SpeakerAssignmentQualityValue.UNRESOLVED
                },
            overlap = span.overlap,
            ambiguous = span.ambiguous,
        )

    private fun configSnapshot(
        config: DiarizationConfig,
        models: List<ActiveModel>,
        vadSettings: LocalVadSettings,
        performance: ResolvedSpeechPerformance,
        requestedSpeakerCount: SpeakerCountChoice?,
    ): String =
        buildString {
            append("{\"schemaVersion\":2")
            append(",\"pipelineVersion\":").append(DIARIZATION_PIPELINE_VERSION)
            append(",\"stitchingAlgorithmVersion\":").append(STITCHING_ALGORITHM_VERSION)
            append(",\"anchorStrategy\":\"").append(ANCHOR_STRATEGY).append("\"")
            append(",\"sampleRateHz\":").append(config.sampleRateHz)
            append(",\"chunkSizeSamples\":").append(config.chunkSizeSamples)
            append(",\"chunkOverlapSamples\":").append(config.chunkOverlapSamples)
            append(",\"vadContextPaddingSamples\":").append(config.vadContextPaddingSamples)
            append(",\"speakerCountPreset\":\"")
                .append(requestedSpeakerCount?.name ?: "CUSTOM")
                .append("\"")
            append(",\"expectedSpeakerCount\":")
                .append(config.expectedSpeakerCount?.toString() ?: "null")
            append(",\"minimumGlobalSpeakerCount\":")
                .append(config.minimumGlobalSpeakerCount?.toString() ?: "null")
            append(",\"maximumGlobalSpeakerCount\":")
                .append(config.maximumGlobalSpeakerCount?.toString() ?: "null")
            append(",\"clusteringThreshold\":")
                .append(config.clusteringThreshold?.toString() ?: "null")
            append(",\"stitchingCosineThreshold\":").append(config.stitchingCosineThreshold)
            append(",\"stitchingMinimumOverlapSamples\":")
                .append(config.stitchingMinimumOverlapSamples)
            append(",\"stitchingMinimumAnchorSamples\":")
                .append(config.stitchingMinimumAnchorSamples)
            append(",\"stitchingMaxAnchorsPerSpeaker\":")
                .append(config.stitchingMaxAnchorsPerSpeaker)
            append(",\"performanceProfile\":\"").append(performance.profile.name).append("\"")
            append(",\"requestedThreads\":")
                .append(performance.requestedThreads?.toString() ?: "null")
            append(",\"effectiveThreads\":").append(performance.effectiveThreads)
            append(",\"vad\":{")
            append("\"threshold\":").append(vadSettings.threshold)
            append(",\"minSilenceDurationSeconds\":").append(vadSettings.minSilenceDurationSeconds)
            append(",\"minSpeechDurationSeconds\":").append(vadSettings.minSpeechDurationSeconds)
            append(",\"maxSpeechDurationSeconds\":").append(vadSettings.maxSpeechDurationSeconds)
            append(",\"windowSizeSamples\":512}")
            append(",\"sherpaDefaults\":{")
            append("\"numThreads\":").append(performance.effectiveThreads)
            append(",\"pyannoteWindowShiftRatio\":0.1")
            append(",\"minDurationOnSeconds\":0.2")
            append(",\"minDurationOffSeconds\":0.5")
            append(",\"defaultClusteringThreshold\":0.5")
            append(",\"computeConfidence\":true}")
            append(",\"models\":[")
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
            models
                .sortedBy { it.descriptor.modelId }
                .joinToString("\n") { model ->
                    model.descriptor.modelId + "|" +
                        model.descriptor.version + "|" +
                        model.descriptor.revision + "|" +
                        model.manifestDigest
                }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
    }

    private fun releaseLeases(leases: List<ModelLease>): List<ModelLease> {
        leases.asReversed().forEach { lease ->
            runCatching { lease.close() }
        }
        return emptyList()
    }

    private data class ActiveDiarizationModels(
        val vad: ActiveModel,
        val segmentation: ActiveModel,
        val embedding: ActiveModel,
    ) {
        val all: List<ActiveModel>
            get() = listOf(vad, segmentation, embedding)
    }

    private data class AnchorRange(
        val speakerIndex: Int,
        val startSampleIndex: Long,
        val endSampleIndexExclusive: Long,
    ) {
        val sampleCount: Long
            get() = endSampleIndexExclusive - startSampleIndex
    }

    private fun normalizeSpeakerEmbedding(values: FloatArray): FloatArray {
        require(values.isNotEmpty())
        var sumSquares = 0.0
        values.forEach { value ->
            require(value.isFinite())
            sumSquares += value.toDouble() * value.toDouble()
        }
        require(sumSquares > 0.0) { "speaker embedding norm must be positive" }
        val norm = kotlin.math.sqrt(sumSquares).toFloat()
        return FloatArray(values.size) { index -> values[index] / norm }
    }

    private companion object {
        const val DIARIZATION_PIPELINE_VERSION = 2
        const val ALIGNMENT_VERSION = 1
        const val STITCHING_ALGORITHM_VERSION = 2
        const val ANCHOR_STRATEGY = "multi-clean-weighted-v2"
    }
}
