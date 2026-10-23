package io.github.ioannes78.voica

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelLease
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.sherpa.LargeOfflineAsrSettings
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal const val STAGE16B_GATE_B_B3_HOTWORD_REFERENCE =
    "今天测试 Qwen3、OpenAI 和 GitHub 的语音识别效果。"
internal const val STAGE16B_GATE_B_B3_HOTWORDS = "Qwen3,OpenAI,GitHub"
private const val STAGE16B_GATE_B_B3_HOTWORD_MAX_RECORDING_SECONDS = 60
private const val STAGE16B_GATE_B_B3_HOTWORD_MAX_ASR_INPUT_SECONDS = 25

internal data class Stage16BGateB3HotwordVariantPlan(
    val modelId: String,
    val label: String,
    val hotwords: String,
)

internal fun stage16BGateB3HotwordPlans(): List<Stage16BGateB3HotwordVariantPlan> =
    listOf(
        Stage16BGateB3HotwordVariantPlan(
            modelId = STAGE16B_FUNASR_NANO_CANDIDATE_ID,
            label = "HOTWORDS_OFF",
            hotwords = "",
        ),
        Stage16BGateB3HotwordVariantPlan(
            modelId = STAGE16B_FUNASR_NANO_CANDIDATE_ID,
            label = "HOTWORDS_ON",
            hotwords = STAGE16B_GATE_B_B3_HOTWORDS,
        ),
        Stage16BGateB3HotwordVariantPlan(
            modelId = Stage13AOfflineModelIds.QWEN3_ASR,
            label = "HOTWORDS_OFF",
            hotwords = "",
        ),
        Stage16BGateB3HotwordVariantPlan(
            modelId = Stage13AOfflineModelIds.QWEN3_ASR,
            label = "HOTWORDS_ON",
            hotwords = STAGE16B_GATE_B_B3_HOTWORDS,
        ),
    )

internal data class Stage16BGateB3HotwordObservation(
    val modelId: String,
    val label: String,
    val hotwords: String,
    val engineOpenMillis: Long?,
    val asrWallMillis: Long,
    val asrCalls: Int,
    val blankCalls: Int,
    val rawText: String,
    val contentCer: Double?,
    val hotwordHits: List<String>,
    val errorMessage: String?,
) {
    val completed: Boolean
        get() = errorMessage == null && asrCalls > 0
}

internal fun stage16BGateB3HotwordHits(text: String): List<String> {
    val normalized = text.lowercase().filterNot(Char::isWhitespace)
    return STAGE16B_GATE_B_B3_HOTWORDS.split(',').filter { hotword ->
        normalized.contains(hotword.lowercase().filterNot(Char::isWhitespace))
    }
}

internal data class Stage16BGateB3HotwordResult(
    val recordingId: String,
    val canonicalSha256: String?,
    val vad: BenchmarkComponentIdentity,
    val vadWallMillis: Long,
    val speechSegments: List<SpeechSegment>,
    val frozenBoundaries: List<SpeechSegment>,
    val observations: List<Stage16BGateB3HotwordObservation>,
) {
    val executionComplete: Boolean
        get() =
            vad.id == Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID &&
                frozenBoundaries.isNotEmpty() &&
                observations.size == stage16BGateB3HotwordPlans().size &&
                observations.all(Stage16BGateB3HotwordObservation::completed)
}

internal sealed interface Stage16BGateB3HotwordState {
    data object Idle : Stage16BGateB3HotwordState

    data class Running(
        val recordingId: String,
        val phase: String,
    ) : Stage16BGateB3HotwordState

    data class Completed(
        val result: Stage16BGateB3HotwordResult,
    ) : Stage16BGateB3HotwordState

    data class Failed(
        val recordingId: String,
        val message: String,
    ) : Stage16BGateB3HotwordState

    data class Cancelled(
        val recordingId: String,
    ) : Stage16BGateB3HotwordState
}

internal class Stage16BGateB3HotwordRunner(
    private val application: Application,
    private val pcmSourceResolver: PcmSourceResolver,
    private val modelManager: ModelManager,
    private val modelUseRegistry: ModelUseRegistry,
    private val localSpeechSettings: () -> LocalSpeechSettings,
    private val loadCanonicalSha256: suspend (String) -> String?,
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
    private val mutableState = MutableStateFlow<Stage16BGateB3HotwordState>(Stage16BGateB3HotwordState.Idle)

    val state: StateFlow<Stage16BGateB3HotwordState> = mutableState.asStateFlow()

    fun start(recordingId: String): Boolean {
        require(recordingId.isNotBlank())
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B Gate B3 hotword benchmark is restricted to Debug/QA builds"
        }
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
            currentJob?.cancel(CancellationException("user cancelled B3 hotword benchmark"))
        }
    }

    fun reset() {
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            mutableState.value = Stage16BGateB3HotwordState.Idle
        }
    }

    private suspend fun runBenchmark(recordingId: String) {
        try {
            mutableState.value = Stage16BGateB3HotwordState.Running(recordingId, "Resolve strict FSMN baseline")
            val fsmn = modelManager.activeModel(Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID)
                ?: error("formal hotword A/B requires active FSMN; Silero fallback is invalid")
            val settings = localSpeechSettings()
            val threads = settings.resolvePerformance().effectiveThreads
            val fsmnLease = acquire(fsmn)
            val vadStartedNs = SystemClock.elapsedRealtimeNanos()
            val speechSegments =
                try {
                    val source = pcmSourceResolver.resolvePcmSource(recordingId)
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
            val vadWallMillis = elapsedMillis(vadStartedNs)
            val pcm = readCanonicalPcm(recordingId)
            val frozenInputs =
                buildStage16BGateBFrozenInputs(
                    pcm = pcm,
                    speechSegments = speechSegments,
                    maxChunkSamples =
                        CanonicalPcmProfile.SAMPLE_RATE_HZ * STAGE16B_GATE_B_B3_HOTWORD_MAX_ASR_INPUT_SECONDS,
                )
            require(frozenInputs.isNotEmpty()) { "FSMN produced no speech input for hotword A/B" }

            val observations = mutableListOf<Stage16BGateB3HotwordObservation>()
            for (plan in stage16BGateB3HotwordPlans()) {
                mutableState.value =
                    Stage16BGateB3HotwordState.Running(
                        recordingId,
                        "${shortModelName(plan.modelId)}/${plan.label}",
                    )
                val model = modelManager.activeModel(plan.modelId)
                if (model == null) {
                    observations +=
                        Stage16BGateB3HotwordObservation(
                            modelId = plan.modelId,
                            label = plan.label,
                            hotwords = plan.hotwords,
                            engineOpenMillis = null,
                            asrWallMillis = 0,
                            asrCalls = 0,
                            blankCalls = 0,
                            rawText = "",
                            contentCer = null,
                            hotwordHits = emptyList(),
                            errorMessage = "model is not active/installed",
                        )
                    continue
                }
                observations += runVariant(model, plan, frozenInputs, threads)
            }

            mutableState.value =
                Stage16BGateB3HotwordState.Completed(
                    Stage16BGateB3HotwordResult(
                        recordingId = recordingId,
                        canonicalSha256 = loadCanonicalSha256(recordingId),
                        vad = identity(fsmn),
                        vadWallMillis = vadWallMillis,
                        speechSegments = speechSegments.toList(),
                        frozenBoundaries = frozenInputs.map {
                            SpeechSegment(it.startSampleIndex, it.endSampleIndexExclusive)
                        },
                        observations = observations,
                    ),
                )
        } catch (cancelled: CancellationException) {
            mutableState.value = Stage16BGateB3HotwordState.Cancelled(recordingId)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value =
                Stage16BGateB3HotwordState.Failed(
                    recordingId = recordingId,
                    message = error.message ?: error::class.java.simpleName,
                )
        }
    }

    private suspend fun runVariant(
        model: ActiveModel,
        plan: Stage16BGateB3HotwordVariantPlan,
        frozenInputs: List<Stage16BGateBFrozenInput>,
        numThreads: Int,
    ): Stage16BGateB3HotwordObservation {
        val lease = acquire(model)
        var engineOpenMillis: Long? = null
        var asrWallMillis = 0L
        var asrCalls = 0
        var blankCalls = 0
        val texts = mutableListOf<String>()
        try {
            val factory = createFactory(model, plan, numThreads)
            val openStartedNs = SystemClock.elapsedRealtimeNanos()
            val engine = factory.open()
            engineOpenMillis = elapsedMillis(openStartedNs)
            engine.use {
                frozenInputs.forEach { input ->
                    val callStartedNs = SystemClock.elapsedRealtimeNanos()
                    val hypothesis = it.transcribe(input.samples, CanonicalPcmProfile.SAMPLE_RATE_HZ)
                    asrWallMillis += elapsedMillis(callStartedNs)
                    asrCalls += 1
                    val text = hypothesis.text.trim()
                    if (stage16BScoringText(text).isBlank()) blankCalls += 1
                    texts += text
                }
            }
            val rawText = texts.filter(String::isNotBlank).joinToString("\n")
            val scoredText = stage16BScoringText(rawText)
            return Stage16BGateB3HotwordObservation(
                modelId = model.descriptor.modelId,
                label = plan.label,
                hotwords = plan.hotwords,
                engineOpenMillis = engineOpenMillis,
                asrWallMillis = asrWallMillis,
                asrCalls = asrCalls,
                blankCalls = blankCalls,
                rawText = rawText,
                contentCer =
                    AsrBenchmarkScoring.characterErrorRate(
                        STAGE16B_GATE_B_B3_HOTWORD_REFERENCE,
                        scoredText,
                    ).errorRate,
                hotwordHits = stage16BGateB3HotwordHits(scoredText),
                errorMessage = null,
            )
        } catch (error: Exception) {
            return Stage16BGateB3HotwordObservation(
                modelId = model.descriptor.modelId,
                label = plan.label,
                hotwords = plan.hotwords,
                engineOpenMillis = engineOpenMillis,
                asrWallMillis = asrWallMillis,
                asrCalls = asrCalls,
                blankCalls = blankCalls,
                rawText = texts.filter(String::isNotBlank).joinToString("\n"),
                contentCer = null,
                hotwordHits = emptyList(),
                errorMessage = error.message ?: error::class.java.simpleName,
            )
        } finally {
            lease.close()
        }
    }

    private fun createFactory(
        model: ActiveModel,
        plan: Stage16BGateB3HotwordVariantPlan,
        numThreads: Int,
    ): SecondPassAsrEngineFactory {
        val directory = model.installedDirectory
            ?: error("B3 hotword ASR must be an installed managed model")
        return when (model.descriptor.modelId) {
            STAGE16B_FUNASR_NANO_CANDIDATE_ID ->
                SecondPassAsrEngineFactory {
                    createSherpaLargeOfflineAsrEngine(
                        model = model.descriptor,
                        modelDirectory = directory,
                        settings =
                            LargeOfflineAsrSettings(
                                numThreads = numThreads,
                                funAsrLanguage = "",
                                funAsrUseInverseTextNormalization = true,
                                funAsrHotwords = plan.hotwords,
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
                                qwenHotwords = plan.hotwords,
                            ),
                    )
                }
            }

            else -> error("unsupported B3 hotword model ${model.descriptor.modelId}")
        }
    }

    private suspend fun readCanonicalPcm(recordingId: String): ShortArray {
        val source = pcmSourceResolver.resolvePcmSource(recordingId)
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
            "B3 hotword benchmark requires canonical 16 kHz PCM"
        }
        require(channelCount == CanonicalPcmProfile.CHANNEL_COUNT) {
            "B3 hotword benchmark requires canonical mono PCM"
        }
        require(totalSampleCount > 0L) { "canonical PCM is empty" }
        val maxSamples =
            CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() *
                STAGE16B_GATE_B_B3_HOTWORD_MAX_RECORDING_SECONDS
        require(totalSampleCount <= maxSamples) {
            "B3 hotword recording is limited to ${STAGE16B_GATE_B_B3_HOTWORD_MAX_RECORDING_SECONDS}s"
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

    private fun shortModelName(modelId: String): String =
        when (modelId) {
            STAGE16B_FUNASR_NANO_CANDIDATE_ID -> "FunASR"
            Stage13AOfflineModelIds.QWEN3_ASR -> "Qwen3"
            else -> modelId
        }

    private companion object {
        fun elapsedMillis(startedNs: Long): Long =
            (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L) / 1_000_000L
    }
}

/** QA/releaseQa-only launcher for the final B3 hotword/context-bias A/B. */
class Stage16BGateB3HotwordActivity : ComponentActivity() {
    private lateinit var runner: Stage16BGateB3HotwordRunner
    private lateinit var statusText: TextView
    private lateinit var recordingsContainer: LinearLayout
    private var recordingsJob: Job? = null
    private var stateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VoicaApplication
        check(VoicaModelChannel.isDebuggable(app)) {
            "Stage 16B Gate B3 hotword activity must never run in a production build"
        }
        val container = app.container
        runner =
            Stage16BGateB3HotwordRunner(
                application = app,
                pcmSourceResolver = container.pcmSourceResolver,
                modelManager = container.modelManager,
                modelUseRegistry = container.modelUseRegistry,
                localSpeechSettings = { container.localSpeechSettingsStore.settings.value },
                loadCanonicalSha256 = { recordingId ->
                    container.recordingLibraryRepository
                        .loadCanonicalTranscriptionLineage(
                            recordingId = recordingId,
                            profileId = CanonicalPcmProfile.PROFILE_ID,
                        )
                        ?.canonicalSha256
                },
            )
        setContentView(buildContentView())
        recordingsJob =
            lifecycleScope.launch {
                container.recordingLibraryRepository.recordings.collectLatest(::renderRecordings)
            }
        stateJob =
            lifecycleScope.launch {
                runner.state.collectLatest { statusText.text = formatState(it) }
            }
    }

    override fun onDestroy() {
        recordingsJob?.cancel()
        stateJob?.cancel()
        if (isFinishing) runner.cancel()
        super.onDestroy()
    }

    private fun buildContentView(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }
        root.addView(TextView(this).apply {
            text = "Stage 16B — Gate B3 Hotword A/B"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text =
                "QA-only. Same exact FSMN-frozen PCM is reused for FunASR and Qwen3 with hotwords OFF/ON. " +
                    "SenseVoice is excluded because this integration does not expose an equivalent hotword control."
        })
        root.addView(TextView(this).apply {
            setPadding(0, padding, 0, padding)
            text = "Instruction: 自然朗读下面一句。\nReference: $STAGE16B_GATE_B_B3_HOTWORD_REFERENCE\nHotwords: $STAGE16B_GATE_B_B3_HOTWORDS"
        })
        statusText =
            TextView(this).apply {
                setPadding(0, padding, 0, padding)
                movementMethod = ScrollingMovementMethod()
                text = "Idle"
            }
        root.addView(statusText)
        root.addView(Button(this).apply {
            text = "Cancel current hotword run"
            setOnClickListener { runner.cancel() }
        })
        root.addView(Button(this).apply {
            text = "Reset result"
            setOnClickListener { runner.reset() }
        })
        root.addView(TextView(this).apply {
            setPadding(0, padding, 0, padding / 2)
            text = "Recent local recordings — tap to run hotword A/B"
            textSize = 18f
        })
        recordingsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            recordingsContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return ScrollView(this).apply { addView(root) }
    }

    private fun renderRecordings(recordings: List<RecordingLibraryItem>) {
        recordingsContainer.removeAllViews()
        val recent = recordings.sortedByDescending(RecordingLibraryItem::updatedAtMs).take(MAX_RECORDINGS)
        if (recent.isEmpty()) {
            recordingsContainer.addView(TextView(this).apply { text = "No local recordings." })
            return
        }
        recent.forEach { recording ->
            recordingsContainer.addView(Button(this).apply {
                text = "${recording.displayName}\n${recording.id}"
                isAllCaps = false
                setOnClickListener { runner.start(recording.id) }
            })
        }
    }

    private fun formatState(state: Stage16BGateB3HotwordState): String =
        when (state) {
            Stage16BGateB3HotwordState.Idle -> "Idle"
            is Stage16BGateB3HotwordState.Running ->
                "Running\nrecording=${state.recordingId}\nphase=${state.phase}"
            is Stage16BGateB3HotwordState.Cancelled -> "Cancelled\nrecording=${state.recordingId}"
            is Stage16BGateB3HotwordState.Failed ->
                "B3 HOTWORD FAILED\nrecording=${state.recordingId}\n${state.message}"
            is Stage16BGateB3HotwordState.Completed -> formatResult(state.result)
        }

    private fun formatResult(result: Stage16BGateB3HotwordResult): String =
        buildString {
            appendLine(if (result.executionComplete) "B3 HOTWORD COMPLETE" else "B3 HOTWORD INCOMPLETE")
            appendLine("reference=$STAGE16B_GATE_B_B3_HOTWORD_REFERENCE")
            appendLine("hotwords=$STAGE16B_GATE_B_B3_HOTWORDS")
            appendLine("recording=${result.recordingId}")
            appendLine("canonicalSha256=${result.canonicalSha256 ?: "unknown"}")
            appendLine("vad=${result.vad.id}@${result.vad.version}/${result.vad.revision}")
            appendLine("vadWallMs=${result.vadWallMillis}")
            appendLine("speechSegments=${result.speechSegments.size}")
            appendLine("frozenAsrInputs=${result.frozenBoundaries.size}")
            appendLine("maxAsrInputSeconds=$STAGE16B_GATE_B_B3_HOTWORD_MAX_ASR_INPUT_SECONDS")
            result.observations.forEach { observation ->
                appendLine()
                appendLine("[${shortModelName(observation.modelId)}/${observation.label}] ${if (observation.completed) "COMPLETE" else "ERROR"}")
                appendLine("configuredHotwords=${observation.hotwords.ifBlank { "<none>" }}")
                observation.errorMessage?.let { appendLine("error=$it") }
                appendLine("engineOpenMs=${observation.engineOpenMillis ?: "-"}")
                appendLine("asrWallMs=${observation.asrWallMillis}")
                appendLine("asrCalls=${observation.asrCalls}")
                appendLine("blankCalls=${observation.blankCalls}")
                observation.contentCer?.let { appendLine("contentCer=${"%.4f".format(it)}") }
                appendLine("hotwordCoverage=${observation.hotwordHits.size}/3")
                appendLine("hotwordHits=${observation.hotwordHits.joinToString().ifBlank { "<none>" }}")
                appendLine("text=${observation.rawText.ifBlank { "<blank>" }}")
            }
        }.trimEnd()

    private fun shortModelName(modelId: String): String =
        when (modelId) {
            STAGE16B_FUNASR_NANO_CANDIDATE_ID -> "FunASR"
            Stage13AOfflineModelIds.QWEN3_ASR -> "Qwen3"
            else -> modelId
        }

    private companion object {
        const val MAX_RECORDINGS = 20
    }
}
