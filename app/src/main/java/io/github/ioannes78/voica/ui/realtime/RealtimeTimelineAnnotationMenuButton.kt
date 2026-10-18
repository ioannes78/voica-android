package io.github.ioannes78.voica.ui.realtime

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import io.github.ioannes78.voica.RealtimeAnnotationCaptureSource
import io.github.ioannes78.voica.RealtimeTimelineAnnotationCaptureResolver
import io.github.ioannes78.voica.TimelineAnnotationCameraCaptureTarget
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel

private data class RealtimePendingCamera(
    val target: TimelineAnnotationCameraCaptureTarget,
    val deleteAnnotationIfCancelled: Boolean,
)

private data class RealtimePendingGallery(
    val annotationId: String,
    val deleteAnnotationIfCancelled: Boolean,
)

@Composable
internal fun RealtimeTimelineAnnotationMenuButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    source: RealtimeAnnotationCaptureSource,
    resolver: RealtimeTimelineAnnotationCaptureResolver,
    viewModel: TimelineAnnotationViewModel,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var pendingCamera by remember { mutableStateOf<RealtimePendingCamera?>(null) }
    var pendingGallery by remember { mutableStateOf<RealtimePendingGallery?>(null) }

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

    fun resolveTarget() = resolver.resolve(source)

    fun openCamera(annotationId: String) {
        viewModel.prepareCamera(annotationId) { target ->
            pendingCamera =
                RealtimePendingCamera(
                    target = target,
                    deleteAnnotationIfCancelled = true,
                )
            cameraLauncher.launch(target.uri)
        }
    }

    fun openGallery(annotationId: String) {
        pendingGallery =
            RealtimePendingGallery(
                annotationId = annotationId,
                deleteAnnotationIfCancelled = true,
            )
        galleryLauncher.launch("image/*")
    }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { menuOpen = true },
            enabled = enabled,
        ) {
            Text("＋")
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("快速标记") },
                onClick = {
                    menuOpen = false
                    viewModel.createAtRealtime(::resolveTarget)
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
                    viewModel.createAtRealtime(::resolveTarget) { created ->
                        openCamera(created.id)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text("从相册添加") },
                onClick = {
                    menuOpen = false
                    viewModel.createAtRealtime(::resolveTarget) { created ->
                        openGallery(created.id)
                    }
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
                        viewModel.createAtRealtime(
                            targetProvider = ::resolveTarget,
                            noteText = value,
                        )
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { noteOpen = false }) { Text("取消") }
            },
        )
    }
}
