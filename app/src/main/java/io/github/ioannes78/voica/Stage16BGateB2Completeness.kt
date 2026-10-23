package io.github.ioannes78.voica

import android.app.Application
import android.os.Debug
import android.os.SystemClock
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelLease
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.transcript.BenchmarkComponentIdentity
import io.github.ioannes78.voica.transcript.LocalFileAsrBenchmarkObservation
import io.github.ioannes78.voica.transcript.SpeechSegment
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

internal const val STAGE16B_GATE_B_B2_MAX_RECORDING_SECONDS = 120
internal const val STAGE16B_GATE_B_B2_MAX_ASR_INPUT_SECONDS = 25

/** Per-frozen-input evidence used for manual B2 completeness review. */
data class Stage16BGateB2ChunkObservation(
    val index: Int,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val rawText: String,
    val detectedLanguage: String?,
) {
    init {
        require(index >= 0)
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
    }

    /** Known SenseVoice language metadata is not spoken content, but rawText remains untouched. */
    val contentText: String
        get() = stage16BScoringText(rawText).trim()

    val blank: Boolean
        get() = contentText.isBlank()
}

data class Stage16BGateB2ModelObservation(
    val modelId: String,
    val benchmark: LocalFileAsrBenchmarkObservation?,
    val startingPssKb: Long?,
    val engineOpenTimeMillis: Long?,
    val chunks: List<Stage16BGateB2ChunkObservation>,
    val errorMessage: String?,
) {
    val ready: Boolean
        get() = benchmark != null && errorMessage == null && chunks.isNotEmpty() && chunks.any { !it.blank }

    val blankChunkIndexes: List<Int>
        get() = chunks.filter(Stage16BGateB2ChunkObservation::blank).map(Stage16BGateB2ChunkObservation::index)

    val nonBlankChunkCount: Int
        get() = chunks.count { !it.blank }

    val rawText: String
        get() = chunks.filterNot(Stage16BGateB2ChunkObservation::blank).joinToString("\n") { it.rawText.trim() }
}

data class Stage16BGateB2Result(
    val recordingId: String,
    val canonicalSha256: String?,
    val vad: BenchmarkComponentIdentity,
    val vadWallTimeMillis: Long,
    val totalSampleCount: Long,
    val speechSegments: List<SpeechSegment>,
    val frozenAsrInputBoundaries: List<SpeechSegment>,
    val models: List<Stage16BGateB2ModelObservation>,
) {
    val formalBaselineValid: Boolean
        get() = vad.id == Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID

    val allCandidatesReady: Boolean
        get() =
            formalBaselineValid &&
                models.map(Stage16BGateB2ModelObservation::modelId) == Stage16BGateBBenchmarkContract.MODEL_IDS &&
                models.all(Stage16BGateB2ModelObservation::ready)
}

sealed interface Stage16BGateB2State {
    data object Idle : Stage16BGateB2State

    data class Running(
        val recordingId: String,
        val phase: String,
    ) : Stage16BGateB2State

    data class Completed(
        val result: Stage16BGateB2Result,
    ) : Stage16BGateB2State

    data class Failed(
        val recordingId: String,
        val message: String,
        val missingModelIds: List<String> = emptyList(),
    ) : Stage16BGateB2State

    data class Cancelled(
        val recordingId: String,
    ) : Stage16BGateB2State
}

/**
 * QA-only B2 runner for real-recording quality/completeness review.
 *
 * Unlike B1, B2 has no synthetic reference transcript and therefore does not manufacture CER/WER.
 * It runs exact FSMN once, freezes canonical sampleIndex chunks once, and records the output of each
 * ASR for each identical chunk so omissions, repetitions and hallucinations can be reviewed directly.
 */
class Stage16BGateB2CompletenessRunner(
    private val application: Application,
    private val pcmSourceResolver: PcmSourceResolver,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val localSpeechSettings: () -> LocalSpeechSettings,
    private val loadCanonicalSha256: suspend (recordingId: String) -> String?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var currentJob: Job? = null
    private val baseEngineProvider = SherpaStage8TranscriptionEngineProvider(application.assets)
    private val engineProvider =
        Stage16BLocalFileTranscriptionEngineProvider(
            delegate = baseEngineProvider,
            assetManager = application.assets,
        )
    private val mutableState = MutableStateFlow<Stage16BGateB2State>(Stage16BGateB2State.Idle)

    val state: StateFlow<Stage16BGateB2State> = mutableState.asStateFlow()

    fun start(recordingId: String): Boolean {
        require(recordingId.isNotBlank())
        checkQaBuild()
        synchronized(lock) {
            if (currentJob?.isActive == true) return false
            val job = scope.launch { runBenchmark(recordingId) }
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
            currentJob?.cancel(CancellationException("user cancelled Stage 16B Gate B B2 benchmark"))
        }
    }

    fun reset() {
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            mutableState.value = Stage16BGateB2State.Idle
        }
    }

    private fun checkQaBuild() {
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B Gate B B2 benchmark is restricted to Debug/QA builds"
        }
    }

    private suspend fun runBenchmark(recordingId: String) {
        try {
            mutableState.value = Stage16BGateB2State.Running(recordingId, "B2: Resolve strict FSMN baseline")
            val fsmn = modelManager.activeModel(Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID)
            if (fsmn == null) {
                mutableState.value =
                    Stage16BGateB2State.Failed(
                        recordingId = recordingId,
                        message = "Gate B formal baseline requires active FSMN; Silero fallback is invalid",
                        missingModelIds = listOf(Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID),
                    )
                return
            }
            require(fsmn.descriptor.modelId == Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID) {
                "Gate B formal baseline resolved a non-FSMN VAD"
            }

            val settings = localSpeechSettings()
            val threads = settings.resolvePerformance().effectiveThreads
            val fsmnLease = acquire(fsmn)
            val vadStartedNs = SystemClock.elapsedRealtimeNanos()
            val speechSegments =
                try {
                    val source =
                        pcmSourceResolver.resolvePcmSource(recordingId)
                            ?: error("canonical PCM source is unavailable")
                    source.use {
                        requireCanonicalSource(it.sampleRateHz, it.channelCount, it.totalSampleCount)
                        val factory = engineProvider.vadFactory(fsmn, threads, settings.vad)
                        factory.open().use { engine -> engine.analyze(it) }
                    }
                } finally {
                    fsmnLease.close()
                }
            val vadWallTimeMillis = elapsedMillis(vadStartedNs)

            mutableState.value = Stage16BGateB2State.Running(recordingId, "B2: Freeze canonical PCM inputs")
            val pcm = readCanonicalPcm(recordingId)
            val frozenInputs =
                buildStage16BGateBFrozenInputs(
                    pcm = pcm,
                    speechSegments = speechSegments,
                    maxChunkSamples =
                        CanonicalPcmProfile.SAMPLE_RATE_HZ * STAGE16B_GATE_B_B2_MAX_ASR_INPUT_SECONDS,
                )
            check(frozenInputs.isNotEmpty()) { "FSMN produced no speech input for Gate B B2" }

            val canonicalSha256 = loadCanonicalSha256(recordingId)
            val vadIdentity = identity(fsmn)
            val speechSampleCount = speechSegments.sumOf(SpeechSegment::sampleCount)
            val observations = mutableListOf<Stage16BGateB2ModelObservation>()

            for (modelId in Stage16BGateBBenchmarkContract.MODEL_IDS) {
                mutableState.value = Stage16BGateB2State.Running(recordingId, "B2 ASR: $modelId")
                val model = modelManager.activeModel(modelId)
                if (model == null) {
                    observations +=
                        Stage16BGateB2ModelObservation(
                            modelId = modelId,
                            benchmark = null,
                            startingPssKb = null,
                            engineOpenTimeMillis = null,
                            chunks = emptyList(),
                            errorMessage = "model is not active/installed",
                        )
                    continue
                }
                observations +=
                    runModel(
                        recordingId = recordingId,
                        canonicalSha256 = canonicalSha256,
                        totalSampleCount = pcm.size.toLong(),
                        speechSampleCount = speechSampleCount,
                        vadIdentity = vadIdentity,
                        vadWallTimeMillis = vadWallTimeMillis,
                        model = model,
                        frozenInputs = frozenInputs,
                        settings = settings,
                        numThreads = threads,
                    )
            }

            mutableState.value =
                Stage16BGateB2State.Completed(
                    Stage16BGateB2Result(
                        recordingId = recordingId,
                        canonicalSha256 = canonicalSha256,
                        vad = vadIdentity,
                        vadWallTimeMillis = vadWallTimeMillis,
                        totalSampleCount = pcm.size.toLong(),
                        speechSegments = speechSegments.toList(),
                        frozenAsrInputBoundaries =
                            frozenInputs.map { SpeechSegment(it.startSampleIndex, it.endSampleIndexExclusive) },
                        models = observations.toList(),
                    ),
                )
        } catch (cancelled: CancellationException) {
            mutableState.value = Stage16BGateB2State.Cancelled(recordingId)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value =
                Stage16BGateB2State.Failed(
                    recordingId = recordingId,
                    message = error.message ?: error::class.java.simpleName,
                )
        }
    }

    private suspend fun runModel(
        recordingId: String,
        canonicalSha256: String?,
        totalSampleCount: Long,
        speechSampleCount: Long,
        vadIdentity: BenchmarkComponentIdentity,
        vadWallTimeMillis: Long,
        model: ActiveModel,
        frozenInputs: List<Stage16BGateBFrozenInput>,
        settings: LocalSpeechSettings,
        numThreads: Int,
    ): Stage16BGateB2ModelObservation =
        supervisorScope {
            val lease = acquire(model)
            val startingPssKb = Debug.getPss().toLong()
            val peakPssKb = AtomicLong(startingPssKb)
            val sampler = launchPssSampler(peakPssKb)
            var engineOpenTimeMillis: Long? = null
            var asrTimeMillis = 0L
            var tokenCount = 0
            val chunks = mutableListOf<Stage16BGateB2ChunkObservation>()
            try {
                val factory =
                    engineProvider.secondPassFactory(
                        model = model,
                        numThreads = numThreads,
                        senseVoiceSettings = settings.senseVoice,
                        qwenSettings = settings.qwen,
                    )
                val openStartedNs = SystemClock.elapsedRealtimeNanos()
                val engine = factory.open()
                engineOpenTimeMillis = elapsedMillis(openStartedNs)
                engine.use {
                    frozenInputs.forEachIndexed { index, input ->
                        val callStartedNs = SystemClock.elapsedRealtimeNanos()
                        val hypothesis = it.transcribe(input.samples, CanonicalPcmProfile.SAMPLE_RATE_HZ)
                        asrTimeMillis += elapsedMillis(callStartedNs)
                        tokenCount += hypothesis.tokens.size
                        chunks +=
                            Stage16BGateB2ChunkObservation(
                                index = index,
                                startSampleIndex = input.startSampleIndex,
                                endSampleIndexExclusive = input.endSampleIndexExclusive,
                                rawText = hypothesis.text.trim(),
                                detectedLanguage = hypothesis.detectedLanguage?.takeIf(String::isNotBlank),
                            )
                        updatePeak(peakPssKb, Debug.getPss().toLong())
                    }
                }

                val asrTotalWithOpen = asrTimeMillis + checkNotNull(engineOpenTimeMillis)
                val blankCount = chunks.count(Stage16BGateB2ChunkObservation::blank)
                val benchmark =
                    LocalFileAsrBenchmarkObservation(
                        recordingId = recordingId,
                        canonicalSha256 = canonicalSha256,
                        sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                        totalSampleCount = totalSampleCount,
                        speechSampleCount = speechSampleCount,
                        vad = vadIdentity,
                        asr = identity(model),
                        wallTimeMillis = vadWallTimeMillis + asrTotalWithOpen,
                        vadTimeMillis = vadWallTimeMillis,
                        asrTimeMillis = asrTimeMillis,
                        timingAlignmentTimeMillis = null,
                        punctuationTimeMillis = null,
                        segmentCount = frozenInputs.size,
                        tokenCount = tokenCount,
                        peakPssKb = peakPssKb.get(),
                        notes =
                            listOf(
                                "gate=B2",
                                "fsmn=frozen-once",
                                "maxAsrInputSeconds=$STAGE16B_GATE_B_B2_MAX_ASR_INPUT_SECONDS",
                                "external-punctuation=off",
                                "timeline-alignment=off",
                                "threads=$numThreads",
                                "engineOpenMs=$engineOpenTimeMillis",
                                "asrCalls=${chunks.size}",
                                "blankCalls=$blankCount",
                                "review=chunk-aligned-completeness",
                            ),
                    )
                Stage16BGateB2ModelObservation(
                    modelId = model.descriptor.modelId,
                    benchmark = benchmark,
                    startingPssKb = startingPssKb,
                    engineOpenTimeMillis = engineOpenTimeMillis,
                    chunks = chunks.toList(),
                    errorMessage = null,
                )
            } catch (error: Exception) {
                Stage16BGateB2ModelObservation(
                    modelId = model.descriptor.modelId,
                    benchmark = null,
                    startingPssKb = startingPssKb,
                    engineOpenTimeMillis = engineOpenTimeMillis,
                    chunks = chunks.toList(),
                    errorMessage = error.message ?: error::class.java.simpleName,
                )
            } finally {
                sampler.cancelAndJoin()
                lease.close()
            }
        }

    private suspend fun readCanonicalPcm(recordingId: String): ShortArray {
        val source =
            pcmSourceResolver.resolvePcmSource(recordingId)
                ?: error("canonical PCM source is unavailable")
        return source.use {
            requireCanonicalSource(it.sampleRateHz, it.channelCount, it.totalSampleCount)
            val totalSamples = it.totalSampleCount.toInt()
            val pcm = ShortArray(totalSamples)
            var offset = 0
            while (offset < totalSamples) {
                val read = it.read(pcm, offset, totalSamples - offset) ?: break
                require(read.startSampleIndex == offset.toLong()) {
                    "canonical PCM source returned a non-sequential sample index"
                }
                require(read.sampleCount > 0) { "canonical PCM source returned an empty read" }
                offset += read.sampleCount
            }
            require(offset == totalSamples) {
                "canonical PCM ended early: expected=$totalSamples actual=$offset"
            }
            pcm
        }
    }

    private fun requireCanonicalSource(
        sampleRateHz: Int,
        channelCount: Int,
        totalSampleCount: Long,
    ) {
        require(sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ) {
            "Gate B B2 requires canonical 16 kHz PCM"
        }
        require(channelCount == CanonicalPcmProfile.CHANNEL_COUNT) {
            "Gate B B2 requires canonical mono PCM"
        }
        require(totalSampleCount > 0L) { "canonical PCM is empty" }
        val maxSamples =
            CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * STAGE16B_GATE_B_B2_MAX_RECORDING_SECONDS
        require(totalSampleCount <= maxSamples) {
            "B2 first pass is limited to ${STAGE16B_GATE_B_B2_MAX_RECORDING_SECONDS}s; use B6 for long files"
        }
        require(totalSampleCount <= Int.MAX_VALUE.toLong())
    }

    private fun identity(model: ActiveModel): BenchmarkComponentIdentity =
        BenchmarkComponentIdentity(
            id = model.descriptor.modelId,
            version = model.descriptor.version,
            revision = model.descriptor.revision.toString(),
        )

    private fun acquire(model: ActiveModel): ModelLease =
        modelUseRegistry.acquire(
            modelId = model.descriptor.modelId,
            version = model.descriptor.version,
            revision = model.descriptor.revision,
        )

    private fun CoroutineScope.launchPssSampler(peakPssKb: AtomicLong): Job =
        launch {
            while (isActive) {
                updatePeak(peakPssKb, Debug.getPss().toLong())
                delay(PSS_SAMPLE_INTERVAL_MS)
            }
        }

    private companion object {
        const val PSS_SAMPLE_INTERVAL_MS = 100L

        fun elapsedMillis(startedNs: Long): Long =
            (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L) / 1_000_000L

        fun updatePeak(target: AtomicLong, candidate: Long) {
            while (true) {
                val current = target.get()
                if (candidate <= current || target.compareAndSet(current, candidate)) return
            }
        }
    }
}
