package io.github.ioannes78.voica.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import io.github.ioannes78.voica.database.AiSummaryEntity
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
    attentionOverride: AiSummaryEntity? = null,
    searchTarget: SearchDocumentEntity?,
    dismissedStaleSummaryFingerprint: String? = null,
    onIgnoreStale: (String) -> Unit = {},
) {
    CompositionLocalProvider(LocalAiSummarySearchTarget provides searchTarget) {
        AiSummaryProductCard(
            transcriptionId = transcriptionId,
            recordingName = recordingName,
            viewModel = viewModel,
            contentViewModel = contentViewModel,
            onOpenSettings = onOpenSettings,
            onSeekEvidence = onSeekEvidence,
            showTransientHeader = showTransientHeader,
            attentionOverride = attentionOverride,
            dismissedStaleSummaryFingerprint = dismissedStaleSummaryFingerprint,
            onIgnoreStale = onIgnoreStale,
        )
    }
}
