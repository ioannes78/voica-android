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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ioannes78.voica.OfflineSummaryRunPhase
import io.github.ioannes78.voica.OfflineSummaryRunState
import io.github.ioannes78.voica.VoicaApplication
import io.github.ioannes78.voica.database.RecordingCandidateAttentionEntity
import io.github.ioannes78.voica.database.SearchDocumentEntity
import kotlinx.coroutines.launch

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
    dismissedAiSummaryCandidateId: String? = null,
    onIgnoreCandidate: (String) -> Unit = {},
    dismissedStaleSummaryFingerprint: String? = null,
    onIgnoreStale: (String) -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as VoicaApplication
    val scope = rememberCoroutineScope()
    val aiSummaryRepository = application.container.aiSummaryRepository
    val offlineSummaryViewModel: OfflineSummaryProductViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(
            factory =
                remember(
                    application.container.offlineSummaryCoordinator,
                    application.container.modelManager,
                    aiSummaryRepository,
                ) {
                    OfflineSummaryProductViewModel.Factory(
                        coordinator = application.container.offlineSummaryCoordinator,
                        modelManager = application.container.modelManager,
                        aiSummaryRepository = aiSummaryRepository,
                    )
                },
        )
    val selected by viewModel.selected.collectAsState()
    val history by viewModel.history.collectAsState()
    val offlineState by offlineSummaryViewModel.state.collectAsState()
    val recordingId = selected?.entity?.recordingId ?: history.firstOrNull()?.recordingId
    val candidateAttention by
        produceState<RecordingCandidateAttentionEntity?>(
            initialValue = null,
            recordingId,
        ) {
            if (recordingId == null) {
                value = null
            } else {
                application.container.stage13B5Qa4Repository
                    .observeCandidateAttention(recordingId)
                    .collect { value = it }
            }
        }

    LaunchedEffect(transcriptionId) {
        viewModel.bind(transcriptionId)
        aiSummaryRepository
            .recordingIdForTranscription(transcriptionId)
            ?.let(offlineSummaryViewModel::bind)
    }

    val hasCompletedSummary = selected != null || history.isNotEmpty()
    val active = offlineState.run.isActiveOfflineSummaryGeneration()
    val effectiveDismissedCandidateId =
        dismissedAiSummaryCandidateId ?: candidateAttention?.dismissedAiSummaryCandidateId

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (
            !hasCompletedSummary ||
            active ||
            offlineState.resumableSummaryId != null ||
            offlineState.shouldShowCompletedSummaryNotice()
        ) {
            OfflineSummaryGenerationPanel(
                hasCompletedSummary = hasCompletedSummary,
                state = offlineState,
                onGenerate = offlineSummaryViewModel::generate,
                onResume = offlineSummaryViewModel::resume,
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
                    dismissedAiSummaryCandidateId = effectiveDismissedCandidateId,
                    onIgnoreCandidate = { candidateId ->
                        val id = recordingId
                        if (id != null) {
                            scope.launch {
                                application.container.stage13B5Qa4Repository
                                    .dismissAiSummaryCandidate(id, candidateId)
                            }
                        }
                        onIgnoreCandidate(candidateId)
                    },
                    dismissedStaleSummaryFingerprint = dismissedStaleSummaryFingerprint,
                    onIgnoreStale = onIgnoreStale,
                    onRegenerate = offlineSummaryViewModel::generate,
                    regenerateEnabled =
                        offlineState.modelReady == true &&
                            !active &&
                            offlineState.resumableSummaryId == null,
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
    onResume: () -> Unit,
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
                            OfflineSummaryRunPhase.ANALYZING -> "正在本地分析"
                            OfflineSummaryRunPhase.VALIDATING -> "正在生成最终总结"
                            else -> "正在生成离线总结"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    run?.message?.takeIf { it.isNotBlank() }?.let { progress ->
                        Text(
                            progress,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

    if (state.resumableSummaryId != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("长文本总结可继续", style = MaterialTheme.typography.titleMedium)
                Text(
                    "已验证的分段检查点会保留；继续时只重试未完成或失败的节点。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state.modelReady) {
                    true -> Button(onClick = onResume) { Text("继续") }
                    false -> {
                        Text(
                            "继续前需要恢复 Gemma 4 E2B 本地模型。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onOpenSettings) { Text("前往本地模型") }
                    }
                    null ->
                        Text(
                            "正在检查离线 AI 模型…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                state.notice?.let { notice ->
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
