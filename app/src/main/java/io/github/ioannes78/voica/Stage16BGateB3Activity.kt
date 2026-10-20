package io.github.ioannes78.voica

import android.os.Bundle
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** QA/releaseQa-only launcher for Gate B B3 controlled challenges. */
class Stage16BGateB3Activity : ComponentActivity() {
    private lateinit var runner: Stage16BGateB3ChallengeRunner
    private lateinit var modeText: TextView
    private lateinit var statusText: TextView
    private lateinit var recordingsContainer: LinearLayout
    private var selectedProfile: Stage16BGateB3Profile = Stage16BGateB3Profile.PUNCTUATION_ZH
    private var recordingsJob: Job? = null
    private var stateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VoicaApplication
        check(VoicaModelChannel.isDebuggable(app)) {
            "Stage 16B Gate B3 QA activity must never run in a production build"
        }
        val container = app.container
        runner =
            Stage16BGateB3ChallengeRunner(
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
                text = "Stage 16B — Gate B B3"
                textSize = 22f
            },
        )
        root.addView(
            TextView(this).apply {
                text =
                    "QA-only. Exact FSMN runs once. Speech inputs use the verified common 25-second cap. " +
                        "Punctuation is native-only; ITN and language controls run only where the runtime exposes them. " +
                        "Silence/noise may be filtered entirely by FSMN, which is recorded as pipeline-level no-speech evidence."
            },
        )

        modeText = TextView(this).apply { setPadding(0, padding, 0, padding / 2) }
        root.addView(modeText)
        root.addView(
            TextView(this).apply {
                text = "B3 challenge profiles"
                textSize = 18f
            },
        )
        Stage16BGateB3Profile.entries.forEach { profile ->
            root.addView(
                Button(this).apply {
                    text = profile.name
                    isAllCaps = false
                    setOnClickListener {
                        selectedProfile = profile
                        renderProfile()
                    }
                },
            )
        }
        renderProfile()

        statusText =
            TextView(this).apply {
                setPadding(0, padding, 0, padding)
                movementMethod = ScrollingMovementMethod()
                text = "Idle"
            }
        root.addView(statusText)
        root.addView(
            Button(this).apply {
                text = "Cancel current B3 run"
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
                text = "Recent local recordings — tap to run selected B3 profile"
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

    private fun renderProfile() {
        if (!::modeText.isInitialized) return
        modeText.text =
            buildString {
                appendLine("Current profile: ${selectedProfile.name}")
                appendLine("Instruction: ${selectedProfile.instruction}")
                selectedProfile.referenceText?.let { append("Reference: $it") }
            }.trimEnd()
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
                    setOnClickListener { runner.start(recording.id, selectedProfile) }
                },
            )
        }
    }

    private fun formatState(state: Stage16BGateB3State): String =
        when (state) {
            Stage16BGateB3State.Idle -> "Idle"
            is Stage16BGateB3State.Running ->
                "Running\nrecording=${state.recordingId}\nphase=${state.phase}"
            is Stage16BGateB3State.Cancelled -> "Cancelled\nrecording=${state.recordingId}"
            is Stage16BGateB3State.Failed ->
                buildString {
                    appendLine("B3 FAILED")
                    appendLine("recording=${state.recordingId}")
                    appendLine(state.message)
                    if (state.missingModelIds.isNotEmpty()) {
                        appendLine("missing=${state.missingModelIds.joinToString()}")
                    }
                }.trimEnd()
            is Stage16BGateB3State.Completed -> formatResult(state.result)
        }

    private fun formatResult(result: Stage16BGateB3Result): String =
        buildString {
            appendLine(if (result.executionComplete) "B3 COMPLETE" else "B3 INCOMPLETE")
            appendLine("profile=${result.profile.name}")
            appendLine("instruction=${result.profile.instruction}")
            result.profile.referenceText?.let { appendLine("reference=$it") }
            appendLine("recording=${result.recordingId}")
            appendLine("canonicalSha256=${result.canonicalSha256 ?: "unknown"}")
            appendLine("vad=${result.vad.id}@${result.vad.version}/${result.vad.revision}")
            appendLine("vadWallMs=${result.vadWallTimeMillis}")
            appendLine("speechSegments=${result.speechSegments.size}")
            appendLine("frozenAsrInputs=${result.frozenAsrInputBoundaries.size}")
            appendLine("maxAsrInputSeconds=$STAGE16B_GATE_B_B3_MAX_ASR_INPUT_SECONDS")
            appendLine("vadFilteredNoSpeech=${result.vadFilteredNoSpeech}")
            if (result.vadFilteredNoSpeech) {
                appendLine("pipelineHallucinationRisk=FILTERED_BEFORE_ASR")
            }

            result.observations.forEach { observation ->
                appendLine()
                appendLine(
                    "[${shortModelName(observation.modelId)}/${observation.variantLabel}] " +
                        if (observation.completed) "COMPLETE" else "ERROR",
                )
                appendLine("capability=${observation.capabilityNote}")
                observation.errorMessage?.let { appendLine("error=$it") }
                appendLine("engineOpenMs=${observation.engineOpenTimeMillis ?: "-"}")
                appendLine("asrWallMs=${observation.asrWallTimeMillis}")
                appendLine("asrCalls=${observation.asrCallCount}")
                appendLine("blankCalls=${observation.blankCallCount}")
                if (observation.detectedLanguages.isNotEmpty()) {
                    appendLine("languages=${observation.detectedLanguages.joinToString()}")
                }
                observation.contentCer?.let { appendLine("contentCer=${"%.4f".format(it)}") }
                observation.semanticCoverage?.let { appendLine("itnSemanticCoverage=${"%.2f".format(it)}") }
                appendLine(
                    "punctuation=comma:${observation.punctuation.commaCount},end:${observation.punctuation.sentenceEndCount}," +
                        "question:${observation.punctuation.questionMarkCount},exclamation:${observation.punctuation.exclamationMarkCount}," +
                        "colonSemicolon:${observation.punctuation.colonSemicolonCount}",
                )
                if (result.profile.expectsNoSpeech) {
                    appendLine("hallucinationNonBlank=${observation.hallucinationNonBlank}")
                }
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
