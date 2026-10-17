package io.github.ioannes78.voica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * QA-only Stage 16A.1.6 calibration surface.
 *
 * This Activity never owns AudioRecord. It only selects which AudioSource the normal phone capture
 * lifecycle will try on its next start, then displays retained diagnostics from that same capture and
 * the formal phone-recording runtime. The latter makes route-boundary, bounded recovery, and manual
 * route-loss pause behavior observable without adding another capture path or changing production
 * routing behavior.
 */
class Stage16A16AudioCalibrationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!stage16A16AudioCalibrationEntryVisible(BuildConfig.APPLICATION_ID)) {
            finish()
            return
        }
        setContent {
            MaterialTheme {
                Stage16A16AudioCalibrationScreen(onClose = ::finish)
            }
        }
    }
}

internal fun stage16A16AudioCalibrationEntryVisible(applicationId: String): Boolean =
    applicationId.endsWith(".qa")

@Composable
private fun Stage16A16AudioCalibrationScreen(
    onClose: () -> Unit,
) {
    val override by Stage16A16QaAudioSourceOverride.state.collectAsState()
    var refreshGeneration by remember { mutableIntStateOf(0) }
    val capture = remember(refreshGeneration) { PhoneMicrophoneCaptureDiagnostics.snapshot() }
    val context = LocalContext.current
    val recording =
        remember(refreshGeneration) {
            runCatching {
                (context.applicationContext as VoicaApplication)
                    .container
                    .phoneMicrophoneRecordingRuntime
                    .state
                    .value
            }.getOrNull()
        }
    val recordingDiagnostics =
        recording
            ?.takeIf { it.recordingId != null }
            ?.toStage16A16RouteDiagnosticsText()
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Stage 16A.1.6 音频校准",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "仅 QA 构建。这里不创建麦克风录音，只控制下一次正常“手机录音/手机实时转写”的 AudioSource，并读取同一路径的诊断结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("下一次录音 AudioSource", style = MaterialTheme.typography.titleMedium)
                    Stage16A16ModeButton(
                        label = Stage16A16QaAudioSourceMode.AUTO.label,
                        selected = override.mode == Stage16A16QaAudioSourceMode.AUTO,
                        onClick = {
                            Stage16A16QaAudioSourceOverride.setMode(
                                Stage16A16QaAudioSourceMode.AUTO,
                            )
                        },
                    )
                    Stage16A16ModeButton(
                        label = Stage16A16QaAudioSourceMode.FORCE_VOICE_RECOGNITION.label,
                        selected =
                            override.mode == Stage16A16QaAudioSourceMode.FORCE_VOICE_RECOGNITION,
                        onClick = {
                            Stage16A16QaAudioSourceOverride.setMode(
                                Stage16A16QaAudioSourceMode.FORCE_VOICE_RECOGNITION,
                            )
                        },
                    )
                    Stage16A16ModeButton(
                        label = Stage16A16QaAudioSourceMode.FORCE_MIC.label,
                        selected = override.mode == Stage16A16QaAudioSourceMode.FORCE_MIC,
                        onClick = {
                            Stage16A16QaAudioSourceOverride.setMode(
                                Stage16A16QaAudioSourceMode.FORCE_MIC,
                            )
                        },
                    )
                    Text(
                        "选择后返回 Voica，用相同距离、相同音量、相同文本录制；停止后再回到本页查看聚合结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("最近一次手机采集结果", style = MaterialTheme.typography.titleMedium)
                    Text(
                        capture?.toDiagnosticsText() ?: "暂无手机采集诊断，请先完成一次手机录音或手机实时转写。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("最近一次手机录音路由结果", style = MaterialTheme.typography.titleMedium)
                    Text(
                        recordingDiagnostics ?: "暂无手机录音路由诊断，请先完成一次手机录音或手机实时转写。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { refreshGeneration += 1 }) {
                            Text("刷新结果")
                        }
                        OutlinedButton(
                            enabled = capture != null || recordingDiagnostics != null,
                            onClick = {
                                val text = buildString {
                                    appendLine(Stage16A16QaAudioSourceOverride.snapshot().toDiagnosticsText())
                                    capture?.let {
                                        appendLine()
                                        appendLine(it.toDiagnosticsText())
                                    }
                                    recordingDiagnostics?.let {
                                        appendLine()
                                        append(it)
                                    }
                                }
                                clipboard.setText(AnnotatedString(text))
                            },
                        ) {
                            Text("复制结果")
                        }
                    }
                }
            }
        }

        item {
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("返回 Voica")
            }
        }
    }
}

private fun PhoneMicrophoneRecordingSnapshot.toStage16A16RouteDiagnosticsText(): String =
    buildString {
        appendLine("[Stage 16A.1.6 phone recording route transition]")
        appendLine(
            "phase=$phase recordingId=${recordingId ?: "n/a"} writtenSamples=$writtenSamples " +
                "durationMs=$durationMs",
        )
        appendLine(
            "activeEpoch=${activeEpochId ?: -1} completedEpochs=$completedEpochs " +
                "routeBoundaries=$routeBoundaryCount discontinuityEpochs=$discontinuityEpochCount " +
                "lastRouteBoundary=${lastRouteBoundaryReason?.name ?: "n/a"}",
        )
        appendLine(
            "recoveryAttempts=$captureRecoveryAttempts recoverySuccesses=$captureRecoverySuccesses " +
                "recoveryFailures=$captureRecoveryFailures " +
                "recoveryTrigger=${lastCaptureRecoveryTrigger?.name ?: "n/a"}",
        )
        appendLine(
            "manualRouteLossPauses=$manualRouteLossPauses " +
                "manualRouteRecoveryPending=$manualRouteRecoveryPending " +
                "lastManualRouteLostDeviceId=${lastManualRouteLostDeviceId ?: -1}",
        )
        append(
            "hadCaptureInterruption=$hadCaptureInterruption " +
                "lastFailure=${lastFailure ?: "n/a"}",
        )
    }

@Composable
private fun Stage16A16ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text("✓ $label")
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(label)
        }
    }
}
