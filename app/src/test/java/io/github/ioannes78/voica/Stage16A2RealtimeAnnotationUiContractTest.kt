package io.github.ioannes78.voica

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16A2RealtimeAnnotationUiContractTest {
    @Test
    fun realtimeControlsExposeCompactPlusMenuOnlyThroughSafeResolver() {
        val screen = appProductionSource("ui/realtime/RealtimeTranscriptionScreen.kt")
        val menu = appProductionSource("ui/realtime/RealtimeTimelineAnnotationMenuButton.kt")
        val viewModel = appProductionSource("ui/annotation/TimelineAnnotationViewModel.kt")

        assertTrue(screen.contains("RealtimeTimelineAnnotationMenuButton("))
        assertTrue(screen.contains("Stage16ARealtimeUiPhase.ACTIVE"))
        assertTrue(screen.contains("Stage16ARealtimeUiPhase.PAUSED"))
        assertTrue(screen.contains("key = \"realtime-annotation-${'$'}{activitySource.name}\""))
        assertTrue(menu.contains("Text(\"＋\")"))
        assertTrue(menu.contains("Text(\"快速标记\")"))
        assertTrue(menu.contains("Text(\"文字笔记\")"))
        assertTrue(menu.contains("Text(\"拍照\")"))
        assertTrue(menu.contains("Text(\"从相册添加\")"))
        assertTrue(menu.contains("suspend fun resolveTarget() = resolver.resolve(source)"))
        assertTrue(viewModel.contains("fun createAtRealtime("))
        assertTrue(viewModel.contains("当前暂无可安全标注的音频位置"))
        assertFalse(
            "Realtime annotation must never derive an anchor from displayed elapsed wall-clock time",
            menu.contains("elapsedMs") || menu.contains("durationMs"),
        )
    }

    @Test
    fun realtimePhotoInputsReuseDurableAttachmentLifecycleAndCleanCancelledPlaceholder() {
        val menu = appProductionSource("ui/realtime/RealtimeTimelineAnnotationMenuButton.kt")
        val viewModel = appProductionSource("ui/annotation/TimelineAnnotationViewModel.kt")

        assertTrue(menu.contains("ActivityResultContracts.TakePicture()"))
        assertTrue(menu.contains("ActivityResultContracts.GetContent()"))
        assertTrue(menu.contains("viewModel.prepareCamera("))
        assertTrue(menu.contains("viewModel.finishCamera("))
        assertTrue(menu.contains("viewModel.importGallery("))
        assertTrue(menu.contains("deleteAnnotationIfCancelled = true"))
        assertTrue(menu.contains("viewModel.deleteAnnotation(pending.annotationId, silent = true)"))
        assertTrue(viewModel.contains("attachmentStore.commitCameraCapture(target)"))
        assertTrue(viewModel.contains("attachmentStore.importGallery(annotationId, uri)"))
    }

    @Test
    fun realtimeReaderRemainsTextFirstWithoutAnnotationCards() {
        val screen = appProductionSource("ui/realtime/RealtimeTranscriptionScreen.kt")

        assertTrue(screen.contains("SelectionContainer { Text(item.text"))
        assertTrue(screen.contains("state.mutablePartialTail"))
        assertFalse(screen.contains("TimelineAnnotationRow("))
        assertFalse(screen.contains("TimelineAnnotationPanel("))
    }

    private fun appProductionSource(relative: String): String {
        val candidates =
            listOf(
                File("src/main/java/io/github/ioannes78/voica/$relative"),
                File("app/src/main/java/io/github/ioannes78/voica/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("app production source not found: $relative")
    }
}
