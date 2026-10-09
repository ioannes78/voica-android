package io.github.ioannes78.voica.ui.library

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.ioannes78.voica.ui.search.SearchReturnRuntime
import kotlinx.coroutines.delay

@Composable
fun RecordingLibraryRoute(
    padding: PaddingValues,
    viewModel: RecordingLibraryViewModel,
    onOpenRecording: (String) -> Unit,
    onOpenUnifiedSearch: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val exportInProgress by viewModel.exportInProgress.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importAudio)
        }
    val exportTreeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(viewModel::exportSelectedToTree)
        }

    LaunchedEffect(Unit) {
        if (SearchReturnRuntime.consumePendingReturn()) {
            onOpenUnifiedSearch()
        }
    }

    // Stage 14.1A R1: the visible library search field is now the primary full-content search
    // launcher. Keep the existing field while the user is typing, then hand the completed query to
    // unified search instead of leaving the user in the legacy recording-only filter surface.
    LaunchedEffect(state.query) {
        val query = state.query.trim()
        if (query.isNotEmpty()) {
            delay(450L)
            if (viewModel.uiState.value.query.trim() == query) {
                SearchReturnRuntime.requestSearchLaunch(query)
                onOpenUnifiedSearch()
                viewModel.setQuery("")
            }
        }
    }

    LaunchedEffect(state.operationMessage) {
        val message = state.operationMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeOperationMessage()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RecordingLibraryScreen(
            padding = padding,
            state = state,
            onImportAudio = {
                importLauncher.launch(
                    arrayOf(
                        "audio/*",
                        "application/ogg",
                        "application/octet-stream",
                    ),
                )
            },
            onCancelImport = viewModel::cancelImport,
            onConfirmDuplicateImport = viewModel::confirmDuplicateImport,
            onDismissDuplicateImport = viewModel::dismissDuplicateImport,
            onQueryChange = viewModel::setQuery,
            onOpenUnifiedSearch = {
                val query = state.query.trim()
                if (query.isNotEmpty()) {
                    SearchReturnRuntime.requestSearchLaunch(query)
                    viewModel.setQuery("")
                }
                onOpenUnifiedSearch()
            },
            onSortChange = viewModel::setSort,
            onFavoriteFilterChange = viewModel::setFavoriteOnly,
            onCompletedTranscriptionFilterChange =
                viewModel::setCompletedTranscriptionOnly,
            onCompletedSummaryFilterChange = viewModel::setCompletedSummaryOnly,
            onFolderFilter = viewModel::setFolderFilter,
            onUncategorizedFilter = viewModel::setUncategorizedOnly,
            onToggleTagFilter = viewModel::toggleTagFilter,
            onToggleSourceType = viewModel::toggleSourceType,
            onClearFilters = viewModel::clearFilters,
            onOpenRecording = onOpenRecording,
            onEnterSelection = viewModel::enterSelection,
            onToggleSelection = viewModel::toggleSelection,
            onClearSelection = viewModel::clearSelection,
            onSelectAll = viewModel::selectAllVisible,
            onToggleFavorite = viewModel::toggleFavorite,
            onSetSelectedFavorite = viewModel::setSelectedFavorite,
            onDeleteSelected = viewModel::deleteSelected,
            exportInProgress = exportInProgress,
            onExportSelected = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    viewModel.exportSelectedToDownloads()
                } else {
                    exportTreeLauncher.launch(null)
                }
            },
            onCancelExport = viewModel::cancelExport,
            onMoveSelectedToFolder = viewModel::moveSelectedToFolder,
            onAddTagToSelected = viewModel::addTagToSelected,
            onRemoveTagFromSelected = viewModel::removeTagFromSelected,
            onCreateFolder = viewModel::createFolder,
            onCreateTag = viewModel::createTag,
            onRenameFolder = viewModel::renameFolder,
            onDeleteFolder = viewModel::deleteFolder,
            onRenameTag = viewModel::renameTag,
            onDeleteTag = viewModel::deleteTag,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
