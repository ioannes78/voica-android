package io.github.ioannes78.voica.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.SpeechBenchmarkRunner
import io.github.ioannes78.voica.SpeechBenchmarkState
import io.github.ioannes78.voica.transcript.VadBenchmarkObservation
import java.util.Locale

/** Explicit QA-only surface. Formal release never receives a non-null SpeechBenchmarkRunner. */
@Composable
internal fun Stage16BVadBenchmarkCard(
    runner: SpeechBenchmarkRunner,
    recordingId: String,
    canonicalReady: Boolean,
) {
    val state by runner.state.collectAsState()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Stage 16B · VAD Gate A", style = MaterialTheme.typography.titleSmall)
            Text(
                "QA 专用。主比较统一为 30 秒：Silero / FSMN / TEN；另保留 TEN 官方 5 秒参考。不会修改正常转写的默认 VAD。",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!canonicalReady) {
                Text(
                    "需要先生成并验证 canonical PCM。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (val current = state) {
                SpeechBenchmarkState.Idle ->
                    Button(
                        enabled = canonicalReady,
                        onClick = { runner.startVadGateA(recordingId) },
                    ) {
                        Text("运行三方 VAD 基准")
                    }

                is SpeechBenchmarkState.Running -> {
                    Text(
                        "${current.completedProfiles + 1}/${current.totalProfiles} · ${current.currentProfile.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(onClick = runner::cancel) {
                        Text("取消")
                    }
                }

                is SpeechBenchmarkState.Completed -> {
                    current.observations.forEach { observation ->
                        BenchmarkObservationText(observation)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = canonicalReady,
                            onClick = { runner.startVadGateA(recordingId) },
                        ) {
                            Text("重新运行")
                        }
                        OutlinedButton(onClick = runner::reset) {
                            Text("清除结果")
                        }
                    }
                }

                is SpeechBenchmarkState.Failed -> {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (current.missingModelIds.isNotEmpty()) {
                        Text(
                            "缺少：${current.missingModelIds.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "请使用 Stage 16B Gate A 组合候选清单，并确保 FSMN 与 TEN 均已下载、验证并启用。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = canonicalReady,
                            onClick = { runner.startVadGateA(recordingId) },
                        ) {
                            Text("重试")
                        }
                        OutlinedButton(onClick = runner::reset) {
                            Text("清除")
                        }
                    }
                }

                is SpeechBenchmarkState.Cancelled -> {
                    Text("已取消", style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = canonicalReady,
                        onClick = { runner.startVadGateA(recordingId) },
                    ) {
                        Text("重新运行")
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchmarkObservationText(observation: VadBenchmarkObservation) {
    val rtf = observation.realTimeFactor?.let { format3(it) } ?: "—"
    val speechSeconds = observation.speechDurationMillis / 1000.0
    val average = observation.averageSegmentDurationMillis?.let { format1(it) } ?: "—"
    val median = observation.medianSegmentDurationMillis?.let { format1(it) } ?: "—"
    val pssMb = observation.peakPssKb?.let { format1(it / 1024.0) } ?: "—"
    val marker = if (observation.profile.primaryGateAProfile) "主比较" else "参考"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${observation.profile.displayName} · $marker",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            "片段 ${observation.segmentCount} · speech ${format1(speechSeconds)}s · RTF $rtf · peak PSS ${pssMb}MB",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "平均 ${average}ms · 中位 ${median}ms · <500ms ${observation.shortFragmentCount} · max切分代理 ${observation.maxDurationSplitProxyCount}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
