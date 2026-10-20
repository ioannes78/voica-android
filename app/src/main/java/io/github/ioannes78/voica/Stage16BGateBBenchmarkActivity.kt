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

/**
 * QA/releaseQa-only launcher activity for Stage 16B Gate B B0/B1.
 *
 * The class lives in main source only so both QA build types compile one implementation; it is
 * declared as a launcher exclusively by app/src/qa and app/src/releaseQa manifest overlays.
 */
class Stage16BGateBBenchmarkActivity : ComponentActivity() {
    private lateinit var runner: Stage16BGateBAsrBenchmarkRunner
    private lateinit var statusText: TextView
    private lateinit var modeText: TextView
    private lateinit var recordingsContainer: LinearLayout
    private var recordingsJob: Job? = null
    private var stateJob: Job? = null
    private var selectedB1Profile: Stage16BGateBB1Profile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VoicaApplication
        check(VoicaModelChannel.isDebuggable(app)) {
            "Stage 16B Gate B QA activity must never run in a production build"
        }

        val container = app.container
        runner =
            Stage16BGateBAsrBenchmarkRunner(
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
                runner.state.collectLatest(::renderState)
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
                text = "Stage 16B — Gate B B0 / B1"
                textSize = 22f
            },
        )
        root.addView(
            TextView(this).apply {
                text =
                    "QA-only. FSMN runs once, then the exact same frozen PCM chunks are sent to " +
                        "Fun-ASR-Nano → SenseVoice → Qwen3-ASR. B0 checks candidate readiness; " +
                        "B1 scores a frozen reference profile. Short runs are limited to " +
                        "${Stage16BGateBBenchmarkContract.MAX_B0_RECORDING_SECONDS}s."
            },
        )

        modeText =
            TextView(this).apply {
                setPadding(0, padding, 0, padding / 2)
            }
        root.addView(modeText)
        root.addView(
            Button(this).apply {
                text = "Use B0 readiness mode"
                isAllCaps = false
                setOnClickListener {
                    selectedB1Profile = null
                    renderMode()
                }
            },
        )

        root.addView(
            TextView(this).apply {
                setPadding(0, padding, 0, padding / 2)
                text = "B1 frozen reference profiles"
                textSize = 18f
            },
        )
        Stage16BGateBB1Profile.entries.forEach { profile ->
            root.addView(
                Button(this).apply {
                    text = profile.name
                    isAllCaps = false
                    setOnClickListener {
                        selectedB1Profile = profile
                        renderMode()
                    }
                },
            )
        }
        renderMode()

        statusText =
            TextView(this).apply {
                setPadding(0, padding, 0, padding)
                movementMethod = ScrollingMovementMethod()
                text = "Idle"
            }
        root.addView(statusText)

        root.addView(
            Button(this).apply {
                text = "Cancel current Gate B run"
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
                text = "Recent local recordings — tap to run current mode"
                textSize = 18f
            },
        )
        recordingsContainer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        root.addView(
            recordingsContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        return ScrollView(this).apply { addView(root) }
    }

    private fun renderMode() {
        if (!::modeText.isInitialized) return
        val profile = selectedB1Profile
        modeText.text =
            if (profile == null) {
                "Current mode: B0 readiness\nNo reference scoring."
            } else {
                "Current mode: B1 ${profile.name}\nReference: ${profile.referenceText}"
            }
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
                    setOnClickListener {
                        val profile = selectedB1Profile
                        if (profile == null) {
                            runner.start(recording.id)
                        } else {
                            runner.startB1(recording.id, profile)
                        }
                    }
                },
            )
        }
    }

    private fun renderState(state: Stage16BGateBB0State) {
        statusText.text =
            when (state) {
                Stage16BGateBB0State.Idle -> "Idle"
                is Stage16BGateBB0State.Running ->
                    "Running\nrecording=${state.recordingId}\nphase=${state.phase}"
                is Stage16BGateBB0State.Cancelled ->
                    "Cancelled\nrecording=${state.recordingId}"
                is Stage16BGateBB0State.Failed ->
                    buildString {
                        appendLine("FAILED")
                        appendLine("recording=${state.recordingId}")
                        appendLine(state.message)
                        if (state.missingModelIds.isNotEmpty()) {
                            appendLine("missing=${state.missingModelIds.joinToString()}")
                        }
                    }.trimEnd()
                is Stage16BGateBB0State.Completed -> formatResult(state.result)
            }
    }

    private fun formatResult(result: Stage16BGateBB0Result): String =
        buildString {
            val profile = result.b1Profile
            if (profile == null) {
                appendLine(if (result.allCandidatesReady) "B0 PASS" else "B0 NOT READY")
            } else {
                appendLine(if (result.allCandidatesReady) "B1 COMPLETE" else "B1 INCOMPLETE")
                appendLine("profile=${profile.name}")
                appendLine("reference=${profile.referenceText}")
            }
            appendLine("recording=${result.recordingId}")
            appendLine("canonicalSha256=${result.canonicalSha256 ?: "unknown"}")
            appendLine("vad=${result.vad.id}@${result.vad.version}/${result.vad.revision}")
            appendLine("vadWallMs=${result.vadWallTimeMillis}")
            appendLine("speechSegments=${result.speechSegments.size}")
            appendLine("frozenAsrInputs=${result.frozenAsrInputBoundaries.size}")
            result.models.forEach { observation ->
                appendLine()
                appendLine("[${observation.modelId}] ${if (observation.ready) "READY" else "NOT READY"}")
                observation.errorMessage?.let { appendLine("error=$it") }
                appendLine("engineOpenMs=${observation.engineOpenTimeMillis ?: "-"}")
                appendLine("asrCalls=${observation.asrCallCount}")
                appendLine("blankCalls=${observation.blankCallCount}")
                appendLine("startPssKb=${observation.startingPssKb ?: "-"}")
                observation.benchmark?.let { benchmark ->
                    appendLine("asrWallMs=${benchmark.asrTimeMillis ?: "-"}")
                    appendLine("totalWallMs=${benchmark.wallTimeMillis}")
                    appendLine("rtf=${benchmark.realTimeFactor?.let { "%.4f".format(it) } ?: "-"}")
                    appendLine("peakPssKb=${benchmark.peakPssKb ?: "-"}")
                }
                observation.b1Quality?.let { quality ->
                    appendLine("cer=${quality.characterErrorRate?.let { "%.4f".format(it) } ?: "-"}")
                    appendLine(
                        "cerEdits=sub:${quality.character.substitutions},ins:${quality.character.insertions},del:${quality.character.deletions}",
                    )
                    quality.word?.let { word ->
                        appendLine("wer=${quality.wordErrorRate?.let { "%.4f".format(it) } ?: "-"}")
                        appendLine("werEdits=sub:${word.substitutions},ins:${word.insertions},del:${word.deletions}")
                    }
                    appendLine("wholeProfileBlank=${quality.wholeProfileBlank}")
                    quality.headDeletion?.let { appendLine("headDeletion=$it") }
                    quality.tailDeletion?.let { appendLine("tailDeletion=$it") }
                    appendLine("duplicateNonBlankLines=${quality.duplicateNonBlankLineCount}")
                    appendLine("manualReview=hallucination/repetition/semantic-loss")
                }
                if (observation.detectedLanguages.isNotEmpty()) {
                    appendLine("languages=${observation.detectedLanguages.joinToString()}")
                }
                appendLine("text=${observation.rawText.ifBlank { "<blank>" }}")
            }
        }.trimEnd()

    private companion object {
        const val MAX_RECORDINGS = 20
    }
}
