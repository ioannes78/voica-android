package io.github.ioannes78.voica.ui.realtime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.Stage16AQaArtificialAsrSlowdown
import io.github.ioannes78.voica.Stage16ARealtimeActionOutcome
import io.github.ioannes78.voica.Stage16ARealtimeRuntime
import io.github.ioannes78.voica.Stage16ARealtimeUiPhase
import io.github.ioannes78.voica.Stage16ARealtimeUiState
import kotlin.math.sqrt
import kotlinx.coroutines.launch

val LocalStage16ARealtimeRuntime = staticCompositionLocalOf<Stage16ARealtimeRuntime> {
    error("Stage16ARealtimeRuntime was not provided")
}

@Composable
fun RealtimeTranscriptionEntryCard(
    state: Stage16ARealtimeUiState,
    canStart: Boolean,
    onClick: () -> Unit,
) {
    if (shouldShowRealtimeMiniLiveBar(state.phase)) {
        RealtimeMiniLiveBar(
            state = state,
            onClick = onClick,
        )
        return
    }

    val supporting =
        when {
            state.phase == Stage16ARealtimeUiPhase.IDLE && !canStart ->
                "当前设备录音状态暂不允许启动实时转写"
            state.modelLabel != null ->
                realtimePhaseLabel(state.phase) + " · " + state.modelLabel
            else -> realtimePhaseLabel(state.phase)
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        ListItem(
            headlineContent = { Text("实时转写") },
            supportingContent = { Text(supporting, maxLines = 1) },
            trailingContent = {
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            },
        )
    }
}

@Composable
private fun RealtimeMiniLiveBar(
    state: Stage16ARealtimeUiState,
    onClick: () -> Unit,
) {
    val modelLabel = state.modelLabel?.trim()?.takeIf { it.isNotEmpty() }
    val headline =
        if (modelLabel == null) {
            realtimePhaseLabel(state.phase)
        } else {
            realtimePhaseLabel(state.phase) + " · " + modelLabel
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    headline,
                    maxLines = 1,
                    fontWeight = FontWeight.Medium,
                )
            },
            supportingContent = {
                Text(
                    realtimeMiniPreview(state),
                    maxLines = 1,
                )
            },
            trailingContent = {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "返回实时转写",
                )
            },
        )
    }
}

@Composable
fun RealtimeTranscriptionScreen(
    padding: PaddingValues,
    runtime: Stage16ARealtimeRuntime,
    canStart: Boolean,
    onBack: () -> Unit,
) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val readerListState = rememberLazyListState()
    val readerDragged by readerListState.interactionSource.collectIsDraggedAsState()
    val readerAtBottom by remember {
        derivedStateOf { !readerListState.canScrollForward }
    }
    var readerFollowMode by remember { mutableStateOf(RealtimeReaderFollowMode.FOLLOW_LIVE) }
    var actionInFlight by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var qaSlowdownMessage by remember { mutableStateOf<String?>(null) }

    val showEmptyReader =
        state.finalized.isEmpty() &&
            state.stableText.isBlank() &&
            state.partialText.isBlank()
    val hasTranscriptContent =
        state.finalized.isNotEmpty() ||
            state.stableText.isNotBlank() ||
            state.partialText.isNotBlank()
    val readerItemCount =
        (if (showEmptyReader) 1 else 0) +
            state.finalized.size +
            (if (state.stableText.isNotBlank()) 1 else 0) +
            (if (state.mutablePartialTail.isNotBlank()) 1 else 0) +
            (if (!state.lifecycleMessage.isNullOrBlank()) 1 else 0)

    LaunchedEffect(state.runId) {
        readerFollowMode = RealtimeReaderFollowMode.FOLLOW_LIVE
        qaSlowdownMessage = null
    }
    LaunchedEffect(readerDragged, readerAtBottom) {
        readerFollowMode =
            RealtimeReaderFollowPolicy.reduce(
                current = readerFollowMode,
                userDragging = readerDragged,
                atBottom = readerAtBottom,
            )
    }
    LaunchedEffect(
        readerItemCount,
        state.finalized.size,
        state.stableText,
        state.partialText,
        state.lifecycleMessage,
        readerFollowMode,
        readerDragged,
    ) {
        if (
            readerFollowMode == RealtimeReaderFollowMode.FOLLOW_LIVE &&
            !readerDragged &&
            readerItemCount > 0
        ) {
            readerListState.scrollToItem(readerItemCount - 1)
        }
    }

    fun launchAction(action: suspend () -> Stage16ARealtimeActionOutcome) {
        if (actionInFlight) return
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val outcome = action()
            actionInFlight = false
            if (!outcome.success || outcome.degraded) {
                actionMessage = outcome.message ?: outcome.code ?: "操作未完成"
            }
        }
    }

    fun armQaSlowdown(delayMs: Long) {
        qaSlowdownMessage =
            if (Stage16AQaArtificialAsrSlowdown.armOnce(delayMs)) {
                "S10 已武装：下一个 PCM 处理将暂停 ${delayMs} ms"
            } else {
                "S10 未武装：已有延迟待触发或当前不是 QA 包"
            }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回",
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "实时转写",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    state.modelLabel ?: realtimePhaseLabel(state.phase),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                realtimePhaseLabel(state.phase),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()

        if (shouldShowRealtimeWaveform(state.phase)) {
            RealtimeAudioActivityWaveform(
                levels = state.audioActivityLevels,
                active = state.phase == Stage16ARealtimeUiPhase.ACTIVE,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        LazyColumn(
            state = readerListState,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showEmptyReader) {
                item(key = "empty") {
                    Text(
                        emptyReaderMessage(state.phase),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(
                items = state.finalized,
                key = { item ->
                    "${item.sessionId}:${item.epochId}:${item.continuityIndex}"
                },
            ) { item ->
                SelectionContainer {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            if (state.stableText.isNotBlank()) {
                item(key = "stable") {
                    Text(
                        state.stableText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (state.mutablePartialTail.isNotBlank()) {
                item(key = "partial") {
                    Text(
                        state.mutablePartialTail,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.lifecycleMessage?.let { message ->
                item(key = "lifecycle") {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (
            RealtimeReaderFollowPolicy.shouldShowReturnToLive(
                mode = readerFollowMode,
                hasTranscriptContent = hasTranscriptContent,
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = {
                        readerFollowMode = RealtimeReaderFollowMode.FOLLOW_LIVE
                        if (readerItemCount > 0) {
                            scope.launch {
                                readerListState.animateScrollToItem(readerItemCount - 1)
                            }
                        }
                    },
                ) {
                    Text("↓ 回到实时")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        val visibleMessage = actionMessage ?: state.errorMessage
        if (!visibleMessage.isNullOrBlank()) {
            Text(
                visibleMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (
            Stage16AQaArtificialAsrSlowdown.isEnabled &&
            state.phase == Stage16ARealtimeUiPhase.ACTIVE
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { armQaSlowdown(1_200L) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("S10-A 延迟 1.2s")
                }
                OutlinedButton(
                    onClick = { armQaSlowdown(2_500L) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("S10-B 延迟 2.5s")
                }
            }
            qaSlowdownMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }

        RealtimeControls(
            state = state,
            canStart = canStart,
            actionInFlight = actionInFlight,
            onStart = { launchAction(runtime::start) },
            onPause = { launchAction(runtime::pause) },
            onResume = { launchAction(runtime::resume) },
            onStop = { launchAction(runtime::stop) },
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun RealtimeAudioActivityWaveform(
    levels: List<Float>,
    active: Boolean,
) {
    val color =
        if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val alpha = if (active) 0.82f else 0.38f

    Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        val barCount = 28
        val visibleCount = levels.size.coerceAtMost(barCount)
        val leftPaddingCount = barCount - visibleCount
        val step = size.width / barCount.toFloat()
        val centerY = size.height / 2f
        val minHalfHeight = 1.dp.toPx()
        val maxHalfHeight = (size.height * 0.46f).coerceAtLeast(minHalfHeight)
        val strokeWidth = 2.dp.toPx()

        for (index in 0 until barCount) {
            val sourceIndex = index - leftPaddingCount
            val rawLevel =
                if (sourceIndex in 0 until visibleCount) {
                    levels[levels.size - visibleCount + sourceIndex]
                } else {
                    0f
                }
            val visualLevel = sqrt(rawLevel.coerceIn(0f, 1f))
            val halfHeight =
                minHalfHeight + visualLevel * (maxHalfHeight - minHalfHeight)
            val x = step * (index + 0.5f)
            drawLine(
                color = color.copy(alpha = alpha),
                start = androidx.compose.ui.geometry.Offset(x, centerY - halfHeight),
                end = androidx.compose.ui.geometry.Offset(x, centerY + halfHeight),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun shouldShowRealtimeWaveform(phase: Stage16ARealtimeUiPhase): Boolean =
    when (phase) {
        Stage16ARealtimeUiPhase.STARTING,
        Stage16ARealtimeUiPhase.ACTIVE,
        Stage16ARealtimeUiPhase.PAUSED,
        Stage16ARealtimeUiPhase.RESUMING,
        -> true

        else -> false
    }

internal fun shouldShowRealtimeMiniLiveBar(phase: Stage16ARealtimeUiPhase): Boolean =
    when (phase) {
        Stage16ARealtimeUiPhase.PREPARING,
        Stage16ARealtimeUiPhase.STARTING,
        Stage16ARealtimeUiPhase.ACTIVE,
        Stage16ARealtimeUiPhase.PAUSED,
        Stage16ARealtimeUiPhase.RESUMING,
        Stage16ARealtimeUiPhase.FINALIZING,
        Stage16ARealtimeUiPhase.INTERRUPTED,
        -> true

        Stage16ARealtimeUiPhase.IDLE,
        Stage16ARealtimeUiPhase.COMPLETED,
        Stage16ARealtimeUiPhase.FAILED,
        -> false
    }

internal fun realtimeMiniPreview(state: Stage16ARealtimeUiState): String {
    state.partialText.trim().takeIf { it.isNotEmpty() }?.let { return it }
    state.stableText.trim().takeIf { it.isNotEmpty() }?.let { return it }
    state.finalized.lastOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    state.lifecycleMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return "实时内容将在这里持续更新"
}

@Composable
private fun RealtimeControls(
    state: Stage16ARealtimeUiState,
    canStart: Boolean,
    actionInFlight: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    when (state.phase) {
        Stage16ARealtimeUiPhase.IDLE,
        Stage16ARealtimeUiPhase.COMPLETED,
        Stage16ARealtimeUiPhase.FAILED,
        -> {
            Button(
                onClick = onStart,
                enabled = canStart && !actionInFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("开始实时转写")
            }
        }

        Stage16ARealtimeUiPhase.ACTIVE -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPause,
                    enabled = !actionInFlight,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("暂停")
                }
                Button(
                    onClick = onStop,
                    enabled = !actionInFlight,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("停止")
                }
            }
        }

        Stage16ARealtimeUiPhase.PAUSED -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onResume,
                    enabled = !actionInFlight,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("继续")
                }
                OutlinedButton(
                    onClick = onStop,
                    enabled = !actionInFlight,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("停止")
                }
            }
        }

        Stage16ARealtimeUiPhase.PREPARING,
        Stage16ARealtimeUiPhase.STARTING,
        Stage16ARealtimeUiPhase.RESUMING,
        Stage16ARealtimeUiPhase.FINALIZING,
        -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(
                    realtimePhaseLabel(state.phase),
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Stage16ARealtimeUiPhase.INTERRUPTED -> {
            Text(
                state.lifecycleMessage ?: "实时转写已中断",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun realtimePhaseLabel(phase: Stage16ARealtimeUiPhase): String =
    when (phase) {
        Stage16ARealtimeUiPhase.IDLE -> "未开始"
        Stage16ARealtimeUiPhase.PREPARING -> "正在准备"
        Stage16ARealtimeUiPhase.STARTING -> "正在启动"
        Stage16ARealtimeUiPhase.ACTIVE -> "实时转写中"
        Stage16ARealtimeUiPhase.PAUSED -> "已暂停"
        Stage16ARealtimeUiPhase.RESUMING -> "正在继续"
        Stage16ARealtimeUiPhase.FINALIZING -> "正在完成最后内容…"
        Stage16ARealtimeUiPhase.INTERRUPTED -> "连接已中断"
        Stage16ARealtimeUiPhase.COMPLETED -> "已完成"
        Stage16ARealtimeUiPhase.FAILED -> "启动失败"
    }

private fun emptyReaderMessage(phase: Stage16ARealtimeUiPhase): String =
    when (phase) {
        Stage16ARealtimeUiPhase.IDLE,
        Stage16ARealtimeUiPhase.COMPLETED,
        Stage16ARealtimeUiPhase.FAILED,
        -> "开始后，实时识别内容会显示在这里。"
        Stage16ARealtimeUiPhase.PAUSED -> "录音已暂停，已确认的内容会保留在这里。"
        Stage16ARealtimeUiPhase.INTERRUPTED -> "连接已中断，已完成的转写内容仍会保留。"
        else -> "正在等待语音内容…"
    }
