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
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelLease
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.transcript.BenchmarkComponentIdentity
import io.github.ioannes78.voica.transcript.HighQualityTranscriptionPipeline
import io.github.ioannes78.voica.transcript.ProgressListener
import io.github.ioannes78.voica.transcript.SpeechSegment
import io.github.ioannes78.voica.transcript.TranscriptSegment
import io.github.ioannes78.voica.transcript.TranscriptionProgressActivity
import io.github.ioannes78.voica.transcript.VadEngine
import io.github.ioannes78.voica.transcript.VadEngineFactory
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

internal const val STAGE16B_GATE_B_B4_REFERENCE =
    "第一段现在开始。第二段用于时间轴测试。第三段包含 OpenAI 和 Qwen3，最后结束。"
internal const val STAGE16B_GATE_B_B4_MAX_ASR_INPUT_SECONDS = 25
private const val STAGE16B_GATE_B_B4_MAX_RECORDING_SECONDS = 90

internal enum class Stage16BGateB4Route(
    val requiresAlignmentModel: Boolean,
    val currentProductPath: Boolean,
) {
    NATIVE_ONLY(false, false),
    FUNASR_ALIGNMENT_PROBE(true, false),
    QWEN_CURRENT_PRODUCT_ALIGNMENT(true, true),
}

internal data class Stage16BGateB4Plan(
    val modelId: String,
    val route: Stage16BGateB4Route,
)

internal fun stage16BGateB4Plans(): List<Stage16BGateB4Plan> =
    listOf(
        Stage16BGateB4Plan(
            modelId = STAGE16B_FUNASR_NANO_CANDIDATE_ID,
            route = Stage16BGateB4Route.NATIVE_ONLY,
        ),
        Stage16BGateB4Plan(
            modelId = STAGE16B_FUNASR_NANO_CANDIDATE_ID,
            route = Stage16BGateB4Route.FUNASR_ALIGNMENT_PROBE,
        ),
        Stage16BGateB4Plan(
            modelId = Stage8ModelIds.SECOND_PASS_ASR,
            route = Stage16BGateB4Route.NATIVE_ONLY,
        ),
        Stage16BGateB4Plan(
            modelId = Stage13AOfflineModelIds.QWEN3_ASR,
            route = Stage16BGateB4Route.NATIVE_ONLY,
        ),
        Stage16BGateB4Plan(
            modelId = Stage13AOfflineModelIds.QWEN3_ASR,
            route = Stage16BGateB4Route.QWEN_CURRENT_PRODUCT_ALIGNMENT,
        ),
    )

internal data class Stage16BGateB4TimelineMetrics(
    val transcriptSegmentCount: Int,
    val nonBlankSegmentCount: Int,
    val tokenCount: Int,
    val timedTokenCount: Int,
    val timedSegmentCount: Int,
    val monotonicViolationCount: Int,
    val outOfBoundsTokenCount: Int,
) {
    val nativeOrAlignedTimingAvailable: Boolean
        get() = timedTokenCount > 0

    val structurallyValid: Boolean
        get() =
            nonBlankSegmentCount > 0 &&
                tokenCount > 0 &&
                timedTokenCount == tokenCount &&
                timedSegmentCount == nonBlankSegmentCount &&
                monotonicViolationCount == 0 &&
                outOfBoundsTokenCount == 0
}

internal fun stage16BGateB4TimelineMetrics(
    segments: List<TranscriptSegment>,
): Stage16BGateB4TimelineMetrics {
    var tokenCount = 0
    var timedTokenCount = 0
    var timedSegmentCount = 0
    var monotonicViolations = 0
    var outOfBounds = 0

    segments.forEach { segment ->
        var previousStart: Long? = null
        var segmentHasTimedToken = false
        segment.tokens.forEach { token ->
            tokenCount += 1
            val start = token.startSampleIndex
            if (start != null) {
                timedTokenCount += 1
                segmentHasTimedToken = true
                if (start < segment.startSampleIndex || start >= segment.endSampleIndexExclusive) {
                    outOfBounds += 1
                }
                if (previousStart != null && start < previousStart!!) {
                    monotonicViolations += 1
                }
                previousStart = start
            }
            val end = token.endSampleIndexExclusive
            if (
                end != null &&
                (end < segment.startSampleIndex || end > segment.endSampleIndexExclusive ||
                    (start != null && end < start))
            ) {
                outOfBounds += 1
            }
        }
        if (segmentHasTimedToken) timedSegmentCount += 1
    }

    return Stage16BGateB4TimelineMetrics(
        transcriptSegmentCount = segments.size,
        nonBlankSegmentCount = segments.count { it.finalText.isNotBlank() },
        tokenCount = tokenCount,
        timedTokenCount = timedTokenCount,
        timedSegmentCount = timedSegmentCount,
        monotonicViolationCount = monotonicViolations,
        outOfBoundsTokenCount = outOfBounds,
    )
}

internal data class Stage16BGateB4Observation(
    val modelId: String,
    val route: Stage16BGateB4Route,
    val pipelineWallMillis: Long,
    val alignmentActivitySeen: Boolean,
    val alignmentWallMillis: Long?,
    val timelineFallbackError: String?,
    val metrics: Stage16BGateB4TimelineMetrics?,
    val rawText: String,
    val errorMessage: String?,
) {
    val nativeTimingAvailable: Boolean
        get() = route == Stage16BGateB4Route.NATIVE_ONLY && metrics?.nativeOrAlignedTimingAvailable == true

    val routePass: Boolean
        get() {
            if (errorMessage != null || metrics == null) return false
            return when (route) {
                Stage16BGateB4Route.NATIVE_ONLY -> {
                    if (metrics.tokenCount == 0) {
                        true
                    } else {
                        metrics.structurallyValid
                    }
                }
                Stage16BGateB4Route.FUNASR_ALIGNMENT_PROBE,
                Stage16BGateB4Route.QWEN_CURRENT_PRODUCT_ALIGNMENT,
                -> alignmentActivitySeen && timelineFallbackError == null && metrics.structurallyValid
            }
        }
}

internal data class Stage16BGateB4Result(
    val recordingId: String,
    val canonicalSha256: String?,
    val vad: BenchmarkComponentIdentity,
    val vadWallMillis: Long,
    val speechSegments: List<SpeechSegment>,
    val frozenBoundaries: List<SpeechSegment>,
    val alignmentModel: BenchmarkComponentIdentity?,
    val observations: List<Stage16BGateB4Observation>,
) {
    val executionComplete: Boolean
        get() =
            vad.id == Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID &&
                frozenBoundaries.isNotEmpty() &&
                observations.size == stage16BGateB4Plans().size &&
                observations.all(Stage16BGateB4Observation::routePass)
}

internal sealed interface Stage16BGateB4State {
    data object Idle : Stage16BGateB4State

    data class Running(
        val recordingId: String,
        val phase: String,
    ) : Stage16BGateB4State

    data class Completed(
        val result: Stage16BGateB4Result,
    ) : Stage16BGateB4State

    data class Failed(
        val recordingId: String,
        val message: String,
        val missingModelIds: List<String> = emptyList(),
    ) : Stage16BGateB4State

    data class Cancelled(
        val recordingId: String,
    ) : Stage16BGateB4State
}

internal class Stage16BGateB4TimelineRunner(
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
    private val mutableState = MutableStateFlow<Stage16BGateB4State>(Stage16BGateB4State.Idle)

    val state: StateFlow<Stage16BGateB4State> = mutableState.asStateFlow()

    fun start(recordingId: String): Boolean {
        require(recordingId.isNotBlank())
        check(VoicaModelChannel.isDebuggable(application)) {
            "Stage 16B Gate B4 timeline QA is restricted to Debug/QA builds"
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
            currentJob?.cancel(CancellationException("user cancelled Gate B4 timeline benchmark"))
        }
    }

    fun reset() {
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            mutableState.value = Stage16BGateB4State.Idle
        }
    }

    private suspend fun runBenchmark(recordingId: String) {
        try {
            mutableState.value = Stage16BGateB4State.Running(recordingId, "Resolve exact FSMN baseline")
            val fsmn = modelManager.activeModel(Stage16BGateBBenchmarkContract.FORMAL_VAD_MODEL_ID)
            if (fsmn == null) {
                mutableState.value =
                    Stage16BGateB4State.Failed(
                        recordingId,
                        "B4 formal evidence requires active FSMN; Silero fallback is invalid",
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
                    val source = pcmSourceResolver.resolvePcmSource(recordingId)
                        ?: error("canonical PCM source is unavailable")
                    source.use {
                        requireCanonicalSource(it)
                        vadEngineProvider.vadFactory(fsmn, threads, settings.vad).open().use { engine ->
                            engine.analyze(it)
                        }
                    }
                } finally {
                    fsmnLease.close()
                }
            val vadWallMillis = elapsedMillis(vadStartedNs)

            mutableState.value = Stage16BGateB4State.Running(recordingId, "Freeze common 25-second PCM inputs")
            val pcm = readCanonicalPcm(recordingId)
            val frozenInputs =
                buildStage16BGateBFrozenInputs(
                    pcm = pcm,
                    speechSegments = speechSegments,
                    maxChunkSamples = CanonicalPcmProfile.SAMPLE_RATE_HZ * STAGE16B_GATE_B_B4_MAX_ASR_INPUT_SECONDS,
                )
            require(frozenInputs.isNotEmpty()) { "FSMN produced no speech input for B4" }
            val frozenBoundaries =
                frozenInputs.map { SpeechSegment(it.startSampleIndex, it.endSampleIndexExclusive) }

            val alignmentModel =
                modelManager.activeModel(Stage13ARealtimeModelIds.SMALL_BILINGUAL)
                    ?.takeIf {
                        it.descriptor.kind == ModelKind.ASR_STREAMING &&
                            it.descriptor.capabilities.supportsTokenTiming
                    }

            val missing =
                stage16BGateB4Plans()
                    .map(Stage16BGateB4Plan::modelId)
                    .distinct()
                    .filter { modelManager.activeModel(it) == null }
                    .toMutableList()
            if (alignmentModel == null) {
                missing += Stage13ARealtimeModelIds.SMALL_BILINGUAL
            }
            if (missing.isNotEmpty()) {
                mutableState.value =
                    Stage16BGateB4State.Failed(
                        recordingId = recordingId,
                        message = "B4 requires all three ASR candidates and the Small Bilingual timing reference model",
                        missingModelIds = missing.distinct(),
                    )
                return
            }

            val observations = mutableListOf<Stage16BGateB4Observation>()
            for (plan in stage16BGateB4Plans()) {
                mutableState.value =
                    Stage16BGateB4State.Running(
                        recordingId,
                        "${shortModelName(plan.modelId)}/${plan.route.name}",
                    )
                val model = checkNotNull(modelManager.activeModel(plan.modelId))
                observations +=
                    runRoute(
                        recordingId = recordingId,
                        fsmn = fsmn,
                        model = model,
                        alignmentModel = checkNotNull(alignmentModel),
                        frozenBoundaries = frozenBoundaries,
                        settings = settings,
                        numThreads = threads,
                        route = plan.route,
                    )
            }

            mutableState.value =
                Stage16BGateB4State.Completed(
                    Stage16BGateB4Result(
                        recordingId = recordingId,
                        canonicalSha256 = loadCanonicalSha256(recordingId),
                        vad = identity(fsmn),
                        vadWallMillis = vadWallMillis,
                        speechSegments = speechSegments,
                        frozenBoundaries = frozenBoundaries,
                        alignmentModel = identity(checkNotNull(alignmentModel)),
                        observations = observations,
                    ),
                )
        } catch (cancelled: CancellationException) {
            mutableState.value = Stage16BGateB4State.Cancelled(recordingId)
            throw cancelled
        } catch (error: Exception) {
            mutableState.value =
                Stage16BGateB4State.Failed(
                    recordingId = recordingId,
                    message = error.message ?: error::class.java.simpleName,
                )
        }
    }

    private suspend fun runRoute(
        recordingId: String,
        fsmn: ActiveModel,
        model: ActiveModel,
        alignmentModel: ActiveModel,
        frozenBoundaries: List<SpeechSegment>,
        settings: LocalSpeechSettings,
        numThreads: Int,
        route: Stage16BGateB4Route,
    ): Stage16BGateB4Observation {
        val leases = mutableListOf<ModelLease>()
        return try {
            leases += acquire(model)
            if (route.requiresAlignmentModel) leases += acquire(alignmentModel)

            val frozenVadFactory =
                VadEngineFactory {
                    FrozenGateB4VadEngine(
                        model = fsmn.descriptor,
                        segments = frozenBoundaries,
                    )
                }
            val secondPassFactory =
                baseEngineProvider.secondPassFactory(
                    model = model,
                    numThreads = numThreads,
                    senseVoiceSettings = settings.senseVoice,
                    qwenSettings = settings.qwen,
                )
            val alignmentFactory =
                if (route.requiresAlignmentModel) {
                    baseEngineProvider.firstPassFactory(
                        model = alignmentModel,
                        numThreads = numThreads,
                        realtimeSettings = LocalRealtimeAsrSettings(),
                    )
                } else {
                    null
                }

            var alignmentStartedNs: Long? = null
            var alignmentLastNs: Long? = null
            var alignmentActivitySeen = false
            val progressListener =
                ProgressListener { progress ->
                    if (progress.activity == TranscriptionProgressActivity.TIMELINE_ALIGNMENT) {
                        val now = SystemClock.elapsedRealtimeNanos()
                        if (alignmentStartedNs == null) alignmentStartedNs = now
                        alignmentLastNs = now
                        alignmentActivitySeen = true
                    }
                }

            val startedNs = SystemClock.elapsedRealtimeNanos()
            val result =
                HighQualityTranscriptionPipeline(
                    pcmSourceResolver = pcmSourceResolver,
                    vadEngineFactory = frozenVadFactory,
                    secondPassAsrEngineFactory = secondPassFactory,
                    punctuationEngineFactory = null,
                    timelineAlignmentAsrEngineFactory = alignmentFactory,
                    maxSecondPassSegmentSamples =
                        CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() *
                            STAGE16B_GATE_B_B4_MAX_ASR_INPUT_SECONDS.toLong(),
                ).transcribe(
                    recordingId = recordingId,
                    progressListener = progressListener,
                )
            val pipelineWallMillis = elapsedMillis(startedNs)
            require(result.speechSegments == frozenBoundaries) {
                "B4 pipeline changed frozen FSMN boundaries"
            }
            val metrics = stage16BGateB4TimelineMetrics(result.transcriptSegments)
            val alignmentWallMillis =
                if (alignmentStartedNs != null && alignmentLastNs != null) {
                    (alignmentLastNs!! - alignmentStartedNs!!).coerceAtLeast(0L) / 1_000_000L
                } else {
                    null
                }
            Stage16BGateB4Observation(
                modelId = model.descriptor.modelId,
                route = route,
                pipelineWallMillis = pipelineWallMillis,
                alignmentActivitySeen = alignmentActivitySeen,
                alignmentWallMillis = alignmentWallMillis,
                timelineFallbackError = result.timelineAlignmentFallbackError,
                metrics = metrics,
                rawText = result.transcriptSegments.joinToString("\n") { it.secondPassRawText.orEmpty() },
                errorMessage = null,
            )
        } catch (error: Exception) {
            Stage16BGateB4Observation(
                modelId = model.descriptor.modelId,
                route = route,
                pipelineWallMillis = 0L,
                alignmentActivitySeen = false,
                alignmentWallMillis = null,
                timelineFallbackError = null,
                metrics = null,
                rawText = "",
                errorMessage = error.message ?: error::class.java.simpleName,
            )
        } finally {
            leases.asReversed().forEach { lease -> runCatching { lease.close() } }
        }
    }

    private suspend fun readCanonicalPcm(recordingId: String): ShortArray {
        val source = pcmSourceResolver.resolvePcmSource(recordingId)
            ?: error("canonical PCM source is unavailable")
        return source.use {
            requireCanonicalSource(it)
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

    private fun requireCanonicalSource(source: PcmSource) {
        require(source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ) {
            "B4 requires canonical 16 kHz PCM"
        }
        require(source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT) {
            "B4 requires canonical mono PCM"
        }
        require(source.totalSampleCount > 0L) { "canonical PCM is empty" }
        val maxSamples =
            CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong() * STAGE16B_GATE_B_B4_MAX_RECORDING_SECONDS
        require(source.totalSampleCount <= maxSamples) {
            "B4 controlled recording is limited to ${STAGE16B_GATE_B_B4_MAX_RECORDING_SECONDS}s"
        }
        require(source.totalSampleCount <= Int.MAX_VALUE.toLong())
    }

    private fun acquire(model: ActiveModel): ModelLease =
        modelUseRegistry.acquire(
            modelId = model.descriptor.modelId,
            version = model.descriptor.version,
            revision = model.descriptor.revision,
        )

    private fun identity(model: ActiveModel): BenchmarkComponentIdentity =
        BenchmarkComponentIdentity(
            id = model.descriptor.modelId,
            version = model.descriptor.version,
            revision = model.descriptor.revision.toString(),
        )

    private fun shortModelName(modelId: String): String =
        when (modelId) {
            STAGE16B_FUNASR_NANO_CANDIDATE_ID -> "FunASR"
            Stage8ModelIds.SECOND_PASS_ASR -> "SenseVoice"
            Stage13AOfflineModelIds.QWEN3_ASR -> "Qwen3"
            else -> modelId
        }

    private companion object {
        fun elapsedMillis(startedNs: Long): Long =
            (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L) / 1_000_000L
    }
}

private class FrozenGateB4VadEngine(
    override val model: ModelDescriptor,
    private val segments: List<SpeechSegment>,
) : VadEngine {
    override suspend fun analyze(
        source: PcmSource,
        progressListener: ProgressListener?,
    ): List<SpeechSegment> {
        require(source.sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ)
        require(source.channelCount == CanonicalPcmProfile.CHANNEL_COUNT)
        require(segments.all { it.endSampleIndexExclusive <= source.totalSampleCount })
        return segments
    }

    override fun close() = Unit
}

/** QA/releaseQa-only launcher for Gate B4 timestamp/timeline compatibility. */
class Stage16BGateB4TimelineActivity : ComponentActivity() {
    private lateinit var runner: Stage16BGateB4TimelineRunner
    private lateinit var statusText: TextView
    private lateinit var recordingsContainer: LinearLayout
    private var recordingsJob: Job? = null
    private var stateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VoicaApplication
        check(VoicaModelChannel.isDebuggable(app)) {
            "Stage 16B Gate B4 QA activity must never run in a production build"
        }
        val container = app.container
        runner =
            Stage16BGateB4TimelineRunner(
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
                runner.state.collectLatest { state -> statusText.text = formatState(state) }
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
        root.addView(
            TextView(this).apply {
                text = "Stage 16B — Gate B4 Timeline"
                textSize = 22f
            },
        )
        root.addView(
            TextView(this).apply {
                text =
                    "QA-only. Exact FSMN runs once and the same frozen 25-second PCM inputs are reused. " +
                        "SenseVoice measures native timing; Qwen3 measures native baseline plus its current Small-Bilingual alignment path; " +
                        "FunASR measures native baseline plus an alignment-compatibility probe. No Room writes."
            },
        )
        root.addView(
            TextView(this).apply {
                setPadding(0, padding, 0, padding)
                text =
                    "Instruction: record the reference naturally in about 10–20 seconds. Pause about 0.5–1 second between the three clauses.\n" +
                        "Reference: $STAGE16B_GATE_B_B4_REFERENCE"
            },
        )

        statusText =
            TextView(this).apply {
                setPadding(0, padding, 0, padding)
                movementMethod = ScrollingMovementMethod()
                text = "Idle"
            }
        root.addView(statusText)
        root.addView(
            Button(this).apply {
                text = "Cancel current B4 run"
                setOnClickListener { runner.cancel() }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Reset result"
                setOnClickListener { runner.reset() }
            },
        )
        root.addView(
            TextView(this).apply {
                setPadding(0, padding, 0, padding / 2)
                text = "Recent local recordings — tap to run B4"
                textSize = 18f
            },
        )
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
            recordingsContainer.addView(
                Button(this).apply {
                    text = "${recording.displayName}\n${recording.id}"
                    isAllCaps = false
                    setOnClickListener { runner.start(recording.id) }
                },
            )
        }
    }

    private fun formatState(state: Stage16BGateB4State): String =
        when (state) {
            Stage16BGateB4State.Idle -> "Idle"
            is Stage16BGateB4State.Running ->
                "Running\nrecording=${state.recordingId}\nphase=${state.phase}"
            is Stage16BGateB4State.Cancelled -> "Cancelled\nrecording=${state.recordingId}"
            is Stage16BGateB4State.Failed ->
                buildString {
                    appendLine("B4 FAILED")
                    appendLine("recording=${state.recordingId}")
                    appendLine(state.message)
                    if (state.missingModelIds.isNotEmpty()) {
                        appendLine("missing=${state.missingModelIds.joinToString()}")
                    }
                }.trimEnd()
            is Stage16BGateB4State.Completed -> formatResult(state.result)
        }

    private fun formatResult(result: Stage16BGateB4Result): String =
        buildString {
            appendLine(if (result.executionComplete) "B4 COMPLETE" else "B4 INCOMPLETE")
            appendLine("reference=$STAGE16B_GATE_B_B4_REFERENCE")
            appendLine("recording=${result.recordingId}")
            appendLine("canonicalSha256=${result.canonicalSha256 ?: "unknown"}")
            appendLine("vad=${result.vad.id}@${result.vad.version}/${result.vad.revision}")
            appendLine("vadWallMs=${result.vadWallMillis}")
            appendLine("speechSegments=${result.speechSegments.size}")
            appendLine("frozenAsrInputs=${result.frozenBoundaries.size}")
            appendLine("maxAsrInputSeconds=$STAGE16B_GATE_B_B4_MAX_ASR_INPUT_SECONDS")
            appendLine(
                "alignmentModel=" +
                    (result.alignmentModel?.let { "${it.id}@${it.version}/${it.revision}" } ?: "missing"),
            )
            result.observations.forEach { observation ->
                appendLine()
                appendLine(
                    "[${shortModelName(observation.modelId)}/${observation.route.name}] " +
                        if (observation.routePass) "PASS" else "FAIL",
                )
                appendLine("currentProductPath=${observation.route.currentProductPath}")
                observation.errorMessage?.let { appendLine("error=$it") }
                appendLine("pipelineWallMs=${observation.pipelineWallMillis}")
                appendLine("alignmentActivitySeen=${observation.alignmentActivitySeen}")
                appendLine("alignmentWallMs=${observation.alignmentWallMillis ?: "-"}")
                appendLine("timelineFallback=${observation.timelineFallbackError ?: "<none>"}")
                observation.metrics?.let { metrics ->
                    appendLine("segments=${metrics.transcriptSegmentCount}")
                    appendLine("nonBlankSegments=${metrics.nonBlankSegmentCount}")
                    appendLine("tokens=${metrics.tokenCount}")
                    appendLine("timedTokens=${metrics.timedTokenCount}")
                    appendLine(
                        "timedSegmentCoverage=${metrics.timedSegmentCount}/${metrics.nonBlankSegmentCount}",
                    )
                    appendLine("monotonicViolations=${metrics.monotonicViolationCount}")
                    appendLine("outOfBoundsTokens=${metrics.outOfBoundsTokenCount}")
                    appendLine("structurallyValidTimeline=${metrics.structurallyValid}")
                }
                appendLine("nativeTimingAvailable=${observation.nativeTimingAvailable}")
                appendLine("text=${observation.rawText.ifBlank { "<blank>" }}")
            }
        }.trimEnd()

    private fun shortModelName(modelId: String): String =
        when (modelId) {
            STAGE16B_FUNASR_NANO_CANDIDATE_ID -> "FunASR"
            Stage8ModelIds.SECOND_PASS_ASR -> "SenseVoice"
            Stage13AOfflineModelIds.QWEN3_ASR -> "Qwen3"
            else -> modelId
        }

    private companion object {
        const val MAX_RECORDINGS = 20
    }
}
