package io.github.ioannes78.voica

import android.app.Application
import android.os.SystemClock
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelLease
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.sherpa.LargeOfflineAsrSettings
import io.github.ioannes78.voica.sherpa.SenseVoiceSettings
import io.github.ioannes78.voica.sherpa.SherpaSenseVoiceEngine
import io.github.ioannes78.voica.sherpa.createSherpaLargeOfflineAsrEngine
import io.github.ioannes78.voica.transcript.AsrBenchmarkScoring
import io.github.ioannes78.voica.transcript.BenchmarkComponentIdentity
import io.github.ioannes78.voica.transcript.SecondPassAsrEngineFactory
import io.github.ioannes78.voica.transcript.SpeechSegment
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val STAGE16B_GATE_B_B3_MAX_RECORDING_SECONDS = 60
internal const val STAGE16B_GATE_B_B3_MAX_ASR_INPUT_SECONDS = 25

/** Controlled B3 challenge profiles. Audio remains user-recorded; these strings are the QA script. */
enum class Stage16BGateB3Profile(
    val instruction: String,
    val referenceText: String? = null,
    val expectsNoSpeech: Boolean = false,
) {
    PUNCTUATION_ZH(
        instruction = "自然朗读并按标点停顿、问句上扬。",
        referenceText = "今天开会讨论三件事：第一，预算；第二，进度；第三，风险。你觉得哪一项最重要？",
    ),
    ITN_COMPLEX(
        instruction = "自然朗读数字、日期、时间、金额、百分比和电话号码。",
        referenceText = "今天是二零二六年十月二十日，会议下午三点二十五分开始，预算是十二万三千四百五十六元，完成率百分之八十七点五，联系电话是一三八零零一三八零零零。",
    ),
    LANG_ZH(
        instruction = "只读中文。",
        referenceText = "今天我们只测试中文语音识别，请完整记录这句话。",
    ),
    LANG_EN(
        instruction = "Read only English.",
        referenceText = "Today we are testing English speech recognition. Please transcribe this sentence completely.",
    ),
    LANG_MIXED(
        instruction = "自然中英混读。",
        referenceText = "今天用 Android 和 OpenAI 测试 offline ASR，版本是 Qwen3。",
    ),
    SHORT_UTTERANCE(
        instruction = "只说一个很短的回答：可以。",
        referenceText = "可以。",
    ),
    SILENCE(
        instruction = "录制约 5 秒纯静音，不要说话。",
        expectsNoSpeech = true,
    ),
    NOISE(
        instruction = "录制约 5 秒普通环境噪声，不要说话。",
        expectsNoSpeech = true,
    ),
}

internal data class Stage16BGateB3VariantPlan(
    val modelId: String,
    val label: String,
    val requestedLanguage: String? = null,
    val useInverseTextNormalization: Boolean? = null,
    val capabilityNote: String,
)

internal fun stage16BGateB3Plans(profile: Stage16BGateB3Profile): List<Stage16BGateB3VariantPlan> =
    when (profile) {
        Stage16BGateB3Profile.ITN_COMPLEX ->
            listOf(
                Stage16BGateB3VariantPlan(
                    STAGE16B_FUNASR_NANO_CANDIDATE_ID,
                    "ITN_ON",
                    requestedLanguage = "",
                    useInverseTextNormalization = true,
                    capabilityNote = "FunASR native ITN enabled",
                ),
                Stage16BGateB3VariantPlan(
                    STAGE16B_FUNASR_NANO_CANDIDATE_ID,
                    "ITN_OFF",
                    requestedLanguage = "",
                    useInverseTextNormalization = false,
                    capabilityNote = "FunASR native ITN disabled",
                ),
                Stage16BGateB3VariantPlan(
                    Stage8ModelIds.SECOND_PASS_ASR,
                    "ITN_ON",
                    requestedLanguage = "",
                    useInverseTextNormalization = true,
                    capabilityNote = "SenseVoice ITN enabled",
                ),
                Stage16BGateB3VariantPlan(
                    Stage8ModelIds.SECOND_PASS_ASR,
                    "ITN_OFF",
                    requestedLanguage = "",
                    useInverseTextNormalization = false,
                    capabilityNote = "SenseVoice ITN disabled",
                ),
                Stage16BGateB3VariantPlan(
                    Stage13AOfflineModelIds.QWEN3_ASR,
                    "NATIVE_NO_ITN_TOGGLE",
                    capabilityNote = "Qwen3 descriptor exposes no ITN toggle; default native behavior only",
                ),
            )

        Stage16BGateB3Profile.LANG_ZH,
        Stage16BGateB3Profile.LANG_EN,
        Stage16BGateB3Profile.LANG_MIXED,
        ->
            listOf(
                Stage16BGateB3VariantPlan(
                    STAGE16B_FUNASR_NANO_CANDIDATE_ID,
                    "LANG_AUTO",
                    requestedLanguage = "",
                    useInverseTextNormalization = true,
                    capabilityNote = "FunASR language auto",
                ),
                Stage16BGateB3VariantPlan(
                    STAGE16B_FUNASR_NANO_CANDIDATE_ID,
                    "LANG_FORCE_ZH",
                    requestedLanguage = "zh",
                    useInverseTextNormalization = true,
                    capabilityNote = "FunASR language force zh",
                ),
                Stage16BGateB3VariantPlan(
                    STAGE16B_FUNASR_NANO_CANDIDATE_ID,
                    "LANG_FORCE_EN",
                    requestedLanguage = "en",
                    useInverseTextNormalization = true,
                    capabilityNote = "FunASR language force en",
                ),
                Stage16BGateB3VariantPlan(
                    Stage8ModelIds.SECOND_PASS_ASR,
                    "LANG_AUTO",
                    requestedLanguage = "",
                    useInverseTextNormalization = false,
                    capabilityNote = "SenseVoice language auto",
                ),
                Stage16BGateB3VariantPlan(
                    Stage8ModelIds.SECOND_PASS_ASR,
                    "LANG_FORCE_ZH",
                    requestedLanguage = "zh",
                    useInverseTextNormalization = false,
                    capabilityNote = "SenseVoice language force zh",
                ),
                Stage16BGateB3VariantPlan(
                    Stage8ModelIds.SECOND_PASS_ASR,
                    "LANG_FORCE_EN",
                    requestedLanguage = "en",
                    useInverseTextNormalization = false,
                    capabilityNote = "SenseVoice language force en",
                ),
                Stage16BGateB3VariantPlan(
                    Stage13AOfflineModelIds.QWEN3_ASR,
                    "DEFAULT_NO_LANGUAGE_FORCE",
                    capabilityNote = "Qwen3 descriptor exposes no language-forcing control",
                ),
            )

        else ->
            listOf(
                Stage16BGateB3VariantPlan(
                    STAGE16B_FUNASR_NANO_CANDIDATE_ID,
                    "DEFAULT_NATIVE",
                    requestedLanguage = "",
                    useInverseTextNormalization = true,
                    capabilityNote = "FunASR native defaults",
                ),
                Stage16BGateB3VariantPlan(
                    Stage8ModelIds.SECOND_PASS_ASR,
                    "DEFAULT_NATIVE",
                    requestedLanguage = "",
                    useInverseTextNormalization = false,
                    capabilityNote = "SenseVoice native output; external punctuation disabled",
                ),
                Stage16BGateB3VariantPlan(
                    Stage13AOfflineModelIds.QWEN3_ASR,
                    "DEFAULT_NATIVE",
                    capabilityNote = "Qwen3 native defaults",
                ),
            )
    }

data class Stage16BGateB3PunctuationMetrics(
    val commaCount: Int,
    val sentenceEndCount: Int,
    val questionMarkCount: Int,
    val exclamationMarkCount: Int,
    val colonSemicolonCount: Int,
)

internal fun stage16BGateB3PunctuationMetrics(text: String): Stage16BGateB3PunctuationMetrics =
    Stage16BGateB3PunctuationMetrics(
        commaCount = text.count { it == '，' || it == ',' },
        sentenceEndCount = text.count { it == '。' || it == '.' },
        questionMarkCount = text.count { it == '？' || it == '?' },
        exclamationMarkCount = text.count { it == '！' || it == '!' },
        colonSemicolonCount = text.count { it == '：' || it == ':' || it == '；' || it == ';' },
    )

private val stage16BItSemanticGroups: List<List<String>> =
    listOf(
        listOf("二零二六年十月二十日", "2026年10月20日", "20261020"),
        listOf("下午三点二十五分", "下午3点25分", "下午3:25", "15:25"),
        listOf("十二万三千四百五十六元", "123456元"),
        listOf("百分之八十七点五", "87.5%", "87.5", "875"),
        listOf("一三八零零一三八零零零", "13800138000"),
    )

internal fun stage16BGateB3SemanticCoverage(text: String): Double {
    val normalized = AsrBenchmarkScoring.normalizeForCharacterErrorRate(stage16BScoringText(text))
    val hits =
        stage16BItSemanticGroups.count { alternatives ->
            alternatives.any { alternative ->
                val token = AsrBenchmarkScoring.normalizeForCharacterErrorRate(alternative)
                token.isNotEmpty() && normalized.contains(token)
            }
        }
    return hits.toDouble() / stage16BItSemanticGroups.size.toDouble()
}

data class Stage16BGateB3VariantObservation(
    val modelId: String,
    val variantLabel: String,
    val capabilityNote: String,
    val engineOpenTimeMillis: Long?,
    val asrWallTimeMillis: Long,
    val asrCallCount: Int,
    val blankCallCount: Int,
    val rawText: String,
    val detectedLanguages: List<String>,
    val contentCer: Double?,
    val semanticCoverage: Double?,
    val punctuation: Stage16BGateB3PunctuationMetrics,
    val hallucinationNonBlank: Boolean,
    val errorMessage: String?,
) {
    val completed: Boolean
        get() = errorMessage == null && asrCallCount > 0
}

data class Stage16BGateB3Result(
    val recordingId: String,
    val canonicalSha256: String?,
    val profile: Stage16BGateB3Profile,
    val vad: BenchmarkComponentIdentity,
    val vadWallTimeMillis: Long,
    val speechSegments: List<SpeechSegment>,
    val frozenAsrInputBoundaries: List<SpeechSegment>,
    val vadFilteredNoSpeech: Boolean,
    val observations: List<Stage16BGateB3VariantObservation>,
) {
    val formalBaselineValid: Boolean
        get() = vad.id == Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID

    val executionComplete: Boolean
        get() =
            formalBaselineValid &&
                if (vadFilteredNoSpeech) {
                    profile.expectsNoSpeech && observations.isEmpty()
                } else {
                    observations.size == stage16BGateB3Plans(profile).size &&
                        observations.all(Stage16BGateB3VariantObservation::completed)
                }
}

sealed interface Stage16BGateB3State {
    data object Idle : Stage16BGateB3State

    data class Running(
        val recordingId: String,
        val phase: String,
    ) : Stage16BGateB3State

    data class Completed(
        val result: Stage16BGateB3Result,
    ) : Stage16BGateB3State

    data class Failed(
        val recordingId: String,
        val message: String,
        val missingModelIds: List<String> = emptyList(),
    ) : Stage16BGateB3State

    data class Cancelled(
        val recordingId: String,
    ) : Stage16BGateB3State
}

/** QA-only B3 runner for punctuation, ITN, language controls and hallucination probes. */
class Stage16BGateB3ChallengeRunner(
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
    private val vadEngineProvider =
        Stage16BLocalFileTranscriptionEngineProvider(
            delegate = baseEngineProvider,
            assetManager = application.assets,
        )
    private val mutableState = MutableStateFlow<Stage16BGateB3State>(Stage16BGateB3State.Idle)

    val state: StateFlow<Stage16BGateB3State> = mutableState.asStateFlow()

    fun start(
        recordingId: String,
        profile: Stage16BGateB3Profile,
    ): Boolean {
        require(recordingId.isNotBlank())
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B Gate B B3 benchmark is restricted to Debug/QA builds"
        }
        synchronized(lock) {
            if (currentJob?.isActive == true) return false
            val job = scope.launch { runBenchmark(recordingId, profile) }
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
            currentJob?.cancel(CancellationException("user cancelled Stage 16B Gate B B3 benchmark"))
        }
    }

    fun reset() {
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            mutableState.value = Stage16BGateB3State.Idle
        }
    }

    private suspend fun runBenchmark(
        recordingId: String,
        profile: Stage16BGateB3Profile,
    ) {
        try {
            mutableState.value = Stage16BGateB3State.Running(recordingId, "B3: Resolve strict FSMN baseline")
            val fsmn = modelManager.activeModel(Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID)
            if (fsmn == null) {
                mutableState.value =
                    Stage16BGateB3State.Failed(
                        recordingId,
                        "Gate B formal baseline requires active FSMN; Silero fallback is invalid",
                        listOf(Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID),
                    )
                return
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
                        vadEngineProvider.vadFactory(fsmn, threads, settings.vad).open().use { engine ->
                            engine.analyze(it)
                        }
                    }
                } finally {
                    fsmnLease.close()
                }
            val vadWallTimeMillis = elapsedMillis(vadStartedNs)
            val pcm = readCanonicalPcm(recordingId)
            val frozenInputs =
                buildStage16BGateBFrozenInputs(
                    pcm = pcm,
                    speechSegments = speechSegments,
                    maxChunkSamples =
                        CanonicalPcmProfile.SAMPLE_RATE_HZ * STAGE16B_GATE_B_B3_MAX_ASR_INPUT_SECONDS,
                )
            val vadIdentity = identity(fsmn)
            val canonicalSha256 = loadCanonicalSha256(recordingId)

            if (frozenInputs.isEmpty()) {
                if (!profile.expectsNoSpeech) {
                    error("FSMN produced no speech input for B3 profile ${profile.name}")
                }
                mutableState.value =
                    Stage16BGateB3State.Completed(
                        Stage16BGateB3Result(
                            recordingId = recordingId,
                            canonicalSha256 = canonicalSha256,
                            profile = profile,
                            vad = vadIdentity,
                            vadWallTimeMillis = vadWallTimeMillis,
                            speechSegments = speechSegments.toList(),
                            frozenAsrInputBoundaries = emptyList(),
                            vadFilteredNoSpeech = true,
                            observations = emptyList(),
                        ),
                    )
                return
            }

            val observations = mutableListOf<Stage16BGateB3VariantObservation>()
            for (plan in stage16BGateB3Plans(profile)) {
                mutableState.value =
                    Stage16BGateB3State.Running(
                        recordingId,
                        "B3 ${profile.name}: ${plan.modelId}/${plan.label}",
                    )
                val model = modelManager.activeModel(plan.modelId)
                if (model == null) {
                    observations +=
                        Stage16BGateB3VariantObservation(
                            modelId = plan.modelId,
                            variantLabel = plan.label,
                            capabilityNote = plan.capabilityNote,
                            engineOpenTimeMillis = null,
                            asrWallTimeMillis = 0,
                            asrCallCount = 0,
                            blankCallCount = 0,
                            rawText = "",
                            detectedLanguages = emptyList(),
                            contentCer = null,
                            semanticCoverage = null,
                            punctuation = stage16BGateB3PunctuationMetrics(""),
                            hallucinationNonBlank = false,
                            errorMessage = "model is not active/installed",
                        )
                    continue
                }
                observations +=
                    runVariant(
                        profile = profile,
                        model = model,
                        plan = plan,
                        frozenInputs = frozenInputs,
                        numThreads = threads,
                    )
            }

            mutableState.value =
                Stage16BGateB3State.Completed(
                    Stage16BGateB3Result(
                        recordingId = recordingId,
                        canonicalSha256 = canonicalSha256,
                        profile = profile,
                        vad = vadIdentity,
                        vadWallTimeMillis = vadWallTimeMillis,
                        speechSegments = speechSegments.toList(),
                        frozenAsrInputBoundaries =
                            frozenInputs.map { SpeechSegment(it.startSampleIndex, it.endSampleIndexExclusive) },
                        vadFilteredNoSpeech = false,
                        observations = observations.toList(),
                    ),
                )
        } catch (cancelled: CancellationException) {
            mutableState.value = Stage16BGateB3State.Cancelled(recordingId)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value =
                Stage16BGateB3State.Failed(
                    recordingId = recordingId,
                    message = error.message ?: error::class.java.simpleName,
                )
        }
    }

    private suspend fun runVariant(
        profile: Stage16BGateB3Profile,
        model: ActiveModel,
        plan: Stage16BGateB3VariantPlan,
        frozenInputs: List<Stage16BGateBFrozenInput>,
        numThreads: Int,
    ): Stage16BGateB3VariantObservation {
        val lease = acquire(model)
        var engineOpenTimeMillis: Long? = null
        var asrWallTimeMillis = 0L
        var asrCallCount = 0
        var blankCallCount = 0
        val texts = mutableListOf<String>()
        val languages = linkedSetOf<String>()
        try {
            val factory = createB3Factory(model, plan, numThreads)
            val openStartedNs = SystemClock.elapsedRealtimeNanos()
            val engine = factory.open()
            engineOpenTimeMillis = elapsedMillis(openStartedNs)
            engine.use {
                frozenInputs.forEach { input ->
                    val callStartedNs = SystemClock.elapsedRealtimeNanos()
                    val hypothesis = it.transcribe(input.samples, CanonicalPcmProfile.SAMPLE_RATE_HZ)
                    asrWallTimeMillis += elapsedMillis(callStartedNs)
                    asrCallCount += 1
                    val content = stage16BScoringText(hypothesis.text).trim()
                    if (content.isBlank()) blankCallCount += 1
                    texts += hypothesis.text.trim()
                    hypothesis.detectedLanguage?.takeIf(String::isNotBlank)?.let(languages::add)
                }
            }
            val rawText = texts.filter(String::isNotBlank).joinToString("\n")
            val contentText = stage16BScoringText(rawText).trim()
            val contentCer =
                if (profile.referenceText != null && profile != Stage16BGateB3Profile.ITN_COMPLEX) {
                    AsrBenchmarkScoring.characterErrorRate(profile.referenceText, contentText).errorRate
                } else {
                    null
                }
            val semanticCoverage =
                if (profile == Stage16BGateB3Profile.ITN_COMPLEX) {
                    stage16BGateB3SemanticCoverage(contentText)
                } else {
                    null
                }
            return Stage16BGateB3VariantObservation(
                modelId = model.descriptor.modelId,
                variantLabel = plan.label,
                capabilityNote = plan.capabilityNote,
                engineOpenTimeMillis = engineOpenTimeMillis,
                asrWallTimeMillis = asrWallTimeMillis,
                asrCallCount = asrCallCount,
                blankCallCount = blankCallCount,
                rawText = rawText,
                detectedLanguages = languages.toList(),
                contentCer = contentCer,
                semanticCoverage = semanticCoverage,
                punctuation = stage16BGateB3PunctuationMetrics(rawText),
                hallucinationNonBlank = profile.expectsNoSpeech && contentText.isNotBlank(),
                errorMessage = null,
            )
        } catch (error: Exception) {
            val rawText = texts.filter(String::isNotBlank).joinToString("\n")
            return Stage16BGateB3VariantObservation(
                modelId = model.descriptor.modelId,
                variantLabel = plan.label,
                capabilityNote = plan.capabilityNote,
                engineOpenTimeMillis = engineOpenTimeMillis,
                asrWallTimeMillis = asrWallTimeMillis,
                asrCallCount = asrCallCount,
                blankCallCount = blankCallCount,
                rawText = rawText,
                detectedLanguages = languages.toList(),
                contentCer = null,
                semanticCoverage = null,
                punctuation = stage16BGateB3PunctuationMetrics(rawText),
                hallucinationNonBlank = profile.expectsNoSpeech && stage16BScoringText(rawText).isNotBlank(),
                errorMessage = error.message ?: error::class.java.simpleName,
            )
        } finally {
            lease.close()
        }
    }

    private fun createB3Factory(
        model: ActiveModel,
        plan: Stage16BGateB3VariantPlan,
        numThreads: Int,
    ): SecondPassAsrEngineFactory {
        val directory =
            model.installedDirectory
                ?: error("B3 ASR must be an installed managed model")
        return when (model.descriptor.modelId) {
            Stage8ModelIds.SECOND_PASS_ASR ->
                SecondPassAsrEngineFactory {
                    SherpaSenseVoiceEngine(
                        model = model.descriptor,
                        modelDirectory = directory,
                        settings =
                            SenseVoiceSettings(
                                numThreads = numThreads,
                                language = plan.requestedLanguage.orEmpty(),
                                useInverseTextNormalization =
                                    plan.useInverseTextNormalization ?: false,
                            ),
                    )
                }

            STAGE16B_FUNASR_NANO_CANDIDATE_ID ->
                SecondPassAsrEngineFactory {
                    createSherpaLargeOfflineAsrEngine(
                        model = model.descriptor,
                        modelDirectory = directory,
                        settings =
                            LargeOfflineAsrSettings(
                                numThreads = numThreads,
                                funAsrLanguage = plan.requestedLanguage.orEmpty(),
                                funAsrUseInverseTextNormalization =
                                    plan.useInverseTextNormalization ?: true,
                            ),
                    )
                }

            Stage13AOfflineModelIds.QWEN3_ASR -> {
                val defaults = LocalQwenAsrSettings()
                SecondPassAsrEngineFactory {
                    createSherpaLargeOfflineAsrEngine(
                        model = model.descriptor,
                        modelDirectory = directory,
                        settings =
                            LargeOfflineAsrSettings(
                                numThreads = numThreads,
                                qwenMaxTotalLen = defaults.maxTotalLen,
                                qwenMaxNewTokens = defaults.maxNewTokens,
                                qwenTemperature = defaults.temperature,
                                qwenTopP = defaults.topP,
                                qwenSeed = defaults.seed,
                                qwenHotwords = defaults.hotwords,
                            ),
                    )
                }
            }

            else -> error("unsupported B3 model ${model.descriptor.modelId}")
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
            "Gate B B3 requires canonical 16 kHz PCM"
        }
        require(channelCount == CanonicalPcmProfile.CHANNEL_COUNT) {
            "Gate B B3 requires canonical mono PCM"
        }
        require(totalSampleCount > 0L) { "canonical PCM is empty" }
        val maxSamples =
            CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * STAGE16B_GATE_B_B3_MAX_RECORDING_SECONDS
        require(totalSampleCount <= maxSamples) {
            "B3 challenge recordings are limited to ${STAGE16B_GATE_B_B3_MAX_RECORDING_SECONDS}s"
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

    private companion object {
        fun elapsedMillis(startedNs: Long): Long =
            (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L) / 1_000_000L
    }
}
