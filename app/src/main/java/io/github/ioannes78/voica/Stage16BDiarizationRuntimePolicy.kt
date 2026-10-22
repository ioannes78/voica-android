package io.github.ioannes78.voica

import io.github.ioannes78.voica.transcript.DiarizationConfig
import kotlin.math.min

/**
 * Stage 16B P5-R4 product candidate policy.
 *
 * AUTO speaker count previously over-split the fixed two-speaker QA recording to five speakers.
 * Keep explicit speaker counts and user tuning intact, but apply a conservative floor to AUTO's
 * clustering threshold so window-local clustering is less eager to create extra speakers.
 * Cross-window stitching remains unchanged at this stage so the R4 diagnostics can distinguish the
 * two sources of over-splitting.
 */
internal fun SpeakerCountChoice.toStage16BDiarizationConfig(
    tuning: LocalDiarizationSettings,
): DiarizationConfig {
    val base = toDiarizationConfig(tuning)
    if (this != SpeakerCountChoice.AUTO) return base
    return base.copy(
        clusteringThreshold =
            checkNotNull(base.clusteringThreshold)
                .coerceAtLeast(AUTO_CLUSTERING_THRESHOLD_FLOOR),
    )
}

/**
 * Diarization is a separate post-ASR task. On AUTO performance, let the pyannote/CAM++ path use up
 * to four CPU threads without changing ASR's existing two-thread AUTO policy. Explicit performance
 * profiles and an explicit requested thread count remain authoritative.
 */
internal fun LocalSpeechSettings.resolveStage16BDiarizationPerformance(
    logicalProcessors: Int = Runtime.getRuntime().availableProcessors(),
): ResolvedSpeechPerformance {
    val base = resolvePerformance(logicalProcessors)
    if (requestedThreads != null || performanceProfile != SpeechPerformanceProfile.AUTO) return base
    val safeLogicalProcessors = logicalProcessors.coerceAtLeast(1)
    val safeMaxThreads = min(safeLogicalProcessors, MAX_CONFIGURABLE_THREADS)
    return base.copy(
        effectiveThreads = min(AUTO_DIARIZATION_THREADS, safeMaxThreads),
    )
}

internal const val AUTO_CLUSTERING_THRESHOLD_FLOOR = 0.60F
private const val AUTO_DIARIZATION_THREADS = 4
