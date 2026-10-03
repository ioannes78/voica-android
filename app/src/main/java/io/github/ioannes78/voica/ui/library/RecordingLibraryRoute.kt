package io.github.ioannes78.voica.ui.library

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

@Composable
fun RecordingLibraryRoute(
    padding: PaddingValues,
    viewModel: RecordingLibraryViewModel,
    onOpenRecording: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importAudio)
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
