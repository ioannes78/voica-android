package io.github.ioannes78.voica

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
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

/**
 * QA-only B5 launcher using fresh lightweight process instances for each formal candidate session.
 *
 * Candidate activities run in the existing :model_validator process because VoicaApplication already
 * skips the full AppContainer there. The process is killed when the candidate screen is finished, so
 * the next candidate starts with a new PID and cannot inherit PSS/model state from the previous run.
 */
class Stage16BGateB5FreshLauncherActivity : ComponentActivity() {
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
            "Stage 16B B5 launcher must never run in a production build"
        }
        setContentView(buildContentView())
        renderPlan(Stage16BGateB5PlanStore.read(app))
        recordingsJob =
            lifecycleScope.launch {
                app.container.recordingLibraryRepository.recordings.collectLatest(::renderRecordings)
            }
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized && !preparing) {
            renderPlan(Stage16BGateB5PlanStore.read(app))
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
            text = "Stage 16B — Gate B5 Isolated Performance"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text =
                "QA-only. Prepare exact FSMN-frozen 25-second inputs once. Each candidate then runs in a fresh lightweight Android process instance. " +
                    "Finish a candidate screen before starting the next candidate. B5 excludes punctuation and timeline-alignment overhead."
        })
        statusText = TextView(this).apply {
            setPadding(0, padding, 0, padding)
            movementMethod = ScrollingMovementMethod()
        }
        root.addView(statusText)
        root.addView(TextView(this).apply {
            text = "Candidate sessions — prepare a plan first"
            textSize = 18f
        })
        candidateButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(candidateButtons)
        root.addView(TextView(this).apply {
            setPadding(0, padding, 0, padding / 2)
            text = "Recent local recordings — tap one 1–3 minute real-speech file to prepare B5"
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
        statusText.text = "Preparing B5 common input…\nrecording=$recordingId\nRunning exact FSMN once."
        lifecycleScope.launch {
            try {
                val container = app.container
                val canonicalSha =
                    withContext(Dispatchers.IO) {
                        container.recordingLibraryRepository
                            .loadCanonicalTranscriptionLineage(
                                recordingId = recordingId,
                                profileId = CanonicalPcmProfile.PROFILE_ID,
                            )
                            ?.canonicalSha256
                    } ?: error("verified canonical PCM lineage is missing")
                val plan =
                    withContext(Dispatchers.Default) {
                        prepareStage16BGateB5Plan(
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
                statusText.text = "B5 PLAN FAILED\nrecording=$recordingId\n${error.message ?: error::class.java.simpleName}"
            } finally {
                preparing = false
            }
        }
    }

    private fun renderPlan(plan: Stage16BGateB5Plan?) {
        candidateButtons.removeAllViews()
        if (plan == null) {
            statusText.text =
                "No B5 plan prepared.\nChoose one representative 1–3 minute recording below. Use the same prepared plan for all candidate sessions."
            return
        }
        statusText.text = buildString {
            appendLine("B5 PLAN READY")
            appendLine("recording=${plan.recordingId}")
            appendLine("canonicalSha256=${plan.canonicalSha256}")
            appendLine("vad=${plan.vadModelId}@${plan.vadModelVersion}/${plan.vadModelRevision}")
            appendLine("vadWallMs=${plan.vadWallMillis}")
            appendLine("threads=${plan.numThreads}")
            appendLine("frozenInputs=${plan.frozenBoundaries.size}")
            appendLine("maxAsrInputSeconds=$STAGE16B_GATE_B_B5_MAX_ASR_INPUT_SECONDS")
            append("speechSeconds=${formatB5Double(plan.speechDurationSeconds)}")
        }
        addCandidateButton("Run SenseVoice — fresh process", Stage16BGateB5FreshSenseVoiceActivity::class.java)
        addCandidateButton("Run FunASR Nano — fresh process", Stage16BGateB5FreshFunAsrActivity::class.java)
        addCandidateButton("Run Qwen3-ASR — fresh process", Stage16BGateB5FreshQwen3Activity::class.java)
        candidateButtons.addView(TextView(this).apply {
            text = "Run each candidate once. After taking the result screenshot, use BACK TO B5 LAUNCHER; that terminates the lightweight candidate process before the next model starts."
        })
    }

    private fun addCandidateButton(label: String, activityClass: Class<out ComponentActivity>) {
        candidateButtons.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@Stage16BGateB5FreshLauncherActivity, activityClass))
            }
        })
    }

    private companion object {
        const val MAX_RECORDINGS = 20
    }
}

private class Stage16BGateB5MinimalDependencies(application: Application) : AutoCloseable {
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
    val modelManager = createVoicaModelManager(
        application = application,
        useRegistry = modelUseRegistry,
    )
    val localSpeechSettingsStore = SharedPreferencesLocalSpeechSettingsStore(application)

    override fun close() {
        database.close()
    }
}

abstract class Stage16BGateB5FreshCandidateActivity : ComponentActivity() {
    protected abstract val candidateModelId: String
    protected abstract val candidateLabel: String

    private lateinit var statusText: TextView
    private lateinit var runButton: Button
    private var runJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VoicaApplication
        check(VoicaModelChannel.isDebuggable(app)) {
            "Stage 16B B5 candidate activity must never run in a production build"
        }
        setContentView(buildContentView(app))
    }

    override fun onDestroy() {
        runJob?.cancel()
        val shouldTerminate = isFinishing && !isChangingConfigurations
        super.onDestroy()
        if (shouldTerminate) {
            Process.killProcess(Process.myPid())
        }
    }

    private fun buildContentView(app: VoicaApplication): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        root.addView(TextView(this).apply {
            text = "Stage 16B — B5 $candidateLabel"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text =
                "Fresh lightweight process instance. Press RUN exactly once for one formal cold + warm session. " +
                    "Take the result screenshot, then use BACK TO B5 LAUNCHER to terminate this process before the next candidate."
        })
        statusText = TextView(this).apply {
            setPadding(0, padding, 0, padding)
            movementMethod = ScrollingMovementMethod()
            text = formatPlan(Stage16BGateB5PlanStore.read(app))
        }
        root.addView(statusText)
        runButton = Button(this).apply {
            text = "RUN COLD + WARM"
            isAllCaps = false
            isEnabled = Stage16BGateB5PlanStore.read(app) != null
            setOnClickListener { runBenchmark(app) }
        }
        root.addView(runButton)
        root.addView(Button(this).apply {
            text = "BACK TO B5 LAUNCHER"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun runBenchmark(app: VoicaApplication) {
        if (runJob?.isActive == true) return
        runButton.isEnabled = false
        statusText.text = "B5 RUNNING\ncandidate=$candidateLabel\nCold pass will run first, then warm pass on the exact same frozen PCM."
        runJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    Stage16BGateB5MinimalDependencies(app).use { dependencies ->
                        Stage16BGateB5CandidateRunner(
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
                statusText.text = "B5 FAILED\ncandidate=$candidateLabel\n${error.message ?: error::class.java.simpleName}"
            }
        }
    }

    private fun formatPlan(plan: Stage16BGateB5Plan?): String =
        if (plan == null) {
            "B5 plan missing. Return to the B5 launcher and prepare the common FSMN-frozen input first."
        } else {
            buildString {
                appendLine("READY")
                appendLine("candidate=$candidateLabel")
                appendLine("recording=${plan.recordingId}")
                appendLine("canonicalSha256=${plan.canonicalSha256}")
                appendLine("frozenInputs=${plan.frozenBoundaries.size}")
                append("speechSeconds=${formatB5Double(plan.speechDurationSeconds)}")
            }
        }

    private fun formatResult(result: Stage16BGateB5Result): String = buildString {
        appendLine(if (result.complete) "B5 COMPLETE" else "B5 INCOMPLETE")
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
        appendLine("speechSeconds=${formatB5Double(result.plan.speechDurationSeconds)}")
        appendPass(result.cold)
        appendPass(result.warm)
    }.trimEnd()

    private fun StringBuilder.appendPass(pass: Stage16BGateB5PassObservation) {
        appendLine()
        appendLine("[${pass.label}] ${if (pass.complete) "PASS" else "INCOMPLETE"}")
        appendLine("engineOpenMs=${pass.engineOpenMillis}")
        appendLine("asrWallMs=${pass.asrWallMillis}")
        appendLine("asrRtf=${formatB5Double(pass.asrRtf)}")
        appendLine("totalWallMs=${pass.totalWallMillis}")
        appendLine("totalRtf=${formatB5Double(pass.totalRtf)}")
        appendLine("pssKb=${pass.startPssKb}/${pass.peakPssKb}/${pass.endPssKb} start/peak/end")
        appendLine("asrCalls=${pass.asrCallCount}")
        appendLine("blankCalls=${pass.blankCallCount}")
    }
}

class Stage16BGateB5FreshSenseVoiceActivity : Stage16BGateB5FreshCandidateActivity() {
    override val candidateModelId: String = Stage8ModelIds.SECOND_PASS_ASR
    override val candidateLabel: String = "SenseVoice"
}

class Stage16BGateB5FreshFunAsrActivity : Stage16BGateB5FreshCandidateActivity() {
    override val candidateModelId: String = STAGE16B_FUNASR_NANO_CANDIDATE_ID
    override val candidateLabel: String = "FunASR Nano"
}

class Stage16BGateB5FreshQwen3Activity : Stage16BGateB5FreshCandidateActivity() {
    override val candidateModelId: String = Stage13AOfflineModelIds.QWEN3_ASR
    override val candidateLabel: String = "Qwen3-ASR"
}

private fun formatB5Double(value: Double): String = String.format(Locale.US, "%.4f", value)
