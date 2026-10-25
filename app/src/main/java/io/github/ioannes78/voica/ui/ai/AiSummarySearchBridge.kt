package io.github.ioannes78.voica.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ioannes78.voica.OfflineSummaryRunPhase
import io.github.ioannes78.voica.OfflineSummaryRunState
import io.github.ioannes78.voica.VoicaApplication
import io.github.ioannes78.voica.database.SearchDocumentEntity

internal val LocalAiSummarySearchTarget = compositionLocalOf<SearchDocumentEntity?> { null }

@Composable
fun AiSummaryProductCard(
    transcriptionId: String,
    recordingName: String,
    viewModel: AiSummaryViewModel,
    contentViewModel: AiSummaryContentViewModel,
    onOpenSettings: () -> Unit,
    onSeekEvidence: (Long) -> Unit,
    showTransientHeader: Boolean = true,
    searchTarget: SearchDocumentEntity?,
    dismissedStaleSummaryFingerprint: String? = null,
    onIgnoreStale: (String) -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as VoicaApplication
    val offlineSummaryViewModel: OfflineSummaryProductViewModel =
        viewModel(
            factory =
                remember(
                    application.container.offlineSummaryCoordinator,
                    application.container.modelManager,
                ) {
                    OfflineSummaryProductViewModel.Factory(
                        coordinator = application.container.offlineSummaryCoordinator,
                        modelManager = application.container.modelManager,
                    )
                },
        )
    val selected by viewModel.selected.collectAsState()
    val history by viewModel.history.collectAsState()
    val offlineState by offlineSummaryViewModel.state.collectAsState()

    LaunchedEffect(transcriptionId) {
        viewModel.bind(transcriptionId)
        application.container.aiSummaryRepository
            .recordingIdForTranscription(transcriptionId)
            ?.let(offlineSummaryViewModel::bind)
    }

    val hasCompletedSummary = selected != null || history.isNotEmpty()
    val active = offlineState.run.isActiveOfflineSummaryGeneration()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!hasCompletedSummary || active || offlineState.shouldShowCompletedSummaryNotice()) {
            OfflineSummaryGenerationPanel(
                hasCompletedSummary = hasCompletedSummary,
                state = offlineState,
                onGenerate = offlineSummaryViewModel::generate,
                onCancel = offlineSummaryViewModel::cancel,
                onOpenSettings = onOpenSettings,
            )
        }

        if (hasCompletedSummary) {
            CompositionLocalProvider(LocalAiSummarySearchTarget provides searchTarget) {
                AiSummaryProductCard(
                    transcriptionId = transcriptionId,
                    recordingName = recordingName,
                    viewModel = viewModel,
                    contentViewModel = contentViewModel,
                    onSeekEvidence = onSeekEvidence,
                    showTransientHeader = showTransientHeader,
                    dismissedStaleSummaryFingerprint = dismissedStaleSummaryFingerprint,
                    onIgnoreStale = onIgnoreStale,
                    onRegenerate = offlineSummaryViewModel::generate,
                    regenerateEnabled = offlineState.modelReady == true && !active,
                )
            }
        }
    }
}

@Composable
private fun OfflineSummaryGenerationPanel(
    hasCompletedSummary: Boolean,
    state: OfflineSummaryProductUiState,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val run = state.run
    val active = run.isActiveOfflineSummaryGeneration()

    if (active) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        when (run?.phase) {
                            OfflineSummaryRunPhase.PREPARING -> "正在准备"
                            OfflineSummaryRunPhase.ANALYZING -> "正在分析"
                            OfflineSummaryRunPhase.VALIDATING -> "正在校验"
                            else -> "正在生成离线总结"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "内容仅在本机处理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onCancel) {
                    Text("取消")
                }
            }
        }
        return
    }

    if (hasCompletedSummary) {
        val message =
            when (run?.phase) {
                OfflineSummaryRunPhase.FAILED ->
                    run.message?.let { "重新生成失败：$it" } ?: "离线总结重新生成失败"

                else -> state.notice
            }
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (run?.phase == OfflineSummaryRunPhase.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.modelReady == false -> {
                    Text(
                        "需要安装离线 AI 模型",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "离线总结使用 Gemma 4 E2B，模型安装后内容仍只在本机处理。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenSettings) {
                        Text("前往本地模型")
                    }
                }

                state.modelReady == null -> {
                    Text(
                        "正在检查离线 AI 模型…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        "从当前转写生成离线总结",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "内容仅在本机处理",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        enabled = state.modelReady == true,
                        onClick = onGenerate,
                    ) {
                        Text("生成总结")
                    }
                }
            }

            when (run?.phase) {
                OfflineSummaryRunPhase.FAILED ->
                    Text(
                        run.message?.let { "生成失败：$it" } ?: "离线总结生成失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                OfflineSummaryRunPhase.CANCELLED ->
                    Text(
                        "已取消。可以重新生成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                else -> Unit
            }

            state.notice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun OfflineSummaryRunState?.isActiveOfflineSummaryGeneration(): Boolean =
    this?.phase == OfflineSummaryRunPhase.PREPARING ||
        this?.phase == OfflineSummaryRunPhase.ANALYZING ||
        this?.phase == OfflineSummaryRunPhase.VALIDATING

private fun OfflineSummaryProductUiState.shouldShowCompletedSummaryNotice(): Boolean =
    run?.phase == OfflineSummaryRunPhase.FAILED || notice != null
