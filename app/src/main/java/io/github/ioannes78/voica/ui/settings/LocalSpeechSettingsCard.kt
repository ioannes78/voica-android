package io.github.ioannes78.voica.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.LocalSpeechSettingsStore
import io.github.ioannes78.voica.LocalVadSettings
import io.github.ioannes78.voica.MAX_CONFIGURABLE_THREADS
import io.github.ioannes78.voica.OfflineAsrQualityChoice
import io.github.ioannes78.voica.RealtimeAsrModelChoice
import io.github.ioannes78.voica.SpeakerCountChoice
import io.github.ioannes78.voica.SpeechPerformanceProfile
import io.github.ioannes78.voica.Stage13AOfflineModelIds
import io.github.ioannes78.voica.Stage13ARealtimeModelIds
import io.github.ioannes78.voica.resolvePerformance
import io.github.ioannes78.voica.model.ModelAvailability
import io.github.ioannes78.voica.model.ModelManager

private data class RealtimeModelOption(
    val choice: RealtimeAsrModelChoice,
    val title: String,
    val subtitle: String,
    val modelId: String?,
)

private data class OfflineModelOption(
    val choice: OfflineAsrQualityChoice,
    val title: String,
    val subtitle: String,
    val modelId: String,
)

@Composable
internal fun LocalSpeechSettingsCard(
    store: LocalSpeechSettingsStore,
    modelManager: ModelManager,
) {
    val settings by store.settings.collectAsState()
    val operations by modelManager.operations.collectAsState()
    val resolvedPerformance = settings.resolvePerformance()
    var availability by remember {
        mutableStateOf<Map<String, ModelAvailability?>>(emptyMap())
    }

    LaunchedEffect(operations) {
        availability =
            (Stage13ARealtimeModelIds.ALL + Stage13AOfflineModelIds.ALL)
                .distinct()
                .associateWith { modelId ->
                    modelManager.availability(modelId)
                }
    }

    val options =
        listOf(
            RealtimeModelOption(
                choice = RealtimeAsrModelChoice.AUTO,
                title = "自动（推荐）",
                subtitle = "优先中文 Large Transducer；不可用时依次回退 CTC、Small Bilingual",
                modelId = null,
            ),
            RealtimeModelOption(
                choice = RealtimeAsrModelChoice.SMALL_BILINGUAL,
                title = "极速 · Small Bilingual",
                subtitle = "资源占用最低 · 中英双语 · 兼容与省资源模式",
                modelId = Stage13ARealtimeModelIds.SMALL_BILINGUAL,
            ),
            RealtimeModelOption(
                choice = RealtimeAsrModelChoice.CHINESE_LARGE_TRANSDUCER,
                title = "中文均衡 · Large Transducer",
                subtitle = "2025-06-30 · 中文会议与连续口语默认候选",
                modelId = Stage13ARealtimeModelIds.CHINESE_LARGE_TRANSDUCER,
            ),
            RealtimeModelOption(
                choice = RealtimeAsrModelChoice.CHINESE_LARGE_CTC,
                title = "中文实时高质量 · Large CTC",
                subtitle = "2025-06-30 · 中文实时 A/B 主力模型",
                modelId = Stage13ARealtimeModelIds.CHINESE_LARGE_CTC,
            ),
        )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "实时转写模型",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "3 个实时模型长期保留。自动模式允许回退；明确选择某个模型时不会静默切换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            options.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider()
                val modelStatus =
                    option.modelId?.let { id ->
                        availabilityStatus(availability[id])
                    }
                ListItem(
                    headlineContent = { Text(option.title) },
                    supportingContent = {
                        Column {
                            Text(option.subtitle)
                            if (modelStatus != null) {
                                Text(
                                    modelStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    leadingContent = {
                        RadioButton(
                            selected = settings.realtimeAsrModel == option.choice,
                            onClick = null,
                        )
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                store.setRealtimeAsrModel(option.choice)
                            },
                )
            }
        }

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("离线高质量转写", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "仅影响“高质量转写”的第二遍识别；快速转写不受影响。明确选择 FireRed/Qwen 后不会静默回退。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                offlineModelOptions().forEachIndexed { index, option ->
                    if (index > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(option.title) },
                        supportingContent = {
                            Column {
                                Text(option.subtitle)
                                Text(
                                    availabilityStatus(availability[option.modelId]),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingContent = {
                            RadioButton(
                                selected = settings.offlineAsrQuality == option.choice,
                                onClick = null,
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    store.setOfflineAsrQuality(option.choice)
                                },
                    )
                }
            }
        }

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("说话人数", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "明确人数会同时约束原生 clustering 与跨分块 global stitching。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                speakerCountOptions().forEachIndexed { index, option ->
                    if (index > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(option.second) },
                        supportingContent = { Text(option.third) },
                        leadingContent = {
                            RadioButton(
                                selected = settings.speakerCount == option.first,
                                onClick = null,
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    store.setSpeakerCount(option.first)
                                },
                    )
                }
            }
        }

        Text(
            "性能模式",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        )
        performanceOptions().forEachIndexed { index, option ->
            if (index > 0) HorizontalDivider()
            ListItem(
                headlineContent = { Text(option.second) },
                supportingContent = { Text(option.third) },
                leadingContent = {
                    RadioButton(
                        selected = settings.performanceProfile == option.first,
                        onClick = null,
                    )
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            store.setPerformanceProfile(option.first)
                        },
            )
        }

        Text(
            "CPU 线程数",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        )
        val threadChoices =
            buildList<Int?> {
                add(null)
                listOf(1, 2, 3, 4, 6, 8)
                    .filter { it <= minOf(Runtime.getRuntime().availableProcessors().coerceAtLeast(1), MAX_CONFIGURABLE_THREADS) }
                    .forEach(::add)
            }
        threadChoices.forEachIndexed { index, threads ->
            if (index > 0) HorizontalDivider()
            ListItem(
                headlineContent = {
                    Text(if (threads == null) "自动" else "$threads 线程")
                },
                supportingContent = {
                    Text(
                        if (threads == null) {
                            "当前有效值：${resolvedPerformance.effectiveThreads} 线程"
                        } else {
                            "手动线程数优先于性能模式"
                        },
                    )
                },
                leadingContent = {
                    RadioButton(
                        selected = settings.requestedThreads == threads,
                        onClick = null,
                    )
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            store.setRequestedThreads(threads)
                        },
            )
        }

        Text(
            "线程数会同时应用于 VAD、实时 ASR、二遍 ASR、标点、说话人分离和 Speaker Embedding。上限按设备逻辑核心数及 8 线程安全上限裁剪。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )

        HorizontalDivider()
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("VAD 高级参数", style = MaterialTheme.typography.titleMedium)
            Text(
                "仅开放 Silero 当前真正生效的参数。数值同时用于转写和说话人分离。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VadParameterRow(
            title = "检测阈值",
            value = formatVad(settings.vad.threshold),
            onDecrease = {
                store.setVadSettings(
                    settings.vad.copy(
                        threshold = (settings.vad.threshold - 0.05F).coerceAtLeast(0.05F),
                    ),
                )
            },
            onIncrease = {
                store.setVadSettings(
                    settings.vad.copy(
                        threshold = (settings.vad.threshold + 0.05F).coerceAtMost(0.95F),
                    ),
                )
            },
        )
        VadParameterRow(
            title = "最短静音",
            value = formatVad(settings.vad.minSilenceDurationSeconds) + " s",
            onDecrease = {
                store.setVadSettings(
                    settings.vad.copy(
                        minSilenceDurationSeconds =
                            (settings.vad.minSilenceDurationSeconds - 0.05F).coerceAtLeast(0.05F),
                    ),
                )
            },
            onIncrease = {
                store.setVadSettings(
                    settings.vad.copy(
                        minSilenceDurationSeconds =
                            (settings.vad.minSilenceDurationSeconds + 0.05F).coerceAtMost(2F),
                    ),
                )
            },
        )
        VadParameterRow(
            title = "最短语音",
            value = formatVad(settings.vad.minSpeechDurationSeconds) + " s",
            onDecrease = {
                store.setVadSettings(
                    settings.vad.copy(
                        minSpeechDurationSeconds =
                            (settings.vad.minSpeechDurationSeconds - 0.05F).coerceAtLeast(0.05F),
                    ),
                )
            },
            onIncrease = {
                store.setVadSettings(
                    settings.vad.copy(
                        minSpeechDurationSeconds =
                            (settings.vad.minSpeechDurationSeconds + 0.05F).coerceAtMost(2F),
                    ),
                )
            },
        )
        VadParameterRow(
            title = "最长语音段",
            value = settings.vad.maxSpeechDurationSeconds.toInt().toString() + " s",
            onDecrease = {
                store.setVadSettings(
                    settings.vad.copy(
                        maxSpeechDurationSeconds =
                            (settings.vad.maxSpeechDurationSeconds - 5F).coerceAtLeast(5F),
                    ),
                )
            },
            onIncrease = {
                store.setVadSettings(
                    settings.vad.copy(
                        maxSpeechDurationSeconds =
                            (settings.vad.maxSpeechDurationSeconds + 5F).coerceAtMost(120F),
                    ),
                )
            },
        )
        TextButton(
            onClick = store::resetVadSettings,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text("恢复 VAD 推荐值")
        }
    }
}

@Composable
private fun VadParameterRow(
    title: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = {
            Row {
                TextButton(onClick = onDecrease) { Text("−") }
                Text(
                    value,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                )
                TextButton(onClick = onIncrease) { Text("+") }
            }
        },
    )
}

private fun formatVad(value: Float): String {
    val rounded = kotlin.math.round(value * 100F) / 100F
    return rounded.toString()
}

private fun offlineModelOptions() =
    listOf(
        OfflineModelOption(
            OfflineAsrQualityChoice.AUTO,
            "自动（推荐）",
            "当前保持 SenseVoice 兼容默认；后续由 Benchmark 决定是否调整默认",
            Stage13AOfflineModelIds.SENSEVOICE,
        ),
        OfflineModelOption(
            OfflineAsrQualityChoice.BALANCED,
            "均衡 · SenseVoice",
            "约 240 MB 安装体积 · 当前稳定基线",
            Stage13AOfflineModelIds.SENSEVOICE,
        ),
        OfflineModelOption(
            OfflineAsrQualityChoice.HIGH_QUALITY,
            "高质量 · FireRedASR2 CTC",
            "中文 / 英文 / 口音与中英混合候选 · 约 740 MB 安装体积",
            Stage13AOfflineModelIds.FIRERED_ASR2,
        ),
        OfflineModelOption(
            OfflineAsrQualityChoice.ULTRA,
            "最高质量 · Qwen3-ASR 0.6B",
            "条件型 Ultra 候选 · 约 941 MB 安装体积 · 仅建议高内存设备测试",
            Stage13AOfflineModelIds.QWEN3_ASR,
        ),
    )

private fun speakerCountOptions() =
    listOf(
        Triple(SpeakerCountChoice.AUTO, "自动", "自动估算说话人数"),
        Triple(SpeakerCountChoice.ONE, "1 人", "整个录音最终只保留一个 GLOBAL speaker"),
        Triple(SpeakerCountChoice.TWO, "2 人", "精确 2 人；原生聚类与跨分块全局结果都受约束"),
        Triple(SpeakerCountChoice.THREE, "3 人", "精确 3 人；不足 3 个有效 speaker 时不伪造结果"),
        Triple(SpeakerCountChoice.FOUR, "4 人", "精确 4 人；不足 4 个有效 speaker 时不伪造结果"),
        Triple(
            SpeakerCountChoice.FIVE_PLUS,
            "5+ 人",
            "至少 5 人，允许自动增长到 8 人；不足 5 个有效 speaker 时不伪造结果",
        ),
    )

private fun performanceOptions() =
    listOf(
        Triple(SpeechPerformanceProfile.AUTO, "自动", "保持兼容默认；当前按 2 线程起步"),
        Triple(SpeechPerformanceProfile.POWER_SAVER, "省电", "1 线程，优先降低持续 CPU 压力"),
        Triple(SpeechPerformanceProfile.BALANCED, "均衡", "2 线程，作为当前默认基线"),
        Triple(SpeechPerformanceProfile.PERFORMANCE, "性能", "最多 4 线程，优先降低推理延迟"),
    )

private fun availabilityStatus(availability: ModelAvailability?): String =
    when {
        availability == null ->
            "当前模型清单未提供；请先在“本地模型”中刷新候选/生产清单"
        availability.activeVersion != null ->
            "已就绪 · ${availability.activeVersion} r${availability.activeRevision}"
        availability.installedVersion != null ->
            "已下载，尚未启用"
        else ->
            "需要下载模型"
    }
