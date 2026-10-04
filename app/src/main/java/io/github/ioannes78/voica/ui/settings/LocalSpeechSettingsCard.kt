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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.LocalSpeechSettingsStore
import io.github.ioannes78.voica.MAX_CONFIGURABLE_THREADS
import io.github.ioannes78.voica.OfflineAsrQualityChoice
import io.github.ioannes78.voica.RealtimeAsrModelChoice
import io.github.ioannes78.voica.SenseVoiceLanguageChoice
import io.github.ioannes78.voica.SpeakerCountChoice
import io.github.ioannes78.voica.SpeechPerformanceProfile
import io.github.ioannes78.voica.Stage13AOfflineModelIds
import io.github.ioannes78.voica.Stage13ARealtimeModelIds
import io.github.ioannes78.voica.Stage13ASpeakerEmbeddingModelIds
import io.github.ioannes78.voica.resolvePerformance
import io.github.ioannes78.voica.model.ModelAvailability
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelState
import java.util.Locale

@Composable
internal fun LocalSpeechSettingsCard(
    store: LocalSpeechSettingsStore,
    modelManager: ModelManager,
) {
    val settings by store.settings.collectAsState()
    val operations by modelManager.operations.collectAsState()
    var availability by remember { mutableStateOf<Map<String, ModelAvailability?>>(emptyMap()) }
    var supportedParameters by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }

    var offlineExpanded by rememberSaveable { mutableStateOf(true) }
    var realtimeExpanded by rememberSaveable { mutableStateOf(false) }
    var speakerExpanded by rememberSaveable { mutableStateOf(false) }
    var speakerAdvancedExpanded by rememberSaveable { mutableStateOf(false) }
    var performanceExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(operations) {
        val ids =
            (
                Stage13AOfflineModelIds.ALL +
                    Stage13ARealtimeModelIds.ALL +
                    listOf(Stage13ASpeakerEmbeddingModelIds.CAMP_PLUS)
            ).distinct()
        availability = ids.associateWith { modelManager.availability(it) }
        supportedParameters =
            runCatching {
                modelManager.catalog().models.associate { descriptor ->
                    descriptor.modelId to descriptor.capabilities.supportedParameters
                }
            }.getOrDefault(emptyMap())
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeechSection(
            title = "离线转写",
            summary = offlineSummary(settings.offlineAsrQuality),
            expanded = offlineExpanded,
            onToggle = { offlineExpanded = !offlineExpanded },
        ) {
            Text(
                "录音文件只使用这里选择的离线模型。SenseVoice 速度优先，Qwen3-ASR 质量优先。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OfflineOption(
                title = "快速 · SenseVoice",
                subtitle = "默认 · 多语言 · 资源占用较低",
                selected =
                    settings.offlineAsrQuality == OfflineAsrQualityChoice.BALANCED ||
                        settings.offlineAsrQuality == OfflineAsrQualityChoice.AUTO,
                status = availabilityStatus(availability[Stage13AOfflineModelIds.SENSEVOICE]),
                onClick = { store.setOfflineAsrQuality(OfflineAsrQualityChoice.BALANCED) },
            )
            HorizontalDivider()
            OfflineOption(
                title = "高质量 · Qwen3-ASR",
                subtitle = "0.6B INT8 · 更高质量，内存和计算开销更高",
                selected =
                    settings.offlineAsrQuality == OfflineAsrQualityChoice.HIGH_QUALITY ||
                        settings.offlineAsrQuality == OfflineAsrQualityChoice.ULTRA,
                status = availabilityStatus(availability[Stage13AOfflineModelIds.QWEN3_ASR]),
                onClick = { store.setOfflineAsrQuality(OfflineAsrQualityChoice.HIGH_QUALITY) },
            )

            if (
                settings.offlineAsrQuality == OfflineAsrQualityChoice.BALANCED ||
                settings.offlineAsrQuality == OfflineAsrQualityChoice.AUTO
            ) {
                SenseVoiceAdvanced(
                    store = store,
                    language = settings.senseVoice.language,
                    itn = settings.senseVoice.useInverseTextNormalization,
                    parameters = supportedParameters[Stage13AOfflineModelIds.SENSEVOICE].orEmpty(),
                )
            } else {
                QwenAdvanced(
                    store = store,
                    maxTotalLen = settings.qwen.maxTotalLen,
                    maxNewTokens = settings.qwen.maxNewTokens,
                    temperature = settings.qwen.temperature,
                    topP = settings.qwen.topP,
                    seed = settings.qwen.seed,
                    hotwords = settings.qwen.hotwords,
                    parameters = supportedParameters[Stage13AOfflineModelIds.QWEN3_ASR].orEmpty(),
                )
            }
        }

        SpeechSection(
            title = "实时转写",
            summary = realtimeSummary(settings.realtimeAsrModel),
            expanded = realtimeExpanded,
            onToggle = { realtimeExpanded = !realtimeExpanded },
        ) {
            Text(
                "用于后续边录边转写，不影响当前录音文件的离线转写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            RealtimeOption(
                title = "轻量 · Small Bilingual",
                subtitle = "默认 · 中英双语 · 资源占用较低",
                selected = settings.realtimeAsrModel != RealtimeAsrModelChoice.CHINESE_LARGE_CTC,
                status = availabilityStatus(availability[Stage13ARealtimeModelIds.SMALL_BILINGUAL]),
                onClick = { store.setRealtimeAsrModel(RealtimeAsrModelChoice.SMALL_BILINGUAL) },
            )
            HorizontalDivider()
            RealtimeOption(
                title = "高质量 · Large CTC",
                subtitle = "中文实时高质量模型",
                selected = settings.realtimeAsrModel == RealtimeAsrModelChoice.CHINESE_LARGE_CTC,
                status = availabilityStatus(availability[Stage13ARealtimeModelIds.CHINESE_LARGE_CTC]),
                onClick = { store.setRealtimeAsrModel(RealtimeAsrModelChoice.CHINESE_LARGE_CTC) },
            )
        }

        SpeechSection(
            title = "说话人分离",
            summary = speakerSummary(settings.speakerCount),
            expanded = speakerExpanded,
            onToggle = { speakerExpanded = !speakerExpanded },
        ) {
            Text(
                "使用 Pyannote Segmentation 3.0 + CAM++。普通使用无需选择说话人模型。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                "CAM++：" + availabilityStatus(availability[Stage13ASpeakerEmbeddingModelIds.CAMP_PLUS]),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ListItem(
                headlineContent = { Text("转写后自动说话人分离") },
                supportingContent = { Text("关闭后仍可在录音详情中手动执行说话人分离。") },
                trailingContent = {
                    Switch(
                        checked = settings.diarization.autoAfterTranscription,
                        onCheckedChange = { enabled ->
                            store.setDiarizationSettings(
                                settings.diarization.copy(autoAfterTranscription = enabled),
                            )
                        },
                    )
                },
            )
            HorizontalDivider()
            Text(
                "说话人数",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
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
                            .clickable { store.setSpeakerCount(option.first) },
                )
            }
            HorizontalDivider()
            TextButton(
                onClick = { speakerAdvancedExpanded = !speakerAdvancedExpanded },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(if (speakerAdvancedExpanded) "收起高级设置" else "高级设置")
            }
            if (speakerAdvancedExpanded) {
                ParameterStepper(
                    title = "自动聚类阈值",
                    value = formatFloat(settings.diarization.clusteringThreshold),
                    hint = "仅自动估算人数时使用",
                    onDecrease = {
                        store.setDiarizationSettings(
                            settings.diarization.copy(
                                clusteringThreshold =
                                    (settings.diarization.clusteringThreshold - 0.05F)
                                        .coerceAtLeast(0.05F),
                            ),
                        )
                    },
                    onIncrease = {
                        store.setDiarizationSettings(
                            settings.diarization.copy(
                                clusteringThreshold =
                                    (settings.diarization.clusteringThreshold + 0.05F)
                                        .coerceAtMost(0.95F),
                            ),
                        )
                    },
                )
                ParameterStepper(
                    title = "跨分块相似度阈值",
                    value = formatFloat(settings.diarization.stitchingCosineThreshold),
                    hint = "值越高越严格；过高可能增加说话人碎片",
                    onDecrease = {
                        store.setDiarizationSettings(
                            settings.diarization.copy(
                                stitchingCosineThreshold =
                                    (settings.diarization.stitchingCosineThreshold - 0.05F)
                                        .coerceAtLeast(0.05F),
                            ),
                        )
                    },
                    onIncrease = {
                        store.setDiarizationSettings(
                            settings.diarization.copy(
                                stitchingCosineThreshold =
                                    (settings.diarization.stitchingCosineThreshold + 0.05F)
                                        .coerceAtMost(0.95F),
                            ),
                        )
                    },
                )
                TextButton(
                    onClick = store::resetDiarizationSettings,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text("恢复说话人推荐值")
                }
            }
        }

        SpeechSection(
            title = "性能与语音检测",
            summary = performanceSummary(settings.performanceProfile, settings.resolvePerformance().effectiveThreads),
            expanded = performanceExpanded,
            onToggle = { performanceExpanded = !performanceExpanded },
        ) {
            Text(
                "线程数会应用于本地 ASR、VAD、标点和说话人分离。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                "性能模式",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
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
                            .clickable { store.setPerformanceProfile(option.first) },
                )
            }
            HorizontalDivider()
            val maxThreads =
                minOf(Runtime.getRuntime().availableProcessors().coerceAtLeast(1), MAX_CONFIGURABLE_THREADS)
            ParameterStepper(
                title = "CPU 线程数",
                value = settings.requestedThreads?.toString() ?: "自动",
                hint = "当前有效 ${settings.resolvePerformance().effectiveThreads} 线程 · 设备最多 $maxThreads",
                onDecrease = {
                    val current = settings.requestedThreads ?: settings.resolvePerformance().effectiveThreads
                    val next = (current - 1).coerceAtLeast(1)
                    store.setRequestedThreads(next)
                },
                onIncrease = {
                    val current = settings.requestedThreads ?: settings.resolvePerformance().effectiveThreads
                    val next = (current + 1).coerceAtMost(maxThreads)
                    store.setRequestedThreads(next)
                },
            )
            TextButton(
                onClick = { store.setRequestedThreads(null) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("线程数恢复自动")
            }
            HorizontalDivider()
            Text(
                "语音检测（Silero VAD）",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            ParameterStepper(
                title = "VAD threshold",
                value = formatFloat(settings.vad.threshold),
                hint = "语音判定阈值",
                onDecrease = {
                    store.setVadSettings(
                        settings.vad.copy(threshold = (settings.vad.threshold - 0.05F).coerceAtLeast(0.05F)),
                    )
                },
                onIncrease = {
                    store.setVadSettings(
                        settings.vad.copy(threshold = (settings.vad.threshold + 0.05F).coerceAtMost(0.95F)),
                    )
                },
            )
            ParameterStepper(
                title = "min silence",
                value = formatSeconds(settings.vad.minSilenceDurationSeconds),
                hint = "最短静音时长",
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
            ParameterStepper(
                title = "min speech",
                value = formatSeconds(settings.vad.minSpeechDurationSeconds),
                hint = "最短语音时长",
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
            ParameterStepper(
                title = "max speech",
                value = formatSeconds(settings.vad.maxSpeechDurationSeconds),
                hint = "单段语音最大时长",
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
}

@Composable
private fun SpeechSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = {
                    Text(
                        if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            )
            if (expanded) {
                HorizontalDivider()
                content()
            }
        }
    }
}

@Composable
private fun OfflineOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    status: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(subtitle)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun RealtimeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    status: String,
    onClick: () -> Unit,
) = OfflineOption(title, subtitle, selected, status, onClick)

@Composable
private fun SenseVoiceAdvanced(
    store: LocalSpeechSettingsStore,
    language: SenseVoiceLanguageChoice,
    itn: Boolean,
    parameters: Set<String>,
) {
    HorizontalDivider()
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            "SenseVoice 高级设置",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (parameters.isEmpty() || "language" in parameters) {
            senseVoiceLanguageOptions().forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider()
                ListItem(
                    headlineContent = { Text(option.second) },
                    supportingContent = { Text(option.third) },
                    leadingContent = { RadioButton(selected = language == option.first, onClick = null) },
                    modifier =
                        Modifier.fillMaxWidth().clickable {
                            val current = store.settings.value.senseVoice
                            store.setSenseVoiceSettings(current.copy(language = option.first))
                        },
                )
            }
        }
        if (parameters.isEmpty() || "useInverseTextNormalization" in parameters) {
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("ITN") },
                supportingContent = { Text("数字、日期等口语形式规范化；开启时使用 SenseVoice 原生文本处理。") },
                trailingContent = {
                    Switch(
                        checked = itn,
                        onCheckedChange = { enabled ->
                            val current = store.settings.value.senseVoice
                            store.setSenseVoiceSettings(current.copy(useInverseTextNormalization = enabled))
                        },
                    )
                },
            )
        }
        TextButton(onClick = store::resetSenseVoiceSettings, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("恢复 SenseVoice 推荐值")
        }
    }
}

@Composable
private fun QwenAdvanced(
    store: LocalSpeechSettingsStore,
    maxTotalLen: Int,
    maxNewTokens: Int,
    temperature: Float,
    topP: Float,
    seed: Int,
    hotwords: String,
    parameters: Set<String>,
) {
    HorizontalDivider()
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            "Qwen3-ASR 高级设置",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        fun supported(name: String) = parameters.isEmpty() || name in parameters
        if (supported("maxTotalLen")) {
            ParameterStepper(
                title = "maxTotalLen",
                value = maxTotalLen.toString(),
                hint = "最大上下文长度",
                onDecrease = {
                    val current = store.settings.value.qwen
                    store.setQwenSettings(
                        current.copy(maxTotalLen = (current.maxTotalLen - 128).coerceAtLeast(maxOf(128, current.maxNewTokens))),
                    )
                },
                onIncrease = {
                    val current = store.settings.value.qwen
                    store.setQwenSettings(current.copy(maxTotalLen = (current.maxTotalLen + 128).coerceAtMost(2048)))
                },
            )
        }
        if (supported("maxNewTokens")) {
            ParameterStepper(
                title = "maxNewTokens",
                value = maxNewTokens.toString(),
                hint = "最大输出 Token",
                onDecrease = {
                    val current = store.settings.value.qwen
                    store.setQwenSettings(current.copy(maxNewTokens = (current.maxNewTokens - 16).coerceAtLeast(16)))
                },
                onIncrease = {
                    val current = store.settings.value.qwen
                    store.setQwenSettings(
                        current.copy(maxNewTokens = (current.maxNewTokens + 16).coerceAtMost(minOf(512, current.maxTotalLen))),
                    )
                },
            )
        }
        if (supported("temperature")) {
            ParameterStepper(
                title = "temperature",
                value = formatFloat(temperature),
                hint = "生成随机性",
                onDecrease = {
                    val current = store.settings.value.qwen
                    store.setQwenSettings(current.copy(temperature = (current.temperature - 0.05F).coerceAtLeast(0F)))
                },
                onIncrease = {
                    val current = store.settings.value.qwen
                    store.setQwenSettings(current.copy(temperature = (current.temperature + 0.05F).coerceAtMost(2F)))
                },
            )
        }
        if (supported("topP")) {
            ParameterStepper(
                title = "topP",
                value = formatFloat(topP),
                hint = "候选概率范围",
                onDecrease = {
                    val current = store.settings.value.qwen
                    store.setQwenSettings(current.copy(topP = (current.topP - 0.05F).coerceAtLeast(0.05F)))
                },
                onIncrease = {
                    val current = store.settings.value.qwen
                    store.setQwenSettings(current.copy(topP = (current.topP + 0.05F).coerceAtMost(1F)))
                },
            )
        }
        if (supported("seed")) {
            ParameterStepper(
                title = "seed",
                value = seed.toString(),
                hint = "随机种子",
                onDecrease = {
                    val current = store.settings.value.qwen
                    store.setQwenSettings(current.copy(seed = current.seed - 1))
                },
                onIncrease = {
                    val current = store.settings.value.qwen
                    store.setQwenSettings(current.copy(seed = current.seed + 1))
                },
            )
        }
        if (supported("hotwords")) {
            OutlinedTextField(
                value = hotwords,
                onValueChange = { value ->
                    if (value.length <= 512) {
                        store.setQwenSettings(store.settings.value.qwen.copy(hotwords = value))
                    }
                },
                label = { Text("hotwords") },
                supportingText = { Text("可选热词，最多 512 个字符") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        TextButton(onClick = store::resetQwenSettings, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("恢复 Qwen3-ASR 推荐值")
        }
    }
}

@Composable
private fun ParameterStepper(
    title: String,
    value: String,
    hint: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(hint) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = onDecrease) { Text("−") }
                Text(value, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = onIncrease) { Text("+") }
            }
        },
    )
}

private fun offlineSummary(choice: OfflineAsrQualityChoice): String =
    when (choice) {
        OfflineAsrQualityChoice.HIGH_QUALITY,
        OfflineAsrQualityChoice.ULTRA,
        -> "高质量 · Qwen3-ASR"
        else -> "快速 · SenseVoice"
    }

private fun realtimeSummary(choice: RealtimeAsrModelChoice): String =
    if (choice == RealtimeAsrModelChoice.CHINESE_LARGE_CTC) {
        "高质量 · Large CTC"
    } else {
        "轻量 · Small Bilingual"
    }

private fun speakerSummary(choice: SpeakerCountChoice): String =
    "CAM++ · " +
        when (choice) {
            SpeakerCountChoice.AUTO -> "自动人数"
            SpeakerCountChoice.ONE -> "1 人"
            SpeakerCountChoice.TWO -> "2 人"
            SpeakerCountChoice.THREE -> "3 人"
            SpeakerCountChoice.FOUR -> "4 人"
            SpeakerCountChoice.FIVE_PLUS -> "5+ 人"
        }

private fun performanceSummary(profile: SpeechPerformanceProfile, threads: Int): String =
    when (profile) {
        SpeechPerformanceProfile.AUTO -> "自动 · $threads 线程"
        SpeechPerformanceProfile.POWER_SAVER -> "省电 · $threads 线程"
        SpeechPerformanceProfile.BALANCED -> "均衡 · $threads 线程"
        SpeechPerformanceProfile.PERFORMANCE -> "性能 · $threads 线程"
    }

private fun availabilityStatus(availability: ModelAvailability?): String =
    when {
        availability == null -> "当前模型清单中不可用"
        availability.activeVersion != null -> "已启用 · ${availability.activeVersion}"
        availability.state == ModelState.DOWNLOADING -> "下载中"
        availability.state == ModelState.VERIFYING -> "验证中"
        availability.state == ModelState.INCOMPATIBLE -> "当前设备不兼容"
        availability.installedVersion != null -> "已下载 · 待验证启用"
        else -> "未下载"
    }

private fun senseVoiceLanguageOptions() =
    listOf(
        Triple(SenseVoiceLanguageChoice.AUTO, "自动", "自动判断语言"),
        Triple(SenseVoiceLanguageChoice.ZH, "中文", "强制按中文识别"),
        Triple(SenseVoiceLanguageChoice.EN, "英语", "强制按英语识别"),
        Triple(SenseVoiceLanguageChoice.JA, "日语", "强制按日语识别"),
        Triple(SenseVoiceLanguageChoice.KO, "韩语", "强制按韩语识别"),
        Triple(SenseVoiceLanguageChoice.YUE, "粤语", "强制按粤语识别"),
    )

private fun speakerCountOptions() =
    listOf(
        Triple(SpeakerCountChoice.AUTO, "自动", "自动估算说话人数"),
        Triple(SpeakerCountChoice.ONE, "1 人", "已知录音只有 1 位说话人"),
        Triple(SpeakerCountChoice.TWO, "2 人", "固定为 2 位说话人"),
        Triple(SpeakerCountChoice.THREE, "3 人", "固定为 3 位说话人"),
        Triple(SpeakerCountChoice.FOUR, "4 人", "固定为 4 位说话人"),
        Triple(SpeakerCountChoice.FIVE_PLUS, "5+ 人", "至少 5 人，允许自动增长"),
    )

private fun performanceOptions() =
    listOf(
        Triple(SpeechPerformanceProfile.AUTO, "自动", "按设备能力使用推荐配置"),
        Triple(SpeechPerformanceProfile.POWER_SAVER, "省电", "降低 CPU 占用"),
        Triple(SpeechPerformanceProfile.BALANCED, "均衡", "速度与资源占用平衡"),
        Triple(SpeechPerformanceProfile.PERFORMANCE, "性能", "提高并行度与处理速度"),
    )

private fun formatFloat(value: Float): String = String.format(Locale.US, "%.2f", value)

private fun formatSeconds(value: Float): String = String.format(Locale.US, "%.2f s", value)
