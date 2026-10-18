package io.github.ioannes78.voica

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16A2RealtimeAnnotationUiContractTest {
    @Test
    fun realtimeControlsExposeSharedComposerOnlyThroughSafeResolver() {
        val screen = appProductionSource("ui/realtime/RealtimeTranscriptionScreen.kt")
        val menu = appProductionSource("ui/realtime/RealtimeTimelineAnnotationMenuButton.kt")
        val composer = appProductionSource("ui/annotation/TimelineAnnotationComposerButton.kt")
        val viewModel = appProductionSource("ui/annotation/TimelineAnnotationViewModel.kt")

        assertTrue(screen.contains("RealtimeTimelineAnnotationMenuButton("))
        assertTrue(screen.contains("Stage16ARealtimeUiPhase.ACTIVE"))
        assertTrue(screen.contains("Stage16ARealtimeUiPhase.PAUSED"))
        assertTrue(screen.contains("key = \"realtime-annotation-${'$'}{activitySource.name}\""))
        assertTrue(menu.contains("TimelineAnnotationComposerButton("))
        assertTrue(menu.contains("targetProvider = { resolver.resolve(source) }"))
        assertTrue(composer.contains("Text(\"快速标记\")"))
        assertTrue(composer.contains("Text(\"文字笔记\")"))
        assertTrue(composer.contains("Text(\"拍照\")"))
        assertTrue(composer.contains("Text(\"从相册添加\")"))
        assertTrue(viewModel.contains("fun createAtRealtime("))
        assertTrue(viewModel.contains("当前暂无可安全标注的音频位置"))
        assertFalse(
            "Realtime annotation must never derive an anchor from displayed elapsed wall-clock time",
            menu.contains("elapsedMs") || menu.contains("durationMs"),
        )
    }

    @Test
    fun sharedPhotoComposerReusesDurableAttachmentLifecycleAndCleansCancelledPlaceholder() {
        val composer = appProductionSource("ui/annotation/TimelineAnnotationComposerButton.kt")
        val viewModel = appProductionSource("ui/annotation/TimelineAnnotationViewModel.kt")

        assertTrue(composer.contains("ActivityResultContracts.TakePicture()"))
        assertTrue(composer.contains("ActivityResultContracts.GetContent()"))
        assertTrue(composer.contains("viewModel.prepareCamera("))
        assertTrue(composer.contains("viewModel.finishCamera("))
        assertTrue(composer.contains("viewModel.importGallery("))
        assertTrue(composer.contains("deleteAnnotationIfCancelled = true"))
        assertTrue(composer.contains("viewModel.deleteAnnotation(pending.annotationId, silent = true)"))
        assertTrue(viewModel.contains("attachmentStore.commitCameraCapture(target)"))
        assertTrue(viewModel.contains("attachmentStore.importGallery(annotationId, uri)"))
    }

    @Test
    fun realtimeReaderRemainsTextFirstWithOnlyCompactAnnotationFeedback() {
        val screen = appProductionSource("ui/realtime/RealtimeTranscriptionScreen.kt")

        assertTrue(screen.contains("SelectionContainer { Text(item.text"))
        assertTrue(screen.contains("state.mutablePartialTail"))
        assertTrue(screen.contains("RealtimeAnnotationFeedback(annotationState.items)"))
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
