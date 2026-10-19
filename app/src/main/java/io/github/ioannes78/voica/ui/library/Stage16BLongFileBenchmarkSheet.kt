package io.github.ioannes78.voica.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.LongFileVadBenchmarkObservation
import io.github.ioannes78.voica.LongFileVadBenchmarkState
import io.github.ioannes78.voica.Stage16BLongFileBenchmarkRunner
import io.github.ioannes78.voica.VoicaApplication
import java.util.Locale

@Composable
internal fun Stage16BLongFileBenchmarkSheet(recordingId: String) {
    val context = LocalContext.current
    val application = context.applicationContext as VoicaApplication
    val runner =
        remember(application) {
            Stage16BLongFileBenchmarkRunner(
                application = application,
                pcmSourceResolver = application.container.pcmSourceResolver,
                modelManager = application.container.modelManager,
                modelUseRegistry = application.container.modelUseRegistry,
                localSpeechSettings = { application.container.localSpeechSettingsStore.settings.value },
            )
        }
    val state by runner.state.collectAsState()

    DisposableEffect(runner) {
        onDispose { runner.cancel() }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Stage 16B · Gate A4", style = MaterialTheme.typography.titleSmall)
        Text(
            "长文件 / 热稳定性。仅比较 Silero 生产基线与 FSMN 候选；TEN 已退出后续 Gate A。固定同一 SenseVoice 和当前线程设置，不写入转写结果。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "建议先用 ≥30 分钟真实录音；重点看是否完整跑完、PSS、总 RTF，以及 SenseVoice 最后 1/4 相对最前 1/4 的 RTF 变化。",
            style = MaterialTheme.typography.bodySmall,
        )

        when (val current = state) {
            LongFileVadBenchmarkState.Idle ->
                Button(onClick = { runner.start(recordingId) }) {
                    Text("运行 A4 · Silero vs FSMN")
                }

            is LongFileVadBenchmarkState.Running -> {
                Text(
                    "${current.completedProfiles + 1}/${current.totalProfiles} · ${current.currentProfile.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("正在执行 VAD → SenseVoice 长文件稳定性测试", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = runner::cancel) {
                    Text("取消")
                }
            }

            is LongFileVadBenchmarkState.Completed -> {
                current.observations.forEach { observation ->
                    A4ObservationText(observation)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            copyA4Report(
                                context = context,
                                recordingId = current.recordingId,
                                observations = current.observations,
                            )
                        },
                    ) {
                        Text("复制 A4 完整结果")
                    }
                    OutlinedButton(onClick = runner::reset) {
                        Text("清除 A4")
                    }
                }
                OutlinedButton(onClick = { runner.start(recordingId) }) {
                    Text("重新运行 A4")
                }
            }

            is LongFileVadBenchmarkState.Failed -> {
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
                    Button(onClick = { runner.start(recordingId) }) {
                        Text("重试 A4")
                    }
                    OutlinedButton(onClick = runner::reset) {
                        Text("清除")
                    }
                }
            }

            is LongFileVadBenchmarkState.Cancelled -> {
                Text("A4 已取消", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { runner.start(recordingId) }) {
                    Text("重新运行 A4")
                }
            }
        }
    }
}

@Composable
private fun A4ObservationText(observation: LongFileVadBenchmarkObservation) {
    val startPssMb = observation.startingPssKb / 1024.0
    val peakPssMb = observation.peakPssKb / 1024.0
    Text(observation.profile.displayName, style = MaterialTheme.typography.labelLarge)
    Text(
        "录音 ${formatDuration(observation.recordingDurationMillis)} · speech ${formatDuration(observation.speechDurationMillis)} · VAD段 ${observation.speechSegmentCount} · ASR calls ${observation.asrCallCount}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "转写段 ${observation.transcriptSegmentCount} · blank ${observation.blankTranscriptSegmentCount} · chars ${observation.normalizedCharacterCount}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "VAD ${observation.vadWallTimeMillis}ms · SenseVoice ${observation.senseVoiceWallTimeMillis}ms · 总 ${observation.totalWallTimeMillis}ms · totalRTF ${formatRatio(observation.totalRtf)} · ASR RTF ${formatRatio(observation.senseVoiceRtf)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "ASR first¼ ${formatRatio(observation.firstQuarterSenseVoiceRtf)} · last¼ ${formatRatio(observation.lastQuarterSenseVoiceRtf)} · slowdown ×${formatRatio(observation.thermalSlowdownRatio)}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "PSS ${String.format(Locale.US, "%.1f", startPssMb)}→${String.format(Locale.US, "%.1f", peakPssMb)}MB · threads ${observation.effectiveThreads} · lang ${observation.senseVoiceLanguage} · ITN ${observation.senseVoiceItn}",
        style = MaterialTheme.typography.bodySmall,
    )
    observation.punctuationFallbackError?.let { error ->
        Text("punctuationFallback=$error", style = MaterialTheme.typography.bodySmall)
    }
}

private fun copyA4Report(
    context: Context,
    recordingId: String,
    observations: List<LongFileVadBenchmarkObservation>,
) {
    val report =
        buildString {
            appendLine("Stage 16B · Gate A4 · Long File Stability")
            appendLine("recordingId=$recordingId")
            appendLine("profiles=Silero,FSMN; TEN excluded")
            observations.forEach { observation ->
                appendLine()
                appendLine("[${observation.profile.displayName}]")
                appendLine(
                    "recordingMs=${observation.recordingDurationMillis} speechMs=${observation.speechDurationMillis} vadSegments=${observation.speechSegmentCount}",
                )
                appendLine(
                    "transcriptSegments=${observation.transcriptSegmentCount} blankSegments=${observation.blankTranscriptSegmentCount} chars=${observation.normalizedCharacterCount} asrCalls=${observation.asrCallCount}",
                )
                appendLine(
                    "vadMs=${observation.vadWallTimeMillis} senseVoiceMs=${observation.senseVoiceWallTimeMillis} totalMs=${observation.totalWallTimeMillis}",
                )
                appendLine(
                    "totalRtf=${formatRatio(observation.totalRtf)} senseVoiceRtf=${formatRatio(observation.senseVoiceRtf)} firstQuarterRtf=${formatRatio(observation.firstQuarterSenseVoiceRtf)} lastQuarterRtf=${formatRatio(observation.lastQuarterSenseVoiceRtf)} thermalSlowdown=${formatRatio(observation.thermalSlowdownRatio)}",
                )
                appendLine(
                    "startPssKb=${observation.startingPssKb} peakPssKb=${observation.peakPssKb} threads=${observation.effectiveThreads} language=${observation.senseVoiceLanguage} itn=${observation.senseVoiceItn}",
                )
                observation.punctuationFallbackError?.let { appendLine("punctuationFallback=$it") }
            }
        }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Stage 16B Gate A4", report))
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun formatRatio(value: Double?): String =
    value?.let { String.format(Locale.US, "%.3f", it) } ?: "n/a"
