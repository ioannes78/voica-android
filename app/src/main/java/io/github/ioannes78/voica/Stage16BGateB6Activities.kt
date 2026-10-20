package io.github.ioannes78.voica

import android.app.Application
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.VoicaDatabase
import io.github.ioannes78.voica.model.ModelUseRegistry
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Stage16BGateB6LauncherActivity : ComponentActivity() {
    private lateinit var app: VoicaApplication
    private lateinit var statusText: TextView
    private lateinit var candidateButtons: LinearLayout
    private lateinit var recordingsContainer: LinearLayout
    private var recordingsJob: Job? = null
    private var preparing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as VoicaApplication
        check(VoicaModelChannel.isDebuggable(app)) {
            "Stage 16B B6 launcher must never run in a production build"
        }
        setContentView(buildContentView())
        renderPlan(Stage16BGateB6PlanStore.read(app))
        recordingsJob = lifecycleScope.launch {
            app.container.recordingLibraryRepository.recordings.collectLatest(::renderRecordings)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized && !preparing) {
            renderPlan(Stage16BGateB6PlanStore.read(app))
        }
    }

    override fun onDestroy() {
        recordingsJob?.cancel()
        super.onDestroy()
    }

    private fun buildContentView(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "Stage 16B — Gate B6 Finalist Endurance"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text =
                "QA-only. Finalists: FunASR Nano and Qwen3-ASR. Prepare one representative 10–30 minute real-speech file with exact FSMN once, then run each finalist in a fresh lightweight process. " +
                    "B6 measures long-run RTF, early-vs-late slowdown, thermal status, memory and blank/crash behavior."
        })
        statusText = TextView(this).apply {
            setPadding(0, padding, 0, padding)
            movementMethod = ScrollingMovementMethod()
        }
        root.addView(statusText)
        root.addView(TextView(this).apply {
            text = "Finalist sessions — prepare a B6 plan first"
            textSize = 18f
        })
        candidateButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(candidateButtons)
        root.addView(TextView(this).apply {
            setPadding(0, padding, 0, padding / 2)
            text = "Recent local recordings — choose one representative 10–30 minute file"
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
                isEnabled = !preparing
                setOnClickListener { prepare(recording.id) }
            })
        }
    }

    private fun prepare(recordingId: String) {
        if (preparing) return
        preparing = true
        candidateButtons.removeAllViews()
        statusText.text = "Preparing B6 common input…\nrecording=$recordingId\nRunning exact FSMN once."
        lifecycleScope.launch {
            try {
                val container = app.container
                val canonicalSha = withContext(Dispatchers.IO) {
                    container.recordingLibraryRepository
                        .loadCanonicalTranscriptionLineage(
                            recordingId = recordingId,
                            profileId = CanonicalPcmProfile.PROFILE_ID,
                        )
                        ?.canonicalSha256
                } ?: error("verified canonical PCM lineage is missing")
                val plan = withContext(Dispatchers.Default) {
                    prepareStage16BGateB6Plan(
                        application = app,
                        recordingId = recordingId,
                        pcmSourceResolver = container.pcmSourceResolver,
                        modelManager = container.modelManager,
                        modelUseRegistry = container.modelUseRegistry,
                        localSpeechSettings = container.localSpeechSettingsStore.settings.value,
                        canonicalSha256 = canonicalSha,
                    )
                }
                renderPlan(plan)
            } catch (error: Exception) {
                statusText.text = "B6 PLAN FAILED\nrecording=$recordingId\n${error.message ?: error::class.java.simpleName}"
            } finally {
                preparing = false
            }
        }
    }

    private fun renderPlan(plan: Stage16BGateB6Plan?) {
        candidateButtons.removeAllViews()
        if (plan == null) {
            statusText.text =
                "No B6 plan prepared.\nChoose one 10–30 minute representative real-speech recording below. " +
                    "Accepted QA duration range is 9–45 minutes."
            return
        }
        statusText.text = buildString {
            appendLine("B6 PLAN READY")
            appendLine("recording=${plan.recordingId}")
            appendLine("canonicalSha256=${plan.canonicalSha256}")
            appendLine("vad=${plan.vadModelId}@${plan.vadModelVersion}/${plan.vadModelRevision}")
            appendLine("vadWallMs=${plan.vadWallMillis}")
            appendLine("threads=${plan.numThreads}")
            appendLine("frozenInputs=${plan.frozenBoundaries.size}")
            appendLine("maxAsrInputSeconds=$STAGE16B_GATE_B_B6_MAX_ASR_INPUT_SECONDS")
            appendLine("recordingSeconds=${formatB6Double(plan.recordingDurationSeconds)}")
            append("speechSeconds=${formatB6Double(plan.speechDurationSeconds)}")
        }
        addCandidateButton("Run FunASR Nano — fresh endurance process", Stage16BGateB6FunAsrActivity::class.java)
        addCandidateButton("Run Qwen3-ASR — fresh endurance process", Stage16BGateB6Qwen3Activity::class.java)
        candidateButtons.addView(TextView(this).apply {
            text = "Run each finalist exactly once. Capture the result, then use BACK TO B6 LAUNCHER to terminate the lightweight process before starting the other finalist."
        })
    }

    private fun addCandidateButton(label: String, activityClass: Class<out ComponentActivity>) {
        candidateButtons.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { startActivity(android.content.Intent(this@Stage16BGateB6LauncherActivity, activityClass)) }
        })
    }

    private companion object {
        const val MAX_RECORDINGS = 30
    }
}

private class Stage16BGateB6MinimalDependencies(application: Application) : AutoCloseable {
    private val recordingsRoot = File(application.noBackupFilesDir, "recordings")
    private val database = VoicaDatabase.create(application)

    val recordingLibraryRepository = RecordingLibraryRepository(
        database = database,
        recordingsRoot = recordingsRoot,
    )
    val pcmSourceResolver = RoomAudioSourceResolver(
        repository = recordingLibraryRepository,
        recordingsRoot = recordingsRoot,
    )
    val modelUseRegistry = ModelUseRegistry()
    val modelManager = createStage16BGateB5ReadOnlyModelManager(
        application = application,
        useRegistry = modelUseRegistry,
    )
    val localSpeechSettingsStore = SharedPreferencesLocalSpeechSettingsStore(application)

    override fun close() {
        database.close()
    }
}

abstract class Stage16BGateB6CandidateActivity : ComponentActivity() {
    protected abstract val candidateModelId: String
    protected abstract val candidateLabel: String

    private lateinit var statusText: TextView
    private lateinit var runButton: Button
    private var runJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as VoicaApplication
        check(VoicaModelChannel.isDebuggable(app)) {
            "Stage 16B B6 candidate activity must never run in a production build"
        }
        setContentView(buildContentView(app))
    }

    override fun onDestroy() {
        runJob?.cancel()
        val shouldTerminate = isFinishing && !isChangingConfigurations
        super.onDestroy()
        if (shouldTerminate) Process.killProcess(Process.myPid())
    }

    private fun buildContentView(app: VoicaApplication): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "Stage 16B — B6 $candidateLabel"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text =
                "Fresh lightweight process. Run exactly once. This can take several minutes. The B6 activity keeps the display awake for the formal endurance pass; avoid other heavy tasks until the result is shown."
        })
        statusText = TextView(this).apply {
            setPadding(0, padding, 0, padding)
            movementMethod = ScrollingMovementMethod()
            text = formatPlan(Stage16BGateB6PlanStore.read(app))
        }
        root.addView(statusText)
        runButton = Button(this).apply {
            text = "RUN B6 ENDURANCE"
            isAllCaps = false
            isEnabled = Stage16BGateB6PlanStore.read(app) != null
            setOnClickListener { runBenchmark(app) }
        }
        root.addView(runButton)
        root.addView(Button(this).apply {
            text = "BACK TO B6 LAUNCHER"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun runBenchmark(app: VoicaApplication) {
        if (runJob?.isActive == true) return
        runButton.isEnabled = false
        statusText.text = "B6 RUNNING\ncandidate=$candidateLabel\nScreen-awake protection is active. Do not leave this screen until the result is shown."
        runJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    Stage16BGateB6MinimalDependencies(app).use { dependencies ->
                        Stage16BGateB6CandidateRunner(
                            application = app,
                            modelId = candidateModelId,
                            pcmSourceResolver = dependencies.pcmSourceResolver,
                            modelManager = dependencies.modelManager,
                            modelUseRegistry = dependencies.modelUseRegistry,
                            localSpeechSettings = { dependencies.localSpeechSettingsStore.settings.value },
                            loadCanonicalSha256 = { recordingId ->
                                dependencies.recordingLibraryRepository
                                    .loadCanonicalTranscriptionLineage(
                                        recordingId = recordingId,
                                        profileId = CanonicalPcmProfile.PROFILE_ID,
                                    )
                                    ?.canonicalSha256
                            },
                        ).run()
                    }
                }
                statusText.text = formatResult(result)
            } catch (error: Exception) {
                statusText.text = "B6 FAILED\ncandidate=$candidateLabel\n${error.message ?: error::class.java.simpleName}"
            }
        }
    }

    private fun formatPlan(plan: Stage16BGateB6Plan?): String =
        if (plan == null) {
            "B6 plan missing. Return to the B6 launcher and prepare the common exact-FSMN input first."
        } else {
            buildString {
                appendLine("READY")
                appendLine("candidate=$candidateLabel")
                appendLine("recording=${plan.recordingId}")
                appendLine("canonicalSha256=${plan.canonicalSha256}")
                appendLine("frozenInputs=${plan.frozenBoundaries.size}")
                appendLine("recordingSeconds=${formatB6Double(plan.recordingDurationSeconds)}")
                append("speechSeconds=${formatB6Double(plan.speechDurationSeconds)}")
            }
        }

    private fun formatResult(result: Stage16BGateB6Result): String = buildString {
        appendLine(if (result.complete) "B6 COMPLETE" else "B6 INCOMPLETE")
        appendLine("candidate=$candidateLabel")
        appendLine("model=${result.modelId}@${result.modelVersion}/${result.modelRevision}")
        appendLine("processSession=${result.processSessionId}")
        appendLine("pid=${result.processId}")
        appendLine("recording=${result.plan.recordingId}")
        appendLine("canonicalSha256=${result.plan.canonicalSha256}")
        appendLine("vad=${result.plan.vadModelId}@${result.plan.vadModelVersion}/${result.plan.vadModelRevision}")
        appendLine("vadWallMs=${result.plan.vadWallMillis}")
        appendLine("threads=${result.plan.numThreads}")
        appendLine("frozenInputs=${result.plan.frozenBoundaries.size}")
        appendLine("recordingSeconds=${formatB6Double(result.plan.recordingDurationSeconds)}")
        appendLine("speechSeconds=${formatB6Double(result.plan.speechDurationSeconds)}")
        appendLine("engineOpenMs=${result.engineOpenMillis}")
        appendLine("asrWallMs=${result.asrWallMillis}")
        appendLine("asrRtf=${formatB6Double(result.asrRtf)}")
        appendLine("totalWallMs=${result.totalWallMillis}")
        appendLine("totalRtf=${formatB6Double(result.totalRtf)}")
        appendLine("firstQuarterAsrRtf=${formatB6Nullable(result.firstQuarterAsrRtf)}")
        appendLine("lastQuarterAsrRtf=${formatB6Nullable(result.lastQuarterAsrRtf)}")
        appendLine("thermalSlowdownRatio=${formatB6Nullable(result.thermalSlowdownRatio)}")
        appendLine("pssKb=${result.startPssKb}/${result.peakPssKb}/${result.endPssKb} start/peak/end")
        appendLine("thermalStatus=${thermalName(result.thermalStatusStart)}/${thermalName(result.thermalStatusMax)}/${thermalName(result.thermalStatusEnd)} start/max/end")
        appendLine("asrCalls=${result.asrCallCount}")
        appendLine("blankCalls=${result.blankCallCount}")
        append("normalizedChars=${result.normalizedCharacterCount}")
    }.trimEnd()
}

class Stage16BGateB6FunAsrActivity : Stage16BGateB6CandidateActivity() {
    override val candidateModelId: String = STAGE16B_FUNASR_NANO_CANDIDATE_ID
    override val candidateLabel: String = "FunASR Nano"
}

class Stage16BGateB6Qwen3Activity : Stage16BGateB6CandidateActivity() {
    override val candidateModelId: String = Stage13AOfflineModelIds.QWEN3_ASR
    override val candidateLabel: String = "Qwen3-ASR"
}

private fun formatB6Double(value: Double): String = String.format(Locale.US, "%.4f", value)

private fun formatB6Nullable(value: Double?): String = value?.let(::formatB6Double) ?: "-"

private fun thermalName(status: Int?): String = when (status) {
    null -> "unavailable"
    PowerManager.THERMAL_STATUS_NONE -> "NONE"
    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
    else -> status.toString()
}
