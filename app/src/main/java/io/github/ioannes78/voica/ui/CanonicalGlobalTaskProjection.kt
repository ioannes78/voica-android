package io.github.ioannes78.voica.ui

import io.github.ioannes78.voica.CanonicalAudioCoordinator
import io.github.ioannes78.voica.CanonicalGenerationPhase
import io.github.ioannes78.voica.CanonicalGenerationProgress
import io.github.ioannes78.voica.database.AudioDerivationState
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import io.github.ioannes78.voica.ui.status.AppActivityAction
import io.github.ioannes78.voica.ui.status.AppActivityDestination
import io.github.ioannes78.voica.ui.status.AppActivityFact
import io.github.ioannes78.voica.ui.status.AppActivityKind
import io.github.ioannes78.voica.ui.status.AppActivityLifecycleClass
import io.github.ioannes78.voica.ui.status.AppActivitySource
import kotlin.math.roundToInt

internal fun buildCanonicalActivityFacts(
    progressByRecording: Map<String, CanonicalGenerationProgress>,
    recordings: List<RecordingLibraryItem>,
): List<AppActivityFact> {
    val recordingsById = recordings.associateBy(RecordingLibraryItem::id)
    val activeIds = progressByRecording.keys
    val active =
        progressByRecording.values
            .sortedBy(CanonicalGenerationProgress::startedAtMs)
            .map { progress ->
                val recording = recordingsById[progress.recordingId]
                AppActivityFact(
                    logicalKey = "canonical:${progress.recordingId}",
                    kind = AppActivityKind.CANONICAL_GENERATION,
                    source = AppActivitySource.LOCAL_PROCESSING,
                    lifecycleClass = AppActivityLifecycleClass.BACKGROUND_RUNNING,
                    title = "标准 WAV 生成",
                    subtitle =
                        listOfNotNull(
                            recording?.displayName ?: "录音",
                            canonicalProgressText(progress),
                        ).joinToString(" · "),
                    progressFraction = progress.fraction,
                    recordingId = progress.recordingId,
                    destination =
                        AppActivityDestination.RecordingDetail(
                            recordingId = progress.recordingId,
                            destination = RecordingDetailDestination.PLAYBACK,
                        ),
                    availableActions =
                        buildSet {
                            add(AppActivityAction.OPEN)
                            if (progress.cancellable) add(AppActivityAction.CANCEL)
                        },
                    priorityHint = CANONICAL_PRIORITY,
                    startedAtMs = progress.startedAtMs,
                )
            }
    val failures =
        recordings.mapNotNull { recording ->
            if (recording.id in activeIds) return@mapNotNull null
            val derivation = currentCanonicalFailure(recording) ?: return@mapNotNull null
            val recoverable = derivation.state == AudioDerivationState.FAILED_RECOVERABLE
            AppActivityFact(
                logicalKey =
                    "canonical:${recording.id}:${derivation.sourceSha256}:${derivation.state}:${derivation.errorCode.orEmpty()}",
                kind = AppActivityKind.CANONICAL_GENERATION,
                source = AppActivitySource.LOCAL_PROCESSING,
                lifecycleClass =
                    if (recoverable) {
                        AppActivityLifecycleClass.INTERRUPTED_ATTENTION
                    } else {
                        AppActivityLifecycleClass.FAILED_ATTENTION
                    },
                title = if (recoverable) "标准 WAV 生成中断" else "标准 WAV 生成失败",
                subtitle = recording.displayName,
                recordingId = recording.id,
                destination =
                    AppActivityDestination.RecordingDetail(
                        recordingId = recording.id,
                        destination = RecordingDetailDestination.PLAYBACK,
                    ),
                availableActions =
                    if (recoverable) {
                        setOf(AppActivityAction.OPEN, AppActivityAction.RETRY)
                    } else {
                        setOf(AppActivityAction.OPEN)
                    },
                priorityHint = CANONICAL_PRIORITY,
            )
        }
    return active + failures
}

internal fun buildCanonicalGlobalTaskItems(
    progressByRecording: Map<String, CanonicalGenerationProgress>,
    recordings: List<RecordingLibraryItem>,
): List<GlobalTaskItem> {
    val recordingsById = recordings.associateBy(RecordingLibraryItem::id)
    val activeIds = progressByRecording.keys

    val active =
        progressByRecording.values
            .sortedBy(CanonicalGenerationProgress::startedAtMs)
            .map { progress ->
                GlobalTaskItem(
                    key = "canonical-running-${progress.recordingId}",
                    recordingId = progress.recordingId,
                    recordingName = recordingsById[progress.recordingId]?.displayName ?: "录音",
                    label = "标准 WAV 生成",
                    progress = canonicalProgressText(progress),
                    destination = RecordingDetailDestination.PLAYBACK,
                    terminal = false,
                )
            }

    val failures =
        recordings.mapNotNull { recording ->
            if (recording.id in activeIds) return@mapNotNull null
            val derivation = currentCanonicalFailure(recording) ?: return@mapNotNull null
            GlobalTaskItem(
                key =
                    buildString {
                        append("canonical-failed-")
                        append(recording.id)
                        append('-')
                        append(derivation.sourceSha256)
                        append('-')
                        append(derivation.state)
                        derivation.errorCode?.let {
                            append('-')
                            append(it)
                        }
                    },
                recordingId = recording.id,
                recordingName = recording.displayName,
                label =
                    if (derivation.state == AudioDerivationState.FAILED_PERMANENT) {
                        "标准 WAV 生成失败"
                    } else {
                        "标准 WAV 生成中断"
                    },
                progress = "查看",
                destination = RecordingDetailDestination.PLAYBACK,
                terminal = true,
            )
        }

    return active + failures
}

internal fun canonicalProgressText(progress: CanonicalGenerationProgress): String {
    if (progress.phase == CanonicalGenerationPhase.CANCELLING) return "正在取消"
    progress.fraction?.let { fraction ->
        return ((fraction * 100f).roundToInt().coerceIn(0, 100)).toString() + "%"
    }
    return when (progress.phase) {
        CanonicalGenerationPhase.PREPARING -> "准备中"
        CanonicalGenerationPhase.DECODING -> "解码中"
        CanonicalGenerationPhase.NORMALIZING -> "标准化中"
        CanonicalGenerationPhase.WRITING -> "写入中"
        CanonicalGenerationPhase.VERIFYING -> "校验中"
        CanonicalGenerationPhase.COMMITTING -> "保存中"
        CanonicalGenerationPhase.CANCELLING -> "正在取消"
    }
}

private fun currentCanonicalFailure(recording: RecordingLibraryItem) =
    recording.derivations
        .filter {
            it.profileId == CanonicalAudioCoordinator.PROFILE_ID &&
                it.pipelineVersion == CanonicalAudioCoordinator.PIPELINE_VERSION &&
                it.state in CANONICAL_FAILURE_STATES
        }
        .lastOrNull()

private val CANONICAL_FAILURE_STATES =
    setOf(
        AudioDerivationState.FAILED_RECOVERABLE,
        AudioDerivationState.FAILED_PERMANENT,
    )

private const val CANONICAL_PRIORITY = 40
