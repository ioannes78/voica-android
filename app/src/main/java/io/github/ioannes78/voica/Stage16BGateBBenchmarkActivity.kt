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
 * QA/releaseQa-only launcher activity for Stage 16B Gate B B0/B1/B2.
 *
 * The class lives in main source only so both QA build types compile one implementation; it is
 * declared as a launcher exclusively by app/src/qa and app/src/releaseQa manifest overlays.
 */
class Stage16BGateBBenchmarkActivity : ComponentActivity() {
    private lateinit var runner: Stage16BGateBAsrBenchmarkRunner
    private lateinit var b2Runner: Stage16BGateB2CompletenessRunner
    private lateinit var statusText: TextView
    private lateinit var modeText: TextView
    private lateinit var recordingsContainer: LinearLayout
    private var recordingsJob: Job? = null
    private var benchmarkStateJob: Job? = null
    private var b2StateJob: Job? = null
    private var latestBenchmarkState: Stage16BGateBB0State = Stage16BGateBB0State.Idle
    private var latestB2State: Stage16BGateB2State = Stage16BGateB2State.Idle
    private var selectedMode: SelectedMode = SelectedMode.B0
    private var selectedB1Profile: Stage16BGateBB1Profile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VoicaApplication
        check(VoicaModelChannel.isDebuggable(app)) {
            "Stage 16B Gate B QA activity must never run in a production build"
        }

        val container = app.container
        val lineageLoader: suspend (String) -> String? = { recordingId ->
            container.recordingLibraryRepository
                .loadCanonicalTranscriptionLineage(
                    recordingId = recordingId,
                    profileId = CanonicalPcmProfile.PROFILE_ID,
                )
                ?.canonicalSha256
        }
        runner =
            Stage16BGateBAsrBenchmarkRunner(
                application = app,
                pcmSourceResolver = container.pcmSourceResolver,
                modelManager = container.modelManager,
                modelUseRegistry = container.modelUseRegistry,
                localSpeechSettings = { container.localSpeechSettingsStore.settings.value },
                loadCanonicalSha256 = lineageLoader,
            )
        b2Runner =
            Stage16BGateB2CompletenessRunner(
                application = app,
                pcmSourceResolver = container.pcmSourceResolver,
                modelManager = container.modelManager,
                modelUseRegistry = container.modelUseRegistry,
                localSpeechSettings = { container.localSpeechSettingsStore.settings.value },
                loadCanonicalSha256 = lineageLoader,
            )

        setContentView(buildContentView())
        recordingsJob =
            lifecycleScope.launch {
                container.recordingLibraryRepository.recordings.collectLatest(::renderRecordings)
            }
        benchmarkStateJob =
            lifecycleScope.launch {
                runner.state.collectLatest { state ->
                    latestBenchmarkState = state
                    if (selectedMode != SelectedMode.B2) renderSelectedState()
                }
            }
        b2StateJob =
            lifecycleScope.launch {
                b2Runner.state.collectLatest { state ->
                    latestB2State = state
                    if (selectedMode == SelectedMode.B2) renderSelectedState()
                }
            }
    }

    override fun onDestroy() {
        recordingsJob?.cancel()
        benchmarkStateJob?.cancel()
        b2StateJob?.cancel()
        if (isFinishing) {
            runner.cancel()
            b2Runner.cancel()
        }
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
                text = "Stage 16B — Gate B B0 / B1 / B2"
                textSize = 22f
            },
        )
        root.addView(
            TextView(this).apply {
                text =
                    "QA-only. FSMN runs once, then the exact same frozen PCM chunks are sent to " +
                        "Fun-ASR-Nano → SenseVoice → Qwen3-ASR. B0 checks candidate readiness; " +
                        "B1 scores frozen reference profiles; B2 compares real-recording completeness " +
                        "chunk by chunk without inventing CER. Short B0/B1/B2 runs are limited to " +
                        "${STAGE16B_GATE_B_B2_MAX_RECORDING_SECONDS}s."
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
                    selectedMode = SelectedMode.B0
                    selectedB1Profile = null
                    renderMode()
                }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Use B2 real-recording completeness mode"
                isAllCaps = false
                setOnClickListener {
                    selectedMode = SelectedMode.B2
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
                        selectedMode = SelectedMode.B1
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
                setOnClickListener {
                    if (selectedMode == SelectedMode.B2) b2Runner.cancel() else runner.cancel()
                }
            },
        )
        root.addView(
            Button(this).apply {
                text = "Reset result"
                setOnClickListener {
                    if (selectedMode == SelectedMode.B2) b2Runner.reset() else runner.reset()
                }
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
        modeText.text =
            when (selectedMode) {
                SelectedMode.B0 -> "Current mode: B0 readiness\nNo reference scoring."
                SelectedMode.B1 -> {
                    val profile = checkNotNull(selectedB1Profile)
                    "Current mode: B1 ${profile.name}\nReference: ${profile.referenceText}"
                }
                SelectedMode.B2 ->
                    "Current mode: B2 real-recording completeness\n" +
                        "No CER/WER. Review identical frozen chunks across all three ASRs."
            }
        renderSelectedState()
    }

    private fun renderSelectedState() {
        if (!::statusText.isInitialized) return
        statusText.text =
            if (selectedMode == SelectedMode.B2) {
                formatB2State(latestB2State)
            } else {
                formatBenchmarkState(latestBenchmarkState)
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
                        when (selectedMode) {
                            SelectedMode.B0 -> runner.start(recording.id)
                            SelectedMode.B1 -> runner.startB1(recording.id, checkNotNull(selectedB1Profile))
                            SelectedMode.B2 -> b2Runner.start(recording.id)
                        }
                    }
                },
            )
        }
    }

    private fun formatBenchmarkState(state: Stage16BGateBB0State): String =
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

    private fun formatB2State(state: Stage16BGateB2State): String =
        when (state) {
            Stage16BGateB2State.Idle -> "Idle"
            is Stage16BGateB2State.Running ->
                "Running\nrecording=${state.recordingId}\nphase=${state.phase}"
            is Stage16BGateB2State.Cancelled ->
                "Cancelled\nrecording=${state.recordingId}"
            is Stage16BGateB2State.Failed ->
                buildString {
                    appendLine("B2 FAILED")
                    appendLine("recording=${state.recordingId}")
                    appendLine(state.message)
                    if (state.missingModelIds.isNotEmpty()) {
                        appendLine("missing=${state.missingModelIds.joinToString()}")
                    }
                }.trimEnd()
            is Stage16BGateB2State.Completed -> formatB2Result(state.result)
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

    private fun formatB2Result(result: Stage16BGateB2Result): String =
        buildString {
            appendLine(if (result.allCandidatesReady) "B2 COMPLETE" else "B2 INCOMPLETE")
            appendLine("recording=${result.recordingId}")
            appendLine("canonicalSha256=${result.canonicalSha256 ?: "unknown"}")
            appendLine("vad=${result.vad.id}@${result.vad.version}/${result.vad.revision}")
            appendLine("vadWallMs=${result.vadWallTimeMillis}")
            appendLine("speechSegments=${result.speechSegments.size}")
            appendLine("frozenAsrInputs=${result.frozenAsrInputBoundaries.size}")
            appendLine("manualReview=missing-content/head-tail/repetition/hallucination")

            result.models.forEach { observation ->
                appendLine()
                appendLine("[${observation.modelId}] ${if (observation.ready) "READY" else "NOT READY"}")
                observation.errorMessage?.let { appendLine("error=$it") }
                appendLine("engineOpenMs=${observation.engineOpenTimeMillis ?: "-"}")
                appendLine("asrCalls=${observation.chunks.size}")
                appendLine("blankCalls=${observation.blankChunkIndexes.size}")
                appendLine(
                    "blankChunkIndexes=${if (observation.blankChunkIndexes.isEmpty()) "none" else observation.blankChunkIndexes.joinToString()}",
                )
                appendLine("nonBlankChunks=${observation.nonBlankChunkCount}")
                appendLine("startPssKb=${observation.startingPssKb ?: "-"}")
                observation.benchmark?.let { benchmark ->
                    appendLine("asrWallMs=${benchmark.asrTimeMillis ?: "-"}")
                    appendLine("totalWallMs=${benchmark.wallTimeMillis}")
                    appendLine("rtf=${benchmark.realTimeFactor?.let { "%.4f".format(it) } ?: "-"}")
                    appendLine("peakPssKb=${benchmark.peakPssKb ?: "-"}")
                }
                appendLine("text=${observation.rawText.ifBlank { "<blank>" }}")
            }

            appendLine()
            appendLine("B2 FROZEN-CHUNK MATRIX")
            val byModel = result.models.associateBy(Stage16BGateB2ModelObservation::modelId)
            result.frozenAsrInputBoundaries.forEachIndexed { index, boundary ->
                val startSeconds = boundary.startSampleIndex.toDouble() / CanonicalPcmProfile.SAMPLE_RATE_HZ
                val endSeconds = boundary.endSampleIndexExclusive.toDouble() / CanonicalPcmProfile.SAMPLE_RATE_HZ
                appendLine()
                appendLine(
                    "chunk#$index samples=${boundary.startSampleIndex}..${boundary.endSampleIndexExclusive} " +
                        "time=${"%.2f".format(startSeconds)}..${"%.2f".format(endSeconds)}s",
                )
                Stage16BGateBBenchmarkContract.MODEL_IDS.forEach { modelId ->
                    val chunk = byModel[modelId]?.chunks?.getOrNull(index)
                    val marker = if (chunk == null) "<missing>" else if (chunk.blank) "<blank>" else chunk.rawText
                    appendLine("${shortModelName(modelId)}=$marker")
                }
            }
        }.trimEnd()

    private fun shortModelName(modelId: String): String =
        when (modelId) {
            STAGE16B_FUNASR_NANO_CANDIDATE_ID -> "FunASR"
            Stage8ModelIds.SECOND_PASS_ASR -> "SenseVoice"
            Stage13AOfflineModelIds.QWEN3_ASR -> "Qwen3"
            else -> modelId
        }

    private enum class SelectedMode {
        B0,
        B1,
        B2,
    }

    private companion object {
        const val MAX_RECORDINGS = 20
    }
}
