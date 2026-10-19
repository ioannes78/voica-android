package io.github.ioannes78.voica.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.SenseVoiceBenchmarkState
import io.github.ioannes78.voica.SenseVoiceVadBenchmarkObservation
import io.github.ioannes78.voica.SpeechBenchmarkRunner
import io.github.ioannes78.voica.SpeechBenchmarkState
import io.github.ioannes78.voica.transcript.VadBenchmarkObservation
import java.util.Locale

/** Explicit QA-only surface. Formal release never receives a non-null SpeechBenchmarkRunner. */
@Composable
internal fun Stage16BVadBenchmarkCard(
    runner: SpeechBenchmarkRunner,
    recordingId: String,
) {
    val a1State by runner.state.collectAsState()
    val a2State by runner.a2State.collectAsState()
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Stage 16B · VAD Gate A", style = MaterialTheme.typography.titleSmall)
            Text(
                "QA 专用。A1 比较三方 VAD；A2 固定同一 SenseVoice 比较下游转写。不会修改正常转写默认 VAD。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("A1 · VAD 核心比较", style = MaterialTheme.typography.labelLarge)
            when (val current = a1State) {
                SpeechBenchmarkState.Idle ->
                    Button(onClick = { runner.startVadGateA(recordingId) }) {
                        Text("运行 A1 · 三方 VAD")
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
                        Button(onClick = { runner.startVadGateA(recordingId) }) {
                            Text("重新运行 A1")
                        }
                        OutlinedButton(onClick = runner::reset) {
                            Text("清除 A1")
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
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { runner.startVadGateA(recordingId) }) {
                            Text("重试 A1")
                        }
                        OutlinedButton(onClick = runner::reset) {
                            Text("清除")
                        }
                    }
                }

                is SpeechBenchmarkState.Cancelled -> {
                    Text("A1 已取消", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { runner.startVadGateA(recordingId) }) {
                        Text("重新运行 A1")
                    }
                }
            }

            Text("A2 · 同 SenseVoice 下游比较", style = MaterialTheme.typography.labelLarge)
            Text(
                "仅比较 Silero/FSMN/TEN 的 30 秒主配置；TEN 官方 5 秒参考不进入 A2。",
                style = MaterialTheme.typography.bodySmall,
            )
            when (val current = a2State) {
                SenseVoiceBenchmarkState.Idle ->
                    Button(onClick = { runner.startSenseVoiceGateA2(recordingId) }) {
                        Text("运行 A2 · 同 SenseVoice")
                    }

                is SenseVoiceBenchmarkState.Running -> {
                    Text(
                        "${current.completedProfiles + 1}/${current.totalProfiles} · ${current.currentProfile.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("正在执行 VAD → SenseVoice → 标点收尾", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = runner::cancel) {
                        Text("取消")
                    }
                }

                is SenseVoiceBenchmarkState.Completed -> {
                    current.observations.forEach { observation ->
                        SenseVoiceObservationText(observation)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                copyA2Report(
                                    context = context,
                                    recordingId = current.recordingId,
                                    observations = current.observations,
                                )
                            },
                        ) {
                            Text("复制 A2 完整结果")
                        }
                        OutlinedButton(onClick = runner::resetA2) {
                            Text("清除 A2")
                        }
                    }
                    OutlinedButton(onClick = { runner.startSenseVoiceGateA2(recordingId) }) {
                        Text("重新运行 A2")
                    }
                }

                is SenseVoiceBenchmarkState.Failed -> {
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
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { runner.startSenseVoiceGateA2(recordingId) }) {
                            Text("重试 A2")
                        }
                        OutlinedButton(onClick = runner::resetA2) {
                            Text("清除")
                        }
                    }
                }

                is SenseVoiceBenchmarkState.Cancelled -> {
                    Text("A2 已取消", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { runner.startSenseVoiceGateA2(recordingId) }) {
                        Text("重新运行 A2")
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

@Composable
private fun SenseVoiceObservationText(observation: SenseVoiceVadBenchmarkObservation) {
    val speechSeconds = observation.speechDurationMillis / 1000.0
    val pssMb = observation.peakPssKb?.let { format1(it / 1024.0) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(observation.profile.displayName, style = MaterialTheme.typography.labelLarge)
        Text(
            "VAD段 ${observation.speechSegmentCount} · speech ${format1(speechSeconds)}s · 转写段 ${observation.transcriptSegmentCount} · 空段 ${observation.blankTranscriptSegmentCount}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "有效字符 ${observation.normalizedCharacterCount} · 标点 ${observation.punctuationCount} · VAD ${observation.vadWallTimeMillis}ms · SenseVoice ${observation.senseVoiceWallTimeMillis}ms · 总 ${observation.totalWallTimeMillis}ms · PSS ${pssMb}MB",
            style = MaterialTheme.typography.bodySmall,
        )
        observation.punctuationFallbackError?.let { warning ->
            Text(
                "标点回退：$warning",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            observation.text.ifBlank { "（无转写文本）" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun copyA2Report(
    context: Context,
    recordingId: String,
    observations: List<SenseVoiceVadBenchmarkObservation>,
) {
    val report = buildA2Report(recordingId, observations)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Stage 16B A2", report))
}

internal fun buildA2Report(
    recordingId: String,
    observations: List<SenseVoiceVadBenchmarkObservation>,
): String = buildString {
    appendLine("Stage 16B · Gate A2 · Same SenseVoice")
    appendLine("recordingId=$recordingId")
    observations.forEach { observation ->
        appendLine()
        appendLine("[${observation.profile.displayName}]")
        appendLine(
            "vadSegments=${observation.speechSegmentCount} speechMs=${observation.speechDurationMillis} " +
                "transcriptSegments=${observation.transcriptSegmentCount} blankSegments=${observation.blankTranscriptSegmentCount}",
        )
        appendLine(
            "chars=${observation.normalizedCharacterCount} punctuation=${observation.punctuationCount} " +
                "vadMs=${observation.vadWallTimeMillis} senseVoiceMs=${observation.senseVoiceWallTimeMillis} " +
                "totalMs=${observation.totalWallTimeMillis} peakPssKb=${observation.peakPssKb ?: -1}",
        )
        observation.punctuationFallbackError?.let { appendLine("punctuationFallback=$it") }
        appendLine("TEXT_BEGIN")
        appendLine(observation.text)
        appendLine("TEXT_END")
    }
}

private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
private fun format3(value: Double): String = String.format(Locale.US, "%.3f", value)
