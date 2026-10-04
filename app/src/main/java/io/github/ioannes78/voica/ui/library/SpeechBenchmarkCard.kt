package io.github.ioannes78.voica.ui.library

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.SpeechBenchmarkReport
import io.github.ioannes78.voica.SpeechBenchmarkRunner
import io.github.ioannes78.voica.SpeechBenchmarkTrack
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun SpeechBenchmarkCard(
    recordingId: String,
    recordingName: String,
    canonicalReady: Boolean,
    blocked: Boolean,
    runner: SpeechBenchmarkRunner,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var referenceText by remember(recordingId) { mutableStateOf("") }
    var report by remember(recordingId) { mutableStateOf<SpeechBenchmarkReport?>(null) }
    var errorMessage by remember(recordingId) { mutableStateOf<String?>(null) }
    var runningJob by remember(recordingId) { mutableStateOf<Job?>(null) }
    val running = runningJob?.isActive == true

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Stage 13A · Speech Benchmark",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "QA/Debug 专用。实时轨比较 3 个 streaming 模型；离线轨固定同一 VAD / first-pass / 标点，仅替换 SenseVoice / FireRed / Qwen 第二遍模型。不会创建或覆盖转写版本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = referenceText,
                onValueChange = { referenceText = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !running,
                minLines = 2,
                maxLines = 5,
                label = { Text("参考文本（可选，用于 CER/WER）") },
            )

            when {
                !canonicalReady ->
                    Text(
                        "请先生成并验证标准 PCM 音频。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                blocked ->
                    Text(
                        "设备录音、转写或说话人分离进行中，Benchmark 暂不可启动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canonicalReady && !blocked && !running,
                    onClick = {
                        errorMessage = null
                        report = null
                        runningJob =
                            scope.launch {
                                try {
                                    report =
                                        runner.runRealtimeComparison(
                                            recordingId = recordingId,
                                            referenceText =
                                                referenceText.trim().takeIf {
                                                    it.isNotEmpty()
                                                },
                                        )
                                } catch (cancelled: CancellationException) {
                                    errorMessage = "Benchmark 已取消"
                                    throw cancelled
                                } catch (error: Throwable) {
                                    errorMessage =
                                        error.message ?: error::class.java.simpleName
                                } finally {
                                    runningJob = null
                                }
                            }
                    },
                ) {
                    Text(if (running) "运行中…" else "实时 3 模型")
                }
                Button(
                    enabled = canonicalReady && !blocked && !running,
                    onClick = {
                        errorMessage = null
                        report = null
                        runningJob =
                            scope.launch {
                                try {
                                    report =
                                        runner.runOfflineComparison(
                                            recordingId = recordingId,
                                            referenceText =
                                                referenceText.trim().takeIf {
                                                    it.isNotEmpty()
                                                },
                                        )
                                } catch (cancelled: CancellationException) {
                                    errorMessage = "Benchmark 已取消"
                                    throw cancelled
                                } catch (error: Throwable) {
                                    errorMessage =
                                        error.message ?: error::class.java.simpleName
                                } finally {
                                    runningJob = null
                                }
                            }
                    },
                ) {
                    Text(if (running) "运行中…" else "离线 3 模型")
                }
                if (running) {
                    OutlinedButton(
                        onClick = {
                            runningJob?.cancel(CancellationException("user cancelled benchmark"))
                        },
                    ) {
                        Text("取消")
                    }
                }
            }

            if (running) {
                Text(
                    "模型按顺序执行，为减少相互干扰不并行运行。离线 Qwen 体积和内存占用较高；建议测试期间不要播放音频或运行其它重任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            errorMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (message.contains("取消")) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }

            report?.let { result ->
                Text(
                    if (result.track == SpeechBenchmarkTrack.REALTIME) {
                        "Realtime ASR Benchmark"
                    } else {
                        "Offline Final-Pass Benchmark"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    result.environment.manufacturer + " " +
                        result.environment.model + " · " +
                        (result.environment.socModel ?: result.environment.hardware) +
                        " · RAM " + formatMegabytes(result.environment.totalRamBytes / 1024L),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                result.cases.forEach { resultCase ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                resultCase.displayName,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (resultCase.error != null) {
                                Text(
                                    "失败：" + resultCase.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                Text(
                                    "依赖 first-pass: " + resultCase.pipelineFirstPassModelId +
                                        " · punctuation: " + resultCase.pipelinePunctuationModelId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    String.format(
                                        Locale.US,
                                        "RTF %.3f · 耗时 %.1fs · CPU %.1fs",
                                        resultCase.rtf,
                                        resultCase.wallTimeMs / 1000.0,
                                        resultCase.cpuTimeMs / 1000.0,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Peak PSS " + formatMegabytes(resultCase.peakPssKb) +
                                        " · Thermal " + thermalLabel(resultCase.thermalStatusMax) +
                                        " · " + resultCase.outputCharacterCount + " 字符",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (resultCase.streamingProbeSpeechMs != null) {
                                    Text(
                                        buildString {
                                            append("Realtime probe ")
                                            resultCase.firstPartialAudioMs?.let {
                                                append("· 首 partial 需音频 ")
                                                append(it)
                                                append(" ms ")
                                            }
                                            resultCase.firstPartialComputeMs?.let {
                                                append("· 加速计算 ")
                                                append(it)
                                                append(" ms ")
                                            }
                                            resultCase.finalizationLatencyMs?.let {
                                                append("· Finalize ")
                                                append(it)
                                                append(" ms")
                                            }
                                        }.trim(),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                resultCase.streamingProbeError?.let { probeError ->
                                    Text(
                                        "Realtime probe 失败：" + probeError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (resultCase.cer != null || resultCase.wer != null) {
                                    Text(
                                        buildString {
                                            resultCase.cer?.let {
                                                append(
                                                    String.format(
                                                        Locale.US,
                                                        "CER %.2f%%",
                                                        it * 100.0,
                                                    ),
                                                )
                                            }
                                            resultCase.wer?.let {
                                                if (isNotEmpty()) append(" · ")
                                                append(
                                                    String.format(
                                                        Locale.US,
                                                        "WER %.2f%%",
                                                        it * 100.0,
                                                    ),
                                                )
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(
                                "Voica Speech Benchmark - " + recordingName,
                                result.toJson(),
                            ),
                        )
                    },
                ) {
                    Text("复制 Benchmark JSON")
                }
            }
        }
    }
}

private fun formatMegabytes(kilobytes: Long): String =
    String.format(
        Locale.US,
        "%.1f MB",
        kilobytes.toDouble() / 1024.0,
    )

private fun thermalLabel(status: Int?): String =
    when (status) {
        null -> "N/A"
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> status.toString()
    }
