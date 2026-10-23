package io.github.ioannes78.voica

import android.app.Application
import android.os.Debug
import android.os.SystemClock
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelLease
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.model.SpeakerModelRole
import io.github.ioannes78.voica.sherpa.SherpaSileroVadEngine
import io.github.ioannes78.voica.sherpa.SherpaTenVadEngine
import io.github.ioannes78.voica.sherpa.SherpaVadModelLocation
import io.github.ioannes78.voica.sherpa.SileroVadSettings
import io.github.ioannes78.voica.sherpa.TenVadSettings
import io.github.ioannes78.voica.transcript.DiarizationChunkStitchInput
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.ExternalVadEngineProvider
import io.github.ioannes78.voica.transcript.GlobalSpeakerTurn
import io.github.ioannes78.voica.transcript.SpeechSegment
import io.github.ioannes78.voica.transcript.VadBenchmarkProfile
import io.github.ioannes78.voica.transcript.VadEngine
import io.github.ioannes78.voica.transcript.consumeDiarizationWindowsSequentially
import io.github.ioannes78.voica.transcript.planDiarizationWindows
import io.github.ioannes78.voica.transcript.stitchDiarizationChunks
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Stage 16B Gate A3 compares only the VAD front-end while freezing the downstream diarization
 * stack to the current product baseline: pyannote segmentation + CAM++ embedding, AUTO speaker
 * count, 60 s windows / 10 s overlap, clustering threshold 0.5 and stitching threshold 0.75.
 *
 * The runner is instantiated only from the existing QA-only Stage 16B diagnostics surface. It
 * never persists a diarization run and never changes the production VAD or diarization settings.
 */
data class DiarizationVadBenchmarkTurn(
    val speakerIndex: Int,
    val startMillis: Long,
    val endMillis: Long,
    val confidence: Float?,
    val overlap: Boolean,
) {
    val durationMillis: Long
        get() = endMillis - startMillis
}

data class DiarizationVadBenchmarkObservation(
    val profile: VadBenchmarkProfile,
    val speechSegmentCount: Int,
    val speechDurationMillis: Long,
    val windowCount: Int,
    val windowWorkloadMillis: Long,
    val speakerCount: Int,
    val turnCount: Int,
    val speakerSwitchCount: Int,
    val shortTurnCount: Int,
    val overlapTurnCount: Int,
    val speakerTurnCounts: List<Int>,
    val speakerDurationsMillis: List<Long>,
    val vadWallTimeMillis: Long,
    val diarizationWallTimeMillis: Long,
    val stitchingWallTimeMillis: Long,
    val totalWallTimeMillis: Long,
    val peakPssKb: Long?,
    val embeddingModelId: String,
    val effectiveThreads: Int,
    val turns: List<DiarizationVadBenchmarkTurn>,
)

sealed interface DiarizationVadBenchmarkState {
    data object Idle : DiarizationVadBenchmarkState

    data class Running(
        val recordingId: String,
        val currentProfile: VadBenchmarkProfile,
        val completedProfiles: Int,
        val totalProfiles: Int,
    ) : DiarizationVadBenchmarkState

    data class Completed(
        val recordingId: String,
        val observations: List<DiarizationVadBenchmarkObservation>,
    ) : DiarizationVadBenchmarkState

    data class Failed(
        val recordingId: String,
        val message: String,
        val missingModelIds: List<String> = emptyList(),
    ) : DiarizationVadBenchmarkState

    data class Cancelled(
        val recordingId: String,
    ) : DiarizationVadBenchmarkState
}

class Stage16BDiarizationBenchmarkRunner(
    private val application: Application,
    private val pcmSourceResolver: PcmSourceResolver,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val localSpeechSettings: () -> LocalSpeechSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var currentJob: Job? = null
    private val engineProvider = SherpaStage9DiarizationEngineProvider(application.assets)
    private val mutableState =
        MutableStateFlow<DiarizationVadBenchmarkState>(DiarizationVadBenchmarkState.Idle)

    val state: StateFlow<DiarizationVadBenchmarkState> = mutableState.asStateFlow()

    fun start(recordingId: String): Boolean {
        require(recordingId.isNotBlank())
        checkQaBuild()
        synchronized(lock) {
            if (currentJob?.isActive == true) return false
            val job = scope.launch { runGateA3(recordingId) }
            currentJob = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (currentJob === job) currentJob = null
                }
            }
            return true
        }
    }

    fun cancel() {
        synchronized(lock) {
            currentJob?.cancel(CancellationException("user cancelled Stage 16B Gate A3 benchmark"))
        }
    }

    fun reset() {
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            mutableState.value = DiarizationVadBenchmarkState.Idle
        }
    }

    private fun checkQaBuild() {
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B Gate A3 is restricted to Debug/QA builds"
        }
    }

    private suspend fun runGateA3(recordingId: String) {
        val observations = mutableListOf<DiarizationVadBenchmarkObservation>()
        var commonLeases: List<ModelLease> = emptyList()
        try {
            val resolved = resolveModels()
            if (resolved.missingModelIds.isNotEmpty()) {
                mutableState.value =
                    DiarizationVadBenchmarkState.Failed(
                        recordingId = recordingId,
                        message = "A3 所需 VAD / 说话人模型未全部启用",
                        missingModelIds = resolved.missingModelIds,
                    )
                return
            }

            val vadModels = checkNotNull(resolved.vadModels)
            val segmentation = checkNotNull(resolved.segmentation)
            val embedding = checkNotNull(resolved.embedding)
            val threads = localSpeechSettings().resolvePerformance().effectiveThreads
            val config = frozenDiarizationConfig()

            commonLeases = listOf(segmentation, embedding).map(::acquire)
            engineProvider.validateBundle(segmentation = segmentation, embedding = embedding)

            val profiles = gateA3Profiles()
            profiles.forEachIndexed { index, profile ->
                mutableState.value =
                    DiarizationVadBenchmarkState.Running(
                        recordingId = recordingId,
                        currentProfile = profile,
                        completedProfiles = index,
                        totalProfiles = profiles.size,
                    )
                observations +=
                    runProfile(
                        recordingId = recordingId,
                        profile = profile,
                        vadModel = vadModels.getValue(profile.modelId),
                        segmentation = segmentation,
                        embedding = embedding,
                        config = config,
                        numThreads = threads,
                    )
            }

            mutableState.value =
                DiarizationVadBenchmarkState.Completed(
                    recordingId = recordingId,
                    observations = observations.toList(),
                )
        } catch (cancelled: CancellationException) {
            mutableState.value = DiarizationVadBenchmarkState.Cancelled(recordingId)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value =
                DiarizationVadBenchmarkState.Failed(
                    recordingId = recordingId,
                    message = error.message ?: error::class.java.simpleName,
                )
        } finally {
            commonLeases.asReversed().forEach { lease -> runCatching { lease.close() } }
        }
    }

    private suspend fun runProfile(
        recordingId: String,
        profile: VadBenchmarkProfile,
        vadModel: ActiveModel,
        segmentation: ActiveModel,
        embedding: ActiveModel,
        config: DiarizationConfig,
        numThreads: Int,
    ): DiarizationVadBenchmarkObservation =
        supervisorScope {
            val vadLease = acquire(vadModel)
            val peakPssKb = AtomicLong(Debug.getPss().toLong())
            val sampler = launchPssSampler(peakPssKb)
            try {
                val totalStartedNs = SystemClock.elapsedRealtimeNanos()
                val sourceForVad =
                    pcmSourceResolver.resolvePcmSource(recordingId)
                        ?: error("canonical PCM source is unavailable")
                val totalSampleCount = sourceForVad.totalSampleCount
                val vadStartedNs = SystemClock.elapsedRealtimeNanos()
                val speechSegments =
                    sourceForVad.use { source ->
                        require(source.sampleRateHz == CANONICAL_SAMPLE_RATE_HZ)
                        require(source.channelCount == 1)
                        openVadEngine(profile, vadModel, numThreads).use { vad ->
                            vad.analyze(source)
                        }
                    }
                val vadWallTimeMillis = elapsedMillis(vadStartedNs)
                val ranges =
                    planDiarizationWindows(
                        speechSegments = speechSegments,
                        totalSampleCount = totalSampleCount,
                        config = config,
                    )

                val chunks = mutableListOf<DiarizationChunkStitchInput>()
                var diarizationWallTimeMillis = 0L
                var stitchingWallTimeMillis = 0L
                val stitched =
                    if (ranges.isEmpty()) {
                        stitchDiarizationChunks(emptyList(), config)
                    } else {
                        val sourceForDiarization =
                            pcmSourceResolver.resolvePcmSource(recordingId)
                                ?: error("canonical PCM source disappeared before diarization")
                        require(sourceForDiarization.totalSampleCount == totalSampleCount)
                        val diarizationEngine =
                            engineProvider.diarizationEngine(
                                segmentation = segmentation,
                                embedding = embedding,
                                numThreads = numThreads,
                            )
                        val embeddingEngine =
                            engineProvider.embeddingEngine(
                                model = embedding,
                                numThreads = numThreads,
                            )
                        try {
                            val diarizationStartedNs = SystemClock.elapsedRealtimeNanos()
                            sourceForDiarization.use { source ->
                                consumeDiarizationWindowsSequentially(
                                    source = source,
                                    ranges = ranges,
                                ) { index, window ->
                                    val result =
                                        diarizationEngine.diarize(
                                            window = window,
                                            config = config,
                                        )
                                    val anchors =
                                        buildSpeakerAnchorEmbeddings(
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
                                }
                            }
                            diarizationWallTimeMillis = elapsedMillis(diarizationStartedNs)
                        } finally {
                            runCatching { embeddingEngine.close() }
                            runCatching { diarizationEngine.close() }
                        }

                        val stitchingStartedNs = SystemClock.elapsedRealtimeNanos()
                        val result = stitchDiarizationChunks(chunks, config)
                        stitchingWallTimeMillis = elapsedMillis(stitchingStartedNs)
                        result
                    }

                updatePeak(peakPssKb, Debug.getPss().toLong())
                val speakerDurationsSamples = LongArray(stitched.speakerCount)
                val speakerTurnCounts = IntArray(stitched.speakerCount)
                stitched.turns.forEach { turn ->
                    speakerDurationsSamples[turn.globalSpeakerIndex] +=
                        turn.endSampleIndexExclusive - turn.startSampleIndex
                    speakerTurnCounts[turn.globalSpeakerIndex] += 1
                }
                val speakerSwitchCount =
                    stitched.turns.zipWithNext().count { (left, right) ->
                        left.globalSpeakerIndex != right.globalSpeakerIndex
                    }
                val shortTurnCount =
                    stitched.turns.count { turn ->
                        turn.endSampleIndexExclusive - turn.startSampleIndex <
                            CANONICAL_SAMPLE_RATE_HZ.toLong()
                    }
                val speechSamples = speechSegments.sumOf(SpeechSegment::sampleCount)
                val workloadSamples = ranges.sumOf { it.sampleCount }

                DiarizationVadBenchmarkObservation(
                    profile = profile,
                    speechSegmentCount = speechSegments.size,
                    speechDurationMillis = samplesToMillis(speechSamples),
                    windowCount = ranges.size,
                    windowWorkloadMillis = samplesToMillis(workloadSamples),
                    speakerCount = stitched.speakerCount,
                    turnCount = stitched.turns.size,
                    speakerSwitchCount = speakerSwitchCount,
                    shortTurnCount = shortTurnCount,
                    overlapTurnCount = stitched.turns.count(GlobalSpeakerTurn::overlap),
                    speakerTurnCounts = speakerTurnCounts.toList(),
                    speakerDurationsMillis =
                        speakerDurationsSamples.map(::samplesToMillis),
                    vadWallTimeMillis = vadWallTimeMillis,
                    diarizationWallTimeMillis = diarizationWallTimeMillis,
                    stitchingWallTimeMillis = stitchingWallTimeMillis,
                    totalWallTimeMillis = elapsedMillis(totalStartedNs),
                    peakPssKb = peakPssKb.get(),
                    embeddingModelId = embedding.descriptor.modelId,
                    effectiveThreads = numThreads,
                    turns = stitched.turns.map(::toBenchmarkTurn),
                )
            } finally {
                sampler.cancelAndJoin()
                vadLease.close()
            }
        }

    private suspend fun resolveModels(): ModelResolution {
        val vadIds =
            listOf(
                Stage8ModelIds.VAD,
                SpeechBenchmarkRunner.STAGE16B_FSMN_VAD_CANDIDATE_ID,
                SpeechBenchmarkRunner.STAGE16B_TEN_VAD_CANDIDATE_ID,
            )
        val requiredIds =
            vadIds +
                listOf(
                    Stage9ModelIds.SEGMENTATION,
                    Stage13ASpeakerEmbeddingModelIds.CAMP_PLUS,
                )
        val active =
            requiredIds.associateWith { modelId -> modelManager.activeModel(modelId) }
        val missing = active.filterValues { it == null }.keys.sorted()
        if (missing.isNotEmpty()) {
            return ModelResolution(
                vadModels = null,
                segmentation = null,
                embedding = null,
                missingModelIds = missing,
            )
        }

        val vadModels = vadIds.associateWith { id -> checkNotNull(active[id]) }
        vadModels.values.forEach { model -> require(model.descriptor.kind == ModelKind.VAD) }
        val segmentation = checkNotNull(active[Stage9ModelIds.SEGMENTATION])
        val embedding = checkNotNull(active[Stage13ASpeakerEmbeddingModelIds.CAMP_PLUS])
        require(segmentation.descriptor.kind == ModelKind.SPEAKER)
        require(
            segmentation.descriptor.speakerRole == SpeakerModelRole.DIARIZATION_SEGMENTATION,
        )
        require(embedding.descriptor.kind == ModelKind.SPEAKER)
        require(embedding.descriptor.speakerRole == SpeakerModelRole.EMBEDDING)

        return ModelResolution(
            vadModels = vadModels,
            segmentation = segmentation,
            embedding = embedding,
            missingModelIds = emptyList(),
        )
    }

    private fun openVadEngine(
        profile: VadBenchmarkProfile,
        model: ActiveModel,
        numThreads: Int,
    ): VadEngine =
        when (profile.modelId) {
            Stage8ModelIds.VAD ->
                SherpaSileroVadEngine(
                    model = model.descriptor,
                    modelLocation = sherpaVadLocation(model),
                    settings =
                        SileroVadSettings(
                            threshold = 0.5F,
                            minSilenceDurationSeconds = 0.25F,
                            minSpeechDurationSeconds = 0.25F,
                            maxSpeechDurationSeconds = 30F,
                            numThreads = numThreads,
                        ),
                )

            SpeechBenchmarkRunner.STAGE16B_TEN_VAD_CANDIDATE_ID ->
                SherpaTenVadEngine(
                    model = model.descriptor,
                    modelLocation = sherpaVadLocation(model),
                    settings =
                        TenVadSettings(
                            threshold = 0.5F,
                            minSilenceDurationSeconds = 0.25F,
                            minSpeechDurationSeconds = 0.25F,
                            windowSizeSamples = 256,
                            maxSpeechDurationSeconds = 30F,
                            numThreads = numThreads,
                        ),
                )

            SpeechBenchmarkRunner.STAGE16B_FSMN_VAD_CANDIDATE_ID ->
                fsmnProvider().open(
                    model = model.descriptor,
                    installedDirectory =
                        model.installedDirectory
                            ?: error("FSMN candidate must be an installed managed model"),
                    numThreads = numThreads,
                    maxSpeechDurationSeconds = 30F,
                )

            else -> error("unsupported Gate A3 VAD model: ${profile.modelId}")
        }

    private fun sherpaVadLocation(model: ActiveModel): SherpaVadModelLocation {
        val installed = model.installedDirectory
        if (installed != null) {
            val modelFile =
                model.descriptor.files
                    .singleOrNull { it.relativePath.endsWith(".onnx", ignoreCase = true) }
                    ?.let { File(installed, it.relativePath).canonicalFile }
                    ?: error("VAD model must contain exactly one ONNX file")
            require(modelFile.isFile) { "VAD model file is missing: ${modelFile.name}" }
            return SherpaVadModelLocation.File(modelFile.absolutePath)
        }
        val assetPath =
            model.descriptor.builtinAssetPath
                ?: error("VAD model is neither installed nor backed by a builtin asset")
        return SherpaVadModelLocation.Asset(application.assets, assetPath)
    }

    private fun fsmnProvider(): ExternalVadEngineProvider {
        val className = "io.github.ioannes78.voica.fsmn.FsmnVadEngineProvider"
        val providerClass =
            try {
                Class.forName(className)
            } catch (error: ClassNotFoundException) {
                error("FSMN candidate runtime is unavailable in this build; use a Stage 16B QA APK")
            }
        return providerClass.getDeclaredConstructor().newInstance() as? ExternalVadEngineProvider
            ?: error("FSMN candidate provider does not implement ExternalVadEngineProvider")
    }

    private fun gateA3Profiles(): List<VadBenchmarkProfile> =
        listOf(
            VadBenchmarkProfile(
                id = "silero-30s",
                displayName = "Silero · 30s 控制组",
                modelId = Stage8ModelIds.VAD,
                maxSpeechDurationSeconds = 30F,
                primaryGateAProfile = true,
                notes = listOf("production baseline"),
            ),
            VadBenchmarkProfile(
                id = "fsmn-30s",
                displayName = "FSMN · 30s 控制组",
                modelId = SpeechBenchmarkRunner.STAGE16B_FSMN_VAD_CANDIDATE_ID,
                maxSpeechDurationSeconds = 30F,
                primaryGateAProfile = true,
                notes = listOf("endpoint=800/150/100ms"),
            ),
            VadBenchmarkProfile(
                id = "ten-30s",
                displayName = "TEN · 30s 控制组",
                modelId = SpeechBenchmarkRunner.STAGE16B_TEN_VAD_CANDIDATE_ID,
                maxSpeechDurationSeconds = 30F,
                primaryGateAProfile = true,
                notes = listOf("window=256"),
            ),
        )

    private fun frozenDiarizationConfig() =
        DiarizationConfig(
            clusteringThreshold = 0.5F,
            stitchingCosineThreshold = 0.75F,
        )

    private fun acquire(model: ActiveModel): ModelLease =
        modelUseRegistry.acquire(
            modelId = model.descriptor.modelId,
            version = model.descriptor.version,
            revision = model.descriptor.revision,
        )

    private fun CoroutineScope.launchPssSampler(target: AtomicLong): Job =
        launch(Dispatchers.Default) {
            while (isActive) {
                updatePeak(target, Debug.getPss().toLong())
                delay(PSS_SAMPLE_INTERVAL_MS)
            }
        }

    private fun updatePeak(
        target: AtomicLong,
        candidate: Long,
    ) {
        while (true) {
            val current = target.get()
            if (candidate <= current || target.compareAndSet(current, candidate)) return
        }
    }

    private fun elapsedMillis(startedNs: Long): Long =
        ((SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L).coerceAtLeast(0L)

    private fun samplesToMillis(samples: Long): Long =
        ((samples * 1000.0) / CANONICAL_SAMPLE_RATE_HZ.toDouble()).toLong()

    private fun toBenchmarkTurn(turn: GlobalSpeakerTurn) =
        DiarizationVadBenchmarkTurn(
            speakerIndex = turn.globalSpeakerIndex,
            startMillis = samplesToMillis(turn.startSampleIndex),
            endMillis = samplesToMillis(turn.endSampleIndexExclusive),
            confidence = turn.confidence,
            overlap = turn.overlap,
        )

    private data class ModelResolution(
        val vadModels: Map<String, ActiveModel>?,
        val segmentation: ActiveModel?,
        val embedding: ActiveModel?,
        val missingModelIds: List<String>,
    )

    companion object {
        private const val PSS_SAMPLE_INTERVAL_MS = 50L
        private const val CANONICAL_SAMPLE_RATE_HZ = 16_000
    }
}
