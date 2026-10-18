package io.github.ioannes78.voica.ui.annotation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.TimelineAnnotationCameraCaptureTarget
import io.github.ioannes78.voica.database.TimelineAnnotationEntity

private data class PendingAnnotationCamera(
    val target: TimelineAnnotationCameraCaptureTarget,
    val deleteAnnotationIfCancelled: Boolean,
)

private data class PendingAnnotationGallery(
    val annotationId: String,
    val deleteAnnotationIfCancelled: Boolean,
)

/** Shared Stage 16A.2 composer: every + surface exposes the same four annotation actions. */
@Composable
internal fun TimelineAnnotationComposerButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    label: String = "＋",
    compact: Boolean = false,
    viewModel: TimelineAnnotationViewModel,
    onCreate: (
        noteText: String?,
        onCreated: (TimelineAnnotationEntity) -> Unit,
    ) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var pendingCamera by remember { mutableStateOf<PendingAnnotationCamera?>(null) }
    var pendingGallery by remember { mutableStateOf<PendingAnnotationGallery?>(null) }

    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { accepted ->
            val pending = pendingCamera ?: return@rememberLauncherForActivityResult
            pendingCamera = null
            viewModel.finishCamera(
                target = pending.target,
                accepted = accepted,
                deleteAnnotationIfCancelled = pending.deleteAnnotationIfCancelled,
            )
        }
    val galleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val pending = pendingGallery ?: return@rememberLauncherForActivityResult
            pendingGallery = null
            if (uri != null) {
                viewModel.importGallery(pending.annotationId, uri)
            } else if (pending.deleteAnnotationIfCancelled) {
                viewModel.deleteAnnotation(pending.annotationId, silent = true)
            }
        }

    fun openCamera(annotationId: String) {
        viewModel.prepareCamera(annotationId) { target ->
            pendingCamera = PendingAnnotationCamera(target, deleteAnnotationIfCancelled = true)
            cameraLauncher.launch(target.uri)
        }
    }

    fun openGallery(annotationId: String) {
        pendingGallery = PendingAnnotationGallery(annotationId, deleteAnnotationIfCancelled = true)
        galleryLauncher.launch("image/*")
    }

    Box(modifier = modifier) {
        if (compact) {
            TextButton(
                onClick = { menuOpen = true },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text(label)
            }
        } else {
            OutlinedButton(
                onClick = { menuOpen = true },
                enabled = enabled,
            ) {
                Text(label)
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("快速标记") },
                onClick = {
                    menuOpen = false
                    onCreate(null) {}
                },
            )
            DropdownMenuItem(
                text = { Text("文字笔记") },
                onClick = {
                    menuOpen = false
                    noteText = ""
                    noteOpen = true
                },
            )
            DropdownMenuItem(
                text = { Text("拍照") },
                onClick = {
                    menuOpen = false
                    onCreate(null) { created -> openCamera(created.id) }
                },
            )
            DropdownMenuItem(
                text = { Text("从相册添加") },
                onClick = {
                    menuOpen = false
                    onCreate(null) { created -> openGallery(created.id) }
                },
            )
        }
    }

    if (noteOpen) {
        AlertDialog(
            onDismissRequest = { noteOpen = false },
            title = { Text("文字笔记") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("记录此刻") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = noteText.isNotBlank(),
                    onClick = {
                        val value = noteText
                        noteOpen = false
                        onCreate(value) {}
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { noteOpen = false }) { Text("取消") }
            },
        )
    }
}
