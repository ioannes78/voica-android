package io.github.ioannes78.voica.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.transcript.TextProjectionQuality
import io.github.ioannes78.voica.transcript.TranscriptionMode
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TranscriptionStatusCard(
    state: TranscriptionRunState,
    recordingName: String?,
    notice: String?,
    onCancel: () -> Unit,
) {
    if (state is TranscriptionRunState.Idle && notice == null) return
    if (state is TranscriptionRunState.Completed && state.warning == null && notice == null) return

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (state) {
            TranscriptionRunState.Idle -> Unit

            is TranscriptionRunState.Running -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            modeLabel(state.mode) + " · " + phaseLabel(state.progress.phase),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        recordingName?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.transcription_cancel))
                    }
                }
                val fraction = state.progress.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            is TranscriptionRunState.Completed -> {
                state.warning?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is TranscriptionRunState.Failed -> {
                Text(
                    stringResource(R.string.transcription_failed, state.message),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.missingModelIds.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.transcription_missing_models,
                            state.missingModelIds.joinToString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            is TranscriptionRunState.Cancelled -> {
                Text(
                    stringResource(R.string.transcription_cancelled),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TranscriptVersionListCard(
    versions: List<TranscriptVersionSummary>,
    recordingName: String?,
    selectedTranscriptionId: String?,
    onSelect: (String) -> Unit,
) {
    if (versions.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val selected =
        versions.firstOrNull { it.transcriptionId == selectedTranscriptionId }
            ?: versions.first()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                modeLabel(selected.mode),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                formatCompletedAt(selected.completedAtMs) +
                    " · " + selected.segmentCount + " 段",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            OutlinedButton(onClick = { expanded = true }) {
                Text("版本 " + versions.size)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                versions.forEach { version ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    (if (version.transcriptionId == selected.transcriptionId) "✓ " else "") +
                                        modeLabel(version.mode),
                                )
                                Text(
                                    formatCompletedAt(version.completedAtMs) +
                                        " · " + version.segmentCount + " 段",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(version.transcriptionId)
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptDocumentHeader(
    document: TranscriptDocument,
    recordingName: String?,
    onRenameSpeaker: (speakerId: String, requestedName: String?) -> Unit,
) {
    var speakerListOpen by remember(document.alignmentId) {
        mutableStateOf(false)
    }
    var pendingSpeaker by remember(document.alignmentId) {
        mutableStateOf<TranscriptSpeakerDisplay?>(null)
    }
    var renameValue by remember(document.alignmentId) {
        mutableStateOf("")
    }
    val modeText = modeLabel(document.mode)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            buildString {
                append(modeText)
                append(" · ")
                append(document.segments.size)
                append(" 段")
                if (document.speakers.isNotEmpty()) {
                    append(" · ")
                    append(document.speakers.size)
                    append(" 位说话人")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (document.speakers.isNotEmpty()) {
            TextButton(onClick = { speakerListOpen = true }) {
                Text("说话人")
            }
        }
    }

    if (speakerListOpen) {
        AlertDialog(
            onDismissRequest = { speakerListOpen = false },
            title = { Text("说话人") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    document.speakers.forEach { speaker ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                speakerListOpen = false
                                pendingSpeaker = speaker
                                renameValue = speaker.displayName.orEmpty()
                            },
                        ) {
                            Text(
                                speaker.displayName
                                    ?: stringResource(
                                        R.string.diarization_speaker_default,
                                        speaker.speakerOrdinal,
                                    ),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { speakerListOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingSpeaker?.let { speaker ->
        AlertDialog(
            onDismissRequest = { pendingSpeaker = null },
            title = {
                Text(
                    stringResource(
                        R.string.diarization_speaker_rename_title,
                        speaker.speakerOrdinal,
                    ),
                )
            },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = {
                        Text(stringResource(R.string.diarization_speaker_name_label))
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val requested = renameValue
                        pendingSpeaker = null
                        onRenameSpeaker(speaker.speakerId, requested)
                    },
                ) {
                    Text(stringResource(R.string.diarization_speaker_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSpeaker = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
fun TranscriptSegmentCard(
    segment: TranscriptDisplaySegment,
    isActive: Boolean,
    activeCueId: String?,
    syncEnabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    val activeCue =
        remember(segment.stableId, activeCueId) {
            segment.cues.firstOrNull { cue ->
                cue.id == activeCueId &&
                    cue.projectionQuality == TextProjectionQuality.EXACT
            }
        }
    val displayText =
        segment.text.ifBlank {
            stringResource(R.string.transcript_empty_segment)
        }
    val tokenHighlightColor = MaterialTheme.colorScheme.primaryContainer
    val annotatedText =
        buildAnnotatedString {
            append(displayText)
            if (
                segment.text.isNotBlank() &&
                activeCue != null &&
                activeCue.textStartOffset in 0..segment.text.length &&
                activeCue.textEndOffsetExclusive in 0..segment.text.length &&
                activeCue.textEndOffsetExclusive > activeCue.textStartOffset
            ) {
                addStyle(
                    SpanStyle(background = tokenHighlightColor),
                    start = activeCue.textStartOffset,
                    end = activeCue.textEndOffsetExclusive,
                )
            }
        }
    var textLayout by remember(segment.stableId) {
        mutableStateOf<TextLayoutResult?>(null)
    }

    val speakerLabel =
        when {
            !segment.speakerDisplayName.isNullOrBlank() -> segment.speakerDisplayName
            segment.speakerOrdinal != null ->
                stringResource(
                    R.string.diarization_speaker_default,
                    segment.speakerOrdinal,
                )
            segment.ambiguous ->
                stringResource(R.string.diarization_speaker_overlap_ambiguous)
            segment.speakerAssignmentAvailable ->
                stringResource(R.string.diarization_speaker_unresolved)
            else -> null
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (isActive) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(8.dp),
                )
                .clickable(
                    enabled = syncEnabled,
                    onClick = { onSeek(segment.startSampleIndex) },
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            speakerLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                formatSampleRange(
                    segment.startSampleIndex,
                    segment.endSampleIndexExclusive,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium,
            onTextLayout = { textLayout = it },
            modifier =
                Modifier.pointerInput(
                    segment.stableId,
                    activeCueId,
                    syncEnabled,
                ) {
                    detectTapGestures { position ->
                        if (!syncEnabled) return@detectTapGestures
                        val offset =
                            textLayout
                                ?.getOffsetForPosition(position)
                                ?: return@detectTapGestures
                        val cue =
                            segment.cues.firstOrNull { candidate ->
                                candidate.projectionQuality ==
                                    TextProjectionQuality.EXACT &&
                                    offset >= candidate.textStartOffset &&
                                    offset < candidate.textEndOffsetExclusive
                            }
                        onSeek(cue?.startSampleIndex ?: segment.startSampleIndex)
                    }
                },
        )
    }
}

@Composable
private fun modeLabel(mode: TranscriptionMode): String =
    when (mode) {
        TranscriptionMode.FAST ->
            stringResource(R.string.transcription_mode_fast)
        TranscriptionMode.HIGH_QUALITY ->
            stringResource(R.string.transcription_mode_high_quality)
    }

@Composable
private fun modeLabel(mode: String): String =
    when (mode) {
        "FAST" -> stringResource(R.string.transcription_mode_fast)
        "HIGH_QUALITY" -> stringResource(R.string.transcription_mode_high_quality)
        else -> mode
    }

@Composable
private fun phaseLabel(phase: TranscriptionPhase): String =
    when (phase) {
        TranscriptionPhase.PREPARING ->
            stringResource(R.string.transcription_phase_preparing)
        TranscriptionPhase.VAD ->
            stringResource(R.string.transcription_phase_vad)
        TranscriptionPhase.FIRST_PASS ->
            stringResource(R.string.transcription_phase_first_pass)
        TranscriptionPhase.SECOND_PASS ->
            stringResource(R.string.transcription_phase_second_pass)
        TranscriptionPhase.PUNCTUATION ->
            stringResource(R.string.transcription_phase_punctuation)
        TranscriptionPhase.PERSISTING ->
            stringResource(R.string.transcription_phase_persisting)
    }

private fun formatCompletedAt(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(COMPLETED_AT_FORMAT)

private fun formatSampleRange(
    start: Long,
    end: Long,
): String =
    formatSampleTime(start) + " – " + formatSampleTime(end)

private val COMPLETED_AT_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatSampleTime(sampleIndex: Long): String {
    val seconds = sampleIndex.toDouble() / 16_000.0
    val totalWholeSeconds = seconds.toLong()
    val minutes = totalWholeSeconds / 60L
    val remainingSeconds = seconds - minutes * 60.0
    return String.format(
        Locale.US,
        "%02d:%05.2f",
        minutes,
        remainingSeconds,
    )
}
