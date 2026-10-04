package io.github.ioannes78.voica.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import io.github.ioannes78.voica.RealtimeAsrModelChoice
import io.github.ioannes78.voica.Stage13ARealtimeModelIds
import io.github.ioannes78.voica.model.ModelAvailability
import io.github.ioannes78.voica.model.ModelManager

private data class RealtimeModelOption(
    val choice: RealtimeAsrModelChoice,
    val title: String,
    val subtitle: String,
    val modelId: String?,
)

@Composable
internal fun LocalSpeechSettingsCard(
    store: LocalSpeechSettingsStore,
    modelManager: ModelManager,
) {
    val settings by store.settings.collectAsState()
    val operations by modelManager.operations.collectAsState()
    var availability by remember {
        mutableStateOf<Map<String, ModelAvailability?>>(emptyMap())
    }

    LaunchedEffect(operations) {
        availability =
            Stage13ARealtimeModelIds.ALL.associateWith { modelId ->
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
    }
}

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
