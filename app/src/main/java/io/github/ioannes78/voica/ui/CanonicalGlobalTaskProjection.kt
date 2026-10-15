package io.github.ioannes78.voica.ui

import io.github.ioannes78.voica.CanonicalAudioCoordinator
import io.github.ioannes78.voica.CanonicalGenerationPhase
import io.github.ioannes78.voica.CanonicalGenerationProgress
import io.github.ioannes78.voica.database.AudioDerivationState
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.ui.library.RecordingDetailDestination
import kotlin.math.roundToInt

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
            val derivation =
                recording.derivations
                    .filter {
                        it.profileId == CanonicalAudioCoordinator.PROFILE_ID &&
                            it.pipelineVersion == CanonicalAudioCoordinator.PIPELINE_VERSION &&
                            it.state in CANONICAL_FAILURE_STATES
                    }
                    .lastOrNull()
                    ?: return@mapNotNull null
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

private val CANONICAL_FAILURE_STATES =
    setOf(
        AudioDerivationState.FAILED_RECOVERABLE,
        AudioDerivationState.FAILED_PERMANENT,
    )
