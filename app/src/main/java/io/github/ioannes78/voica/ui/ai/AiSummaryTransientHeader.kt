package io.github.ioannes78.voica.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.AiSummaryRunState
import io.github.ioannes78.voica.ai.AiSummaryEnginePhase

@Composable
fun AiSummaryTransientHeader(
    running: AiSummaryRunState.Running?,
    candidateId: String?,
    candidatePreviewing: Boolean,
    recordingName: String,
    onCancel: () -> Unit,
    onViewCandidate: (String) -> Unit,
    onReturnCurrent: () -> Unit,
    onAdoptCandidate: (String) -> Unit,
    onIgnoreCandidate: (String) -> Unit,
) {
    when {
        running != null ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "AI 总结",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                summaryProgressText(running),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                recordingName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onCancel) { Text("取消生成") }
                    }
                    if (running.totalUnits > 0) {
                        val progress =
                            (running.completedUnits.toFloat() / running.totalUnits.toFloat())
                                .coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

        candidateId != null ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (candidatePreviewing) {
                                "新结果预览 · 当前总结尚未切换"
                            } else {
                                "新的总结结果已生成"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            recordingName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            if (candidatePreviewing) {
                                onReturnCurrent()
                            } else {
                                onViewCandidate(candidateId)
                            }
                        },
                    ) {
                        Text(if (candidatePreviewing) "返回当前" else "查看")
                    }
                    Button(onClick = { onAdoptCandidate(candidateId) }) {
                        Text("使用新结果")
                    }
                    TextButton(onClick = { onIgnoreCandidate(candidateId) }) {
                        Text("忽略")
                    }
                }
            }
    }
}

internal fun summaryProgressText(state: AiSummaryRunState.Running): String =
    when (state.phase) {
        AiSummaryEnginePhase.PREPARING -> "正在准备当前转写…"
        AiSummaryEnginePhase.ANALYZING -> "正在分析内容…"
        AiSummaryEnginePhase.MAPPING ->
            if (state.totalUnits > 0) {
                "正在总结 ${state.completedUnits}/${state.totalUnits}…"
            } else {
                "正在总结…"
            }
        AiSummaryEnginePhase.REDUCING ->
            if (state.totalUnits > 0) {
                "正在合并 ${state.completedUnits}/${state.totalUnits}…"
            } else {
                "正在合并…"
            }
        AiSummaryEnginePhase.VALIDATING -> "正在校验结果…"
    }
