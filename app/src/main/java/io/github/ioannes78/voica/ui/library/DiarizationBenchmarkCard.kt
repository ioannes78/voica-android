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
import io.github.ioannes78.voica.DiarizationBenchmarkReport
import io.github.ioannes78.voica.DiarizationBenchmarkRunner
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun DiarizationBenchmarkCard(
    recordingId: String,
    recordingName: String,
    canonicalReady: Boolean,
    blocked: Boolean,
    runner: DiarizationBenchmarkRunner,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var referenceSpeakerCountText by remember(recordingId) { mutableStateOf("") }
    var report by remember(recordingId) { mutableStateOf<DiarizationBenchmarkReport?>(null) }
    var errorMessage by remember(recordingId) { mutableStateOf<String?>(null) }
    var runningJob by remember(recordingId) { mutableStateOf<Job?>(null) }
    val running = runningJob?.isActive == true
    val referenceSpeakerCount =
        referenceSpeakerCountText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val referenceInvalid =
        referenceSpeakerCountText.isNotBlank() &&
            (referenceSpeakerCount == null || referenceSpeakerCount <= 0)

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
                "Stage 13A · Diarization Benchmark",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "QA/Debug 专用。自动说话人数下依次比较 ERes2Net 与 CAM++；复用正式 Pyannote、anchor aggregation 和 stitching，但不会写入说话人分离历史。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = referenceSpeakerCountText,
                onValueChange = {
                    referenceSpeakerCountText =
                        it.filter(Char::isDigit).take(2)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !running,
                singleLine = true,
                label = { Text("真实说话人数（可选）") },
                supportingText = {
                    Text(
                        if (referenceInvalid) {
                            "请输入大于 0 的整数"
                        } else {
                            "填写后计算 Speaker 数误差、多拆 Speaker 和少识别 Speaker"
                        },
                    )
                },
                isError = referenceInvalid,
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
                        "设备录音、转写或正式说话人分离进行中，Benchmark 暂不可启动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled =
                        canonicalReady &&
                            !blocked &&
                            !running &&
                            !referenceInvalid,
                    onClick = {
                        errorMessage = null
                        report = null
                        runningJob =
                            scope.launch {
                                try {
                                    report =
                                        runner.runEmbeddingComparison(
                                            recordingId = recordingId,
                                            referenceSpeakerCount = referenceSpeakerCount,
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
                    Text(if (running) "运行中…" else "说话人 2 模型")
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
                    "两模型顺序执行，不并行。建议测试期间不要播放音频或运行其它重任务。",
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
                    result.environment.manufacturer + " " +
                        result.environment.model + " · " +
                        (result.environment.socModel ?: result.environment.hardware),
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
                                    "Peak PSS " +
                                        formatDiarizationMegabytes(resultCase.peakPssKb) +
                                        " · Thermal " +
                                        diarizationThermalLabel(resultCase.thermalStatusMax),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "Speaker " + resultCase.detectedSpeakerCount +
                                        " · Turn " + resultCase.turnCount +
                                        " · Window " + resultCase.windowCount,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                resultCase.referenceSpeakerCount?.let { reference ->
                                    Text(
                                        "真实 " + reference +
                                            " · 误差 " + resultCase.speakerCountAbsoluteError +
                                            " · 多拆 " + resultCase.fragmentationExtraSpeakers +
                                            " · 少识别 " + resultCase.mergeMissingSpeakers,
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
                                "Voica Diarization Benchmark - " + recordingName,
                                result.toJson(),
                            ),
                        )
                    },
                ) {
                    Text("复制 Diarization Benchmark JSON")
                }
            }
        }
    }
}

private fun formatDiarizationMegabytes(kilobytes: Long): String =
    String.format(
        Locale.US,
        "%.1f MB",
        kilobytes.toDouble() / 1024.0,
    )

private fun diarizationThermalLabel(status: Int?): String =
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
