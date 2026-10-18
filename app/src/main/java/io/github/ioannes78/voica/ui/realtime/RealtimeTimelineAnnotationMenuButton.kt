package io.github.ioannes78.voica.ui.realtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ioannes78.voica.RealtimeAnnotationCaptureSource
import io.github.ioannes78.voica.RealtimeTimelineAnnotationCaptureResolver
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationComposerButton
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel

@Composable
internal fun RealtimeTimelineAnnotationMenuButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    source: RealtimeAnnotationCaptureSource,
    resolver: RealtimeTimelineAnnotationCaptureResolver,
    viewModel: TimelineAnnotationViewModel,
) {
    TimelineAnnotationComposerButton(
        modifier = modifier,
        enabled = enabled,
        viewModel = viewModel,
        onCreate = { noteText, onCreated ->
            viewModel.createAtRealtime(
                targetProvider = { resolver.resolve(source) },
                noteText = noteText,
                onCreated = onCreated,
            )
        },
    )
}
