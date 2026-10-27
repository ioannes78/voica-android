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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.OfflineSummaryRunPhase
import io.github.ioannes78.voica.OfflineSummaryRunState
import io.github.ioannes78.voica.VoicaApplication
import io.github.ioannes78.voica.database.SearchDocumentEntity

/**
 * Product bridge for the Stage 17 local-summary runtime.
 *
 * The base summary card remains responsible for persisted content/history/edit/export. This wrapper
 * only adds live application-scoped execution controls. It owns no inference or foreground service;
 * leaving the screen therefore never stops generation.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AiSummaryProductCard(
    transcriptionId: String,
    recordingName: String,
    viewModel: AiSummaryViewModel,
    contentViewModel: AiSummaryContentViewModel,
    onOpenSettings: () -> Unit,
    onSeekEvidence: (Long) -> Unit,
    showTransientHeader: Boolean = true,
    searchTarget: SearchDocumentEntity? = null,
    dismissedStaleSummaryFingerprint: String? = null,
    onIgnoreStale: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as VoicaApplication
    val coordinator = application.container.offlineSummaryCoordinator
    val modelManager = application.container.modelManager
    val aiSummaryRepository = application.container.aiSummaryRepository
    val runtimeViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel<OfflineSummaryProductViewModel>(
            key = "offline-summary-runtime:$transcriptionId",
            factory =
                remember(coordinator, modelManager, aiSummaryRepository) {
                    OfflineSummaryProductViewModel.Factory(
                        coordinator = coordinator,
                        modelManager = modelManager,
                        aiSummaryRepository = aiSummaryRepository,
                    )
                },
        )
    val runtimeState by runtimeViewModel.state.collectAsState()
    var resolvedRecordingId by remember(transcriptionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(transcriptionId) {
        val recordingId = aiSummaryRepository.recordingIdForTranscription(transcriptionId)
        resolvedRecordingId = recordingId
        if (recordingId != null) runtimeViewModel.bind(recordingId)
    }

    val run = runtimeState.run
    val active = run?.phase.isActiveOfflineSummaryPhase()
    val canGenerate =
        resolvedRecordingId != null &&
            runtimeState.modelReady == true &&
            !active &&
            runtimeState.resumableSummaryId == null

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (active && run != null) {
            OfflineSummaryRuntimeSurface(
                title = offlineSummaryRuntimeTitle(run),
                detail = run.message,
                actionLabel = "取消",
                onAction = runtimeViewModel::cancel,
            )
        } else if (runtimeState.resumableSummaryId != null) {
            OfflineSummaryRuntimeSurface(
                title = "长文本总结可继续",
                detail = "将从已验证的本地检查点继续，不会重跑已完成分段。",
                actionLabel = "继续",
                onAction = runtimeViewModel::resume,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = canGenerate,
                    onClick = runtimeViewModel::generate,
                ) {
                    Text("生成新总结")
                }
                if (runtimeState.modelReady == false) {
                    Text(
                        "需要 Gemma 4 E2B 本地模型",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onOpenSettings) {
                        Text("模型设置")
                    }
                }
            }
        }

        runtimeState.notice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AiSummaryProductCard(
            transcriptionId = transcriptionId,
            recordingName = recordingName,
            viewModel = viewModel,
            contentViewModel = contentViewModel,
            onSeekEvidence = onSeekEvidence,
            showTransientHeader = showTransientHeader,
            dismissedStaleSummaryFingerprint = dismissedStaleSummaryFingerprint,
            onIgnoreStale = onIgnoreStale,
            onRegenerate = runtimeViewModel::generate,
            regenerateEnabled = canGenerate,
        )
    }
}

@Composable
private fun OfflineSummaryRuntimeSurface(
    title: String,
    detail: String?,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                detail?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

private fun OfflineSummaryRunPhase?.isActiveOfflineSummaryPhase(): Boolean =
    this == OfflineSummaryRunPhase.PREPARING ||
        this == OfflineSummaryRunPhase.ANALYZING ||
        this == OfflineSummaryRunPhase.VALIDATING

private fun offlineSummaryRuntimeTitle(run: OfflineSummaryRunState): String =
    when (run.phase) {
        OfflineSummaryRunPhase.PREPARING -> "正在准备本地总结"
        OfflineSummaryRunPhase.ANALYZING ->
            when {
                run.message?.contains("规划长文本") == true -> "正在规划长文本"
                run.message?.contains("分析分段") == true -> "正在分段分析"
                run.message?.contains("合并第") == true -> "正在层级合并"
                else -> "正在本地分析"
            }
        OfflineSummaryRunPhase.VALIDATING -> "正在生成最终总结"
        OfflineSummaryRunPhase.COMPLETED -> "总结已完成"
        OfflineSummaryRunPhase.FAILED -> "总结失败"
        OfflineSummaryRunPhase.CANCELLED -> "总结已取消"
    }
}
