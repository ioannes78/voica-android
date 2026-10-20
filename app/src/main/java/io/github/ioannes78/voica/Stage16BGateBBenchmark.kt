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
import io.github.ioannes78.voica.transcript.AsrAccuracyMetrics
import io.github.ioannes78.voica.transcript.AsrBenchmarkScoring
import io.github.ioannes78.voica.transcript.BenchmarkComponentIdentity
import io.github.ioannes78.voica.transcript.EditDistanceMetrics
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

/**
 * Frozen Gate B contract. The formal comparison is valid only when FSMN is the active VAD and the
 * exact same frozen ASR input chunks are reused for every candidate.
 */
internal object Stage16BGateBBenchmarkContract {
    const val FORMAL_VAD_MODEL_ID = STAGE16B_FSMN_VAD_CANDIDATE_ID
    const val MAX_B0_RECORDING_SECONDS = 120
    const val MAX_ASR_INPUT_SECONDS = 30

    /** Candidate-first order catches Fun-ASR bring-up failures before spending time on baselines. */
    val MODEL_IDS: List<String> =
        listOf(
            STAGE16B_FUNASR_NANO_CANDIDATE_ID,
            Stage8ModelIds.SECOND_PASS_ASR,
            Stage13AOfflineModelIds.QWEN3_ASR,
        )
}

/** Frozen first-pass B1 reference corpus. */
enum class Stage16BGateBB1Profile(
    val referenceText: String,
    val scoreEnglishWer: Boolean = false,
    val expectedHeadText: String? = null,
    val expectedTailText: String? = null,
) {
    ZH_BASE(
        "今天我们测试离线语音识别，重点比较三个模型在相同录音上的准确率和完整性。",
    ),
    ZH_LONG(
        "如果会议结束以后还有时间，请把今天讨论的产品计划、预算变化、风险事项和下周需要完成的任务整理成一份清晰的记录，然后发给项目组。",
    ),
    EN_BASE(
        "Today we are testing offline speech recognition on the same recording to compare accuracy, completeness, and stability.",
        scoreEnglishWer = true,
    ),
    ZH_EN_MIXED(
        "今天我们测试 Voica 的 offline ASR，重点检查 Android、GitHub、OpenAI 和 Qwen3 这些英文名称能不能正确识别。",
    ),
    NUMBERS_ITN_RAW(
        "今天是二零二六年十月二十日，会议下午三点二十五分开始，预算是十二万三千四百五十六元，完成率百分之八十七点五。",
    ),
    PROPER_NOUNS(
        "这次测试会提到 Android、GitHub、OpenAI、Qwen3、SenseVoice 和 FunASR，请不要把这些名称替换成普通中文词。",
    ),
    BOUNDARY(
        referenceText = "好，现在开始测试。中间这一句保持正常语速。最后请完整识别，谢谢。",
        expectedHeadText = "好现在开始测试",
        expectedTailText = "最后请完整识别谢谢",
    ),
    PAUSE(
        "第一项检查准确率，稍微停一下，第二项检查完整性，再停一下，第三项检查是否出现重复内容。",
    ),
}

internal data class Stage16BGateBFrozenInput(
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
    val samples: ShortArray,
) {
    init {
        require(startSampleIndex >= 0L)
        require(endSampleIndexExclusive > startSampleIndex)
        require(endSampleIndexExclusive - startSampleIndex == samples.size.toLong())
    }
}

internal fun buildStage16BGateBFrozenInputs(
    pcm: ShortArray,
    speechSegments: List<SpeechSegment>,
    maxChunkSamples: Int,
): List<Stage16BGateBFrozenInput> {
    require(maxChunkSamples > 0)
    var previousEnd = 0L
    val result = mutableListOf<Stage16BGateBFrozenInput>()
    speechSegments.forEach { segment ->
        require(segment.startSampleIndex >= previousEnd) {
            "FSMN speech segments must be monotonic and non-overlapping"
        }
        require(segment.endSampleIndexExclusive <= pcm.size.toLong()) {
            "FSMN speech segment exceeds canonical PCM"
        }
        previousEnd = segment.endSampleIndexExclusive

        var start = segment.startSampleIndex
        while (start < segment.endSampleIndexExclusive) {
            val end = minOf(segment.endSampleIndexExclusive, start + maxChunkSamples.toLong())
            result +=
                Stage16BGateBFrozenInput(
                    startSampleIndex = start,
                    endSampleIndexExclusive = end,
                    samples = pcm.copyOfRange(start.toInt(), end.toInt()),
                )
            start = end
        }
    }
    return result
}

data class Stage16BGateBB1QualityObservation(
    val profile: Stage16BGateBB1Profile,
    val referenceText: String,
    val character: EditDistanceMetrics,
    val word: EditDistanceMetrics?,
    val wholeProfileBlank: Boolean,
    val headDeletion: Boolean?,
    val tailDeletion: Boolean?,
    val duplicateNonBlankLineCount: Int,
) {
    val characterErrorRate: Double?
        get() = character.errorRate

    val wordErrorRate: Double?
        get() = word?.errorRate
}

internal fun scoreStage16BGateBB1(
    profile: Stage16BGateBB1Profile,
    hypothesis: String,
): Stage16BGateBB1QualityObservation {
    val character = AsrBenchmarkScoring.characterErrorRate(profile.referenceText, hypothesis)
    val word =
        if (profile.scoreEnglishWer) {
            AsrBenchmarkScoring.wordErrorRate(
                referenceWords = stage16BEnglishWordTokens(profile.referenceText),
                hypothesisWords = stage16BEnglishWordTokens(hypothesis),
            )
        } else {
            null
        }
    val normalizedHypothesis = AsrBenchmarkScoring.normalizeForCharacterErrorRate(hypothesis)
    val headDeletion =
        profile.expectedHeadText?.let { expected ->
            val normalizedExpected = AsrBenchmarkScoring.normalizeForCharacterErrorRate(expected)
            normalizedExpected.isNotEmpty() && !normalizedHypothesis.contains(normalizedExpected)
        }
    val tailDeletion =
        profile.expectedTailText?.let { expected ->
            val normalizedExpected = AsrBenchmarkScoring.normalizeForCharacterErrorRate(expected)
            normalizedExpected.isNotEmpty() && !normalizedHypothesis.contains(normalizedExpected)
        }
    val duplicateNonBlankLineCount =
        hypothesis
            .lineSequence()
            .map(AsrBenchmarkScoring::normalizeForCharacterErrorRate)
            .filter { it.length >= 4 }
            .groupingBy { it }
            .eachCount()
            .values
            .sumOf { count -> (count - 1).coerceAtLeast(0) }

    return Stage16BGateBB1QualityObservation(
        profile = profile,
        referenceText = profile.referenceText,
        character = character,
        word = word,
        wholeProfileBlank = normalizedHypothesis.isBlank(),
        headDeletion = headDeletion,
        tailDeletion = tailDeletion,
        duplicateNonBlankLineCount = duplicateNonBlankLineCount,
    )
}

private fun stage16BEnglishWordTokens(text: String): List<String> =
    Regex("""[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)?""")
        .findAll(text)
        .map { it.value }
        .toList()

data class Stage16BGateBB0ModelObservation(
    val modelId: String,
    val benchmark: LocalFileAsrBenchmarkObservation?,
    val startingPssKb: Long?,
    val engineOpenTimeMillis: Long?,
    val asrCallCount: Int,
    val blankCallCount: Int,
    val rawText: String,
    val detectedLanguages: List<String>,
    val errorMessage: String?,
    val b1Quality: Stage16BGateBB1QualityObservation? = null,
) {
    val ready: Boolean
        get() = benchmark != null && errorMessage == null && asrCallCount > 0 && rawText.isNotBlank()
}

data class Stage16BGateBB0Result(
    val recordingId: String,
    val canonicalSha256: String?,
    val vad: BenchmarkComponentIdentity,
    val vadWallTimeMillis: Long,
    val totalSampleCount: Long,
    val speechSegments: List<SpeechSegment>,
    val frozenAsrInputBoundaries: List<SpeechSegment>,
    val models: List<Stage16BGateBB0ModelObservation>,
    val b1Profile: Stage16BGateBB1Profile? = null,
) {
    val formalBaselineValid: Boolean
        get() = vad.id == Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID

    val allCandidatesReady: Boolean
        get() =
            formalBaselineValid &&
                models.map { it.modelId } == Stage16BGateBBenchmarkContract.MODEL_IDS &&
                models.all(Stage16BGateBB0ModelObservation::ready)
}

sealed interface Stage16BGateBB0State {
    data object Idle : Stage16BGateBB0State

    data class Running(
        val recordingId: String,
        val phase: String,
    ) : Stage16BGateBB0State

    data class Completed(
        val result: Stage16BGateBB0Result,
    ) : Stage16BGateBB0State

    data class Failed(
        val recordingId: String,
        val message: String,
        val missingModelIds: List<String> = emptyList(),
    ) : Stage16BGateBB0State

    data class Cancelled(
        val recordingId: String,
    ) : Stage16BGateBB0State
}

/**
 * Stage 16B Gate B QA-only runner for B0 readiness and B1 same-input base quality.
 *
 * It intentionally bypasses the product TranscriptionCoordinator so FSMN runs exactly once. The
 * resulting speech boundaries are frozen, split deterministically only for bounded ASR input size,
 * and the exact same PCM chunks are then fed to each ASR candidate. It never writes Room and does
 * not run external punctuation or Qwen timeline alignment; those belong to later Gate B phases.
 */
class Stage16BGateBAsrBenchmarkRunner(
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
    private val mutableState = MutableStateFlow<Stage16BGateBB0State>(Stage16BGateBB0State.Idle)

    val state: StateFlow<Stage16BGateBB0State> = mutableState.asStateFlow()

    fun start(recordingId: String): Boolean = startInternal(recordingId, null)

    fun startB1(
        recordingId: String,
        profile: Stage16BGateBB1Profile,
    ): Boolean = startInternal(recordingId, profile)

    private fun startInternal(
        recordingId: String,
        b1Profile: Stage16BGateBB1Profile?,
    ): Boolean {
        require(recordingId.isNotBlank())
        checkQaBuild()
        synchronized(lock) {
            if (currentJob?.isActive == true) return false
            val job = scope.launch { runBenchmark(recordingId, b1Profile) }
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
            currentJob?.cancel(CancellationException("user cancelled Stage 16B Gate B benchmark"))
        }
    }

    fun reset() {
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            mutableState.value = Stage16BGateBB0State.Idle
        }
    }

    private fun checkQaBuild() {
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B Gate B benchmark is restricted to Debug/QA builds"
        }
    }

    private suspend fun runBenchmark(
        recordingId: String,
        b1Profile: Stage16BGateBB1Profile?,
    ) {
        try {
            val gateLabel = if (b1Profile == null) "B0" else "B1/${b1Profile.name}"
            mutableState.value = Stage16BGateBB0State.Running(recordingId, "$gateLabel: Resolve strict FSMN baseline")
            val fsmn = modelManager.activeModel(Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID)
            if (fsmn == null) {
                mutableState.value =
                    Stage16BGateBB0State.Failed(
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

            mutableState.value = Stage16BGateBB0State.Running(recordingId, "$gateLabel: Freeze canonical PCM inputs")
            val pcm = readCanonicalPcm(recordingId)
            val frozenInputs =
                buildStage16BGateBFrozenInputs(
                    pcm = pcm,
                    speechSegments = speechSegments,
                    maxChunkSamples =
                        CanonicalPcmProfile.SAMPLE_RATE_HZ *
                            Stage16BGateBBenchmarkContract.MAX_ASR_INPUT_SECONDS,
                )
            check(frozenInputs.isNotEmpty()) { "FSMN produced no speech input for Gate B benchmark" }
            val frozenBoundaries =
                frozenInputs.map { SpeechSegment(it.startSampleIndex, it.endSampleIndexExclusive) }
            val canonicalSha256 = loadCanonicalSha256(recordingId)
            val vadIdentity = identity(fsmn)
            val speechSampleCount = speechSegments.sumOf(SpeechSegment::sampleCount)

            val observations = mutableListOf<Stage16BGateBB0ModelObservation>()
            for (modelId in Stage16BGateBBenchmarkContract.MODEL_IDS) {
                mutableState.value = Stage16BGateBB0State.Running(recordingId, "$gateLabel ASR: $modelId")
                val model = modelManager.activeModel(modelId)
                if (model == null) {
                    observations +=
                        Stage16BGateBB0ModelObservation(
                            modelId = modelId,
                            benchmark = null,
                            startingPssKb = null,
                            engineOpenTimeMillis = null,
                            asrCallCount = 0,
                            blankCallCount = 0,
                            rawText = "",
                            detectedLanguages = emptyList(),
                            errorMessage = "model is not active/installed",
                            b1Quality = b1Profile?.let { scoreStage16BGateBB1(it, "") },
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
                        b1Profile = b1Profile,
                    )
            }

            mutableState.value =
                Stage16BGateBB0State.Completed(
                    Stage16BGateBB0Result(
                        recordingId = recordingId,
                        canonicalSha256 = canonicalSha256,
                        vad = vadIdentity,
                        vadWallTimeMillis = vadWallTimeMillis,
                        totalSampleCount = pcm.size.toLong(),
                        speechSegments = speechSegments.toList(),
                        frozenAsrInputBoundaries = frozenBoundaries,
                        models = observations.toList(),
                        b1Profile = b1Profile,
                    ),
                )
        } catch (cancelled: CancellationException) {
            mutableState.value = Stage16BGateBB0State.Cancelled(recordingId)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value =
                Stage16BGateBB0State.Failed(
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
        b1Profile: Stage16BGateBB1Profile?,
    ): Stage16BGateBB0ModelObservation =
        supervisorScope {
            val lease = acquire(model)
            val startingPssKb = Debug.getPss().toLong()
            val peakPssKb = AtomicLong(startingPssKb)
            val sampler = launchPssSampler(peakPssKb)
            var engineOpenTimeMillis: Long? = null
            var asrTimeMillis = 0L
            var asrCallCount = 0
            var blankCallCount = 0
            var tokenCount = 0
            val texts = mutableListOf<String>()
            val languages = linkedSetOf<String>()
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
                    frozenInputs.forEach { input ->
                        val callStartedNs = SystemClock.elapsedRealtimeNanos()
                        val hypothesis = it.transcribe(input.samples, CanonicalPcmProfile.SAMPLE_RATE_HZ)
                        asrTimeMillis += elapsedMillis(callStartedNs)
                        asrCallCount += 1
                        if (hypothesis.text.isBlank()) blankCallCount += 1
                        texts += hypothesis.text.trim()
                        tokenCount += hypothesis.tokens.size
                        hypothesis.detectedLanguage?.takeIf(String::isNotBlank)?.let(languages::add)
                        updatePeak(peakPssKb, Debug.getPss().toLong())
                    }
                }
                val rawText = texts.filter(String::isNotBlank).joinToString("\n")
                val quality = b1Profile?.let { scoreStage16BGateBB1(it, rawText) }
                val asrTotalWithOpen = asrTimeMillis + checkNotNull(engineOpenTimeMillis)
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
                        accuracy =
                            quality?.let {
                                AsrAccuracyMetrics(
                                    character = it.character,
                                    word = it.word,
                                )
                            },
                        notes =
                            buildList {
                                add("gate=${if (b1Profile == null) "B0" else "B1"}")
                                b1Profile?.let { add("profile=${it.name}") }
                                add("fsmn=frozen-once")
                                add("external-punctuation=off")
                                add("timeline-alignment=off")
                                add("threads=$numThreads")
                                add("engineOpenMs=$engineOpenTimeMillis")
                                add("asrCalls=$asrCallCount")
                                add("blankCalls=$blankCallCount")
                            },
                    )
                Stage16BGateBB0ModelObservation(
                    modelId = model.descriptor.modelId,
                    benchmark = benchmark,
                    startingPssKb = startingPssKb,
                    engineOpenTimeMillis = engineOpenTimeMillis,
                    asrCallCount = asrCallCount,
                    blankCallCount = blankCallCount,
                    rawText = rawText,
                    detectedLanguages = languages.toList(),
                    errorMessage = null,
                    b1Quality = quality,
                )
            } catch (error: Exception) {
                val rawText = texts.filter(String::isNotBlank).joinToString("\n")
                Stage16BGateBB0ModelObservation(
                    modelId = model.descriptor.modelId,
                    benchmark = null,
                    startingPssKb = startingPssKb,
                    engineOpenTimeMillis = engineOpenTimeMillis,
                    asrCallCount = asrCallCount,
                    blankCallCount = blankCallCount,
                    rawText = rawText,
                    detectedLanguages = languages.toList(),
                    errorMessage = error.message ?: error::class.java.simpleName,
                    b1Quality = b1Profile?.let { scoreStage16BGateBB1(it, rawText) },
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
            "Gate B short benchmark requires canonical 16 kHz PCM"
        }
        require(channelCount == CanonicalPcmProfile.CHANNEL_COUNT) {
            "Gate B short benchmark requires canonical mono PCM"
        }
        require(totalSampleCount > 0L) { "canonical PCM is empty" }
        val maxSamples =
            CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() *
                Stage16BGateBBenchmarkContract.MAX_B0_RECORDING_SECONDS
        require(totalSampleCount <= maxSamples) {
            "B0/B1 is intentionally limited to ${Stage16BGateBBenchmarkContract.MAX_B0_RECORDING_SECONDS}s; use B6 for long files"
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
