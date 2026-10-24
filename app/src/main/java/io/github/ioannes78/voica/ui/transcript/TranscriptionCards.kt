package io.github.ioannes78.voica.ui.transcript

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.R
import io.github.ioannes78.voica.Stage13AOfflineModelIds
import io.github.ioannes78.voica.Stage13ARealtimeModelIds
import io.github.ioannes78.voica.TranscriptionRunState
import io.github.ioannes78.voica.VoicaApplication
import io.github.ioannes78.voica.database.DiarizationSpeakerEntity
import io.github.ioannes78.voica.ui.theme.VoicaThemeTokens
import io.github.ioannes78.voica.transcript.TextProjectionQuality
import io.github.ioannes78.voica.transcript.TranscriptionPhase
import io.github.ioannes78.voica.transcript.TranscriptionProgress
import io.github.ioannes78.voica.transcript.TranscriptionProgressActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("离线转写 · " + runningModelLabel(state.modelId), style = MaterialTheme.typography.labelLarge)
                        Text(
                            buildString {
                                append(phaseLabel(state.progress))
                                state.progress.fraction?.let { fraction ->
                                    append(' ')
                                    append((fraction * 100.0).roundToInt().coerceIn(0, 100))
                                    append('%')
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        recordingName?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.transcription_cancel)) }
                }
                val fraction = state.progress.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            is TranscriptionRunState.Completed -> {
                state.warning?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is TranscriptionRunState.Failed -> {
                Text(
                    stringResource(R.string.transcription_failed, productTranscriptionFailureMessage(state.message)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.missingModelIds.isNotEmpty()) {
                    Text(
                        stringResource(R.string.transcription_missing_models, state.missingModelIds.joinToString()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            is TranscriptionRunState.Cancelled -> {
                Text(stringResource(R.string.transcription_cancelled), style = MaterialTheme.typography.bodySmall)
            }
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val selected = versions.firstOrNull { it.transcriptionId == selectedTranscriptionId } ?: versions.first()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text("离线转写", style = MaterialTheme.typography.titleSmall)
            Text(
                formatCompletedAt(selected.completedAtMs) + " · " + selected.segmentCount + " 段",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            OutlinedButton(onClick = { expanded = true }) { Text("版本 " + versions.size) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                versions.forEach { version ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text((if (version.transcriptionId == selected.transcriptionId) "✓ " else "") + "离线转写")
                                Text(formatCompletedAt(version.completedAtMs) + " · " + version.segmentCount + " 段", style = MaterialTheme.typography.bodySmall)
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
    edited: Boolean = false,
    onRenameSpeaker: (speakerId: String, requestedName: String?) -> Unit,
) {
    var speakerListOpen by remember(document.alignmentId) { mutableStateOf(false) }
    var pendingSpeaker by remember(document.alignmentId) { mutableStateOf<TranscriptSpeakerDisplay?>(null) }
    var renameValue by remember(document.alignmentId) { mutableStateOf("") }
    val sourceText = sourceModelLabel(document.sourceModelId)

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        Text("$sourceText · ${document.segments.size} 段", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (document.speakers.isNotEmpty()) {
            Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${document.speakers.size}位说话人",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { speakerListOpen = true },
            )
        }
        if (edited) {
            Text(" · 已编辑", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (speakerListOpen) {
        AlertDialog(
            onDismissRequest = { speakerListOpen = false },
            title = { Text("编辑说话人") },
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
                            Text(speaker.displayName ?: stringResource(R.string.diarization_speaker_default, speaker.speakerOrdinal))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { speakerListOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    pendingSpeaker?.let { speaker ->
        AlertDialog(
            onDismissRequest = { pendingSpeaker = null },
            title = { Text(stringResource(R.string.diarization_speaker_rename_title, speaker.speakerOrdinal)) },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.diarization_speaker_name_label)) },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val requested = renameValue
                        pendingSpeaker = null
                        onRenameSpeaker(speaker.speakerId, requested)
                    },
                ) { Text(stringResource(R.string.diarization_speaker_save)) }
            },
            dismissButton = { TextButton(onClick = { pendingSpeaker = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun TranscriptSegmentCard(
    segment: TranscriptDisplaySegment,
    isActive: Boolean,
    activeCueId: String?,
    searchHighlighted: Boolean = false,
    syncEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onAddAnnotation: ((Long) -> Unit)? = null,
    annotationAction: (@Composable () -> Unit)? = null,
) {
    val activeCue = remember(segment.stableId, activeCueId) {
        segment.cues.firstOrNull { cue -> cue.id == activeCueId && cue.projectionQuality == TextProjectionQuality.EXACT }
    }
    val transcriptColors = VoicaThemeTokens.colors
    val displayText = segment.text.ifBlank { stringResource(R.string.transcript_empty_segment) }
    val tokenHighlightColor = transcriptColors.accent.copy(alpha = 0.26f)
    val annotatedText = buildAnnotatedString {
        append(displayText)
        if (
            segment.text.isNotBlank() && activeCue != null &&
            activeCue.textStartOffset in 0..segment.text.length &&
            activeCue.textEndOffsetExclusive in 0..segment.text.length &&
            activeCue.textEndOffsetExclusive > activeCue.textStartOffset
        ) {
            if (activeCue.textStartOffset > 0) {
                addStyle(
                    SpanStyle(color = transcriptColors.transcriptPlayedText),
                    0,
                    activeCue.textStartOffset,
                )
            }
            addStyle(
                SpanStyle(
                    background = tokenHighlightColor,
                    color = transcriptColors.transcriptCurrentWordText,
                ),
                activeCue.textStartOffset,
                activeCue.textEndOffsetExclusive,
            )
            if (activeCue.textEndOffsetExclusive < segment.text.length) {
                addStyle(
                    SpanStyle(color = transcriptColors.transcriptUpcomingText),
                    activeCue.textEndOffsetExclusive,
                    segment.text.length,
                )
            }
        }
    }
    var textLayout by remember(segment.stableId) { mutableStateOf<TextLayoutResult?>(null) }

    val application = LocalContext.current.applicationContext as? VoicaApplication
    val repository = application?.container?.diarizationRepository
    val scope = rememberCoroutineScope()
    val spanId = remember(segment.stableId) {
        segment.stableId.takeIf { it.startsWith("span:") }?.removePrefix("span:")?.takeIf(String::isNotBlank)
    }
    var speakerChoices by remember(segment.stableId) { mutableStateOf<List<DiarizationSpeakerEntity>>(emptyList()) }
    var speakerDialogOpen by remember(segment.stableId) { mutableStateOf(false) }
    var localSpeakerId by remember(segment.stableId, segment.speakerId) { mutableStateOf(segment.speakerId) }
    var localSpeakerOrdinal by remember(segment.stableId, segment.speakerOrdinal) { mutableStateOf(segment.speakerOrdinal) }
    var localSpeakerDisplayName by remember(segment.stableId, segment.speakerDisplayName) { mutableStateOf(segment.speakerDisplayName) }
    var localAmbiguous by remember(segment.stableId, segment.ambiguous) { mutableStateOf(segment.ambiguous) }

    val baseSpeakerLabel =
        when {
            !localSpeakerDisplayName.isNullOrBlank() -> localSpeakerDisplayName
            localSpeakerOrdinal != null -> stringResource(R.string.diarization_speaker_default, localSpeakerOrdinal!!)
            localAmbiguous -> null
            else -> null
        }
    val speakerLabel =
        when {
            baseSpeakerLabel != null && segment.overlap -> "重叠语音 · $baseSpeakerLabel"
            baseSpeakerLabel != null -> baseSpeakerLabel
            localAmbiguous -> stringResource(R.string.diarization_speaker_overlap_ambiguous)
            segment.speakerAssignmentAvailable -> stringResource(R.string.diarization_speaker_unresolved)
            else -> null
        }
    val canEditSpeakerAssignment = spanId != null && repository != null && segment.speakerAssignmentAvailable
    val paragraphBackground by
        animateColorAsState(
            targetValue =
                when {
                    searchHighlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                    isActive -> transcriptColors.transcriptActiveParagraphBg.copy(alpha = 0.52f)
                    else -> transcriptColors.surface
                },
            animationSpec = tween(durationMillis = 150),
            label = "transcriptParagraphBackground",
        )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(paragraphBackground, RoundedCornerShape(8.dp))
                .drawBehind {
                    if (isActive) {
                        val strokeWidth = 2.dp.toPx()
                        drawLine(
                            color = transcriptColors.transcriptActiveLineBar,
                            start = Offset(strokeWidth / 2f, 6.dp.toPx()),
                            end = Offset(strokeWidth / 2f, size.height - 6.dp.toPx()),
                            strokeWidth = strokeWidth,
                        )
                    }
                }
                .clickable(enabled = syncEnabled, onClick = { onSeek(segment.startSampleIndex) })
                .padding(start = 13.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            speakerLabel?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isActive || searchHighlighted || canEditSpeakerAssignment) transcriptColors.accent else transcriptColors.textSecondary,
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(enabled = canEditSpeakerAssignment) {
                                val targetSpanId = spanId ?: return@clickable
                                val targetRepository = repository ?: return@clickable
                                scope.launch {
                                    val choices = targetRepository.loadSpeakersForSpan(targetSpanId)
                                    if (choices.isNotEmpty()) {
                                        speakerChoices = choices
                                        speakerDialogOpen = true
                                    }
                                }
                            },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatSampleRange(segment.startSampleIndex, segment.endSampleIndexExclusive), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (annotationAction != null) {
                    annotationAction()
                } else {
                    onAddAnnotation?.let { addAnnotation ->
                        Text(
                            "＋标注",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(enabled = syncEnabled) { addAnnotation(segment.startSampleIndex) },
                        )
                    }
                }
            }
        }
        Text(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium,
            color = transcriptColors.textPrimary,
            onTextLayout = { textLayout = it },
            modifier =
                Modifier.pointerInput(segment.stableId, activeCueId, syncEnabled, onAddAnnotation) {
                    detectTapGestures(
                        onTap = { position ->
                            if (syncEnabled) {
                                val offset = textLayout?.getOffsetForPosition(position)
                                val cue = offset?.let { textOffset ->
                                    segment.cues.firstOrNull { candidate ->
                                        candidate.projectionQuality == TextProjectionQuality.EXACT &&
                                            textOffset >= candidate.textStartOffset && textOffset < candidate.textEndOffsetExclusive
                                    }
                                }
                                onSeek(cue?.startSampleIndex ?: segment.startSampleIndex)
                            }
                        },
                        onLongPress = { position ->
                            if (syncEnabled && onAddAnnotation != null) {
                                val offset = textLayout?.getOffsetForPosition(position)
                                val cue = offset?.let { textOffset ->
                                    segment.cues.firstOrNull { candidate ->
                                        candidate.projectionQuality == TextProjectionQuality.EXACT &&
                                            textOffset >= candidate.textStartOffset && textOffset < candidate.textEndOffsetExclusive
                                    }
                                }
                                onAddAnnotation(cue?.startSampleIndex ?: segment.startSampleIndex)
                            }
                        },
                    )
                },
        )
    }

    if (speakerDialogOpen && spanId != null && repository != null) {
        AlertDialog(
            onDismissRequest = { speakerDialogOpen = false },
            title = { Text("确认这段文字的说话人") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (segment.overlap) {
                        Text(
                            "该时间段检测到重叠语音；选择说话人只确认这段文字的主要归属，不会删除重叠证据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    speakerChoices.forEach { speaker ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                speakerDialogOpen = false
                                scope.launch {
                                    if (repository.assignSpeakerToSpan(spanId, speaker.id)) {
                                        localSpeakerId = speaker.id
                                        localSpeakerOrdinal = speaker.speakerOrdinal
                                        localSpeakerDisplayName = speaker.displayName
                                        localAmbiguous = false
                                    }
                                }
                            },
                        ) {
                            Text(speaker.displayName ?: stringResource(R.string.diarization_speaker_default, speaker.speakerOrdinal))
                        }
                    }
                    if (segment.overlap && !localAmbiguous) {
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                speakerDialogOpen = false
                                scope.launch {
                                    if (repository.restoreAmbiguousOverlapSpan(spanId)) {
                                        localSpeakerId = null
                                        localSpeakerOrdinal = null
                                        localSpeakerDisplayName = null
                                        localAmbiguous = true
                                    }
                                }
                            },
                        ) { Text("恢复为重叠语音 · 说话人待确认") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { speakerDialogOpen = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

internal fun productTranscriptionFailureMessage(rawMessage: String): String {
    val message = rawMessage.trim()
    if (message.isEmpty()) return "离线转写未完成，请重试"
    return when {
        message.contains("verified canonical PCM is not ready", ignoreCase = true) -> "标准音频尚未准备好，请稍后重试"
        message.contains("canonical PCM source is unavailable", ignoreCase = true) -> "标准音频当前不可用，请重新打开录音后重试"
        message.any { character -> character in '\u4e00'..'\u9fff' } -> message
        else -> "离线转写遇到内部错误，请重试；如持续出现，请查看诊断信息"
    }
}

private fun runningModelLabel(modelId: String?): String =
    when (modelId) {
        Stage13AOfflineModelIds.SENSEVOICE -> "SenseVoice"
        Stage13AOfflineModelIds.FUNASR_NANO -> "FunASR Nano"
        Stage13AOfflineModelIds.QWEN3_ASR -> "Qwen3-ASR"
        Stage13ARealtimeModelIds.SMALL_BILINGUAL -> "Small Bilingual"
        Stage13ARealtimeModelIds.CHINESE_LARGE_CTC -> "Large CTC"
        else -> "本地模型"
    }

private fun sourceModelLabel(modelId: String?): String =
    when (modelId) {
        Stage13AOfflineModelIds.SENSEVOICE -> "SenseVoice · 快速"
        Stage13AOfflineModelIds.FUNASR_NANO -> "FunASR Nano · 高质量"
        Stage13AOfflineModelIds.QWEN3_ASR -> "Qwen3-ASR · 历史转写"
        Stage13AOfflineModelIds.FIRERED_ASR2 -> "FireRedASR2 · 历史转写"
        Stage13ARealtimeModelIds.SMALL_BILINGUAL -> "Small Bilingual · 历史转写"
        else -> "离线转写"
    }

private fun phaseLabel(progress: TranscriptionProgress): String =
    if (progress.activity == TranscriptionProgressActivity.TIMELINE_ALIGNMENT) {
        "正在生成时间轴…"
    } else {
        when (progress.phase) {
            TranscriptionPhase.PREPARING -> "正在准备…"
            TranscriptionPhase.VAD -> "正在分析语音…"
            TranscriptionPhase.FIRST_PASS -> "正在识别…"
            TranscriptionPhase.SECOND_PASS -> "正在识别…"
            TranscriptionPhase.PUNCTUATION -> "正在处理标点…"
            TranscriptionPhase.PERSISTING -> "正在保存转写结果…"
        }
    }

private fun formatCompletedAt(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime().format(COMPLETED_AT_FORMAT)

private fun formatSampleRange(start: Long, end: Long): String = formatSampleTime(start) + " – " + formatSampleTime(end)
private val COMPLETED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatSampleTime(sampleIndex: Long): String {
    val seconds = sampleIndex.toDouble() / 16_000.0
    val totalWholeSeconds = seconds.toLong()
    val minutes = totalWholeSeconds / 60L
    val remainingSeconds = seconds - minutes * 60.0
    return String.format(Locale.US, "%02d:%05.2f", minutes, remainingSeconds)
}
