package io.github.ioannes78.voica

import io.github.ioannes78.voica.transcript.DiarizationConfig
import kotlin.math.min

/**
 * Stage 16B P5-R5 product runtime policy.
 *
 * Speaker-count tuning is resolved by LocalDiarizationSettings and then passed to native without a
 * hidden AUTO-only threshold override. This keeps the settings UI, persisted preference, runtime,
 * and diagnostics on the same effective clustering threshold.
 *
 * Cross-window stitching remains unchanged while R5 isolates window-local clustering quality.
 */
internal fun SpeakerCountChoice.toStage16BDiarizationConfig(
    tuning: LocalDiarizationSettings,
): DiarizationConfig = toDiarizationConfig(tuning)

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

internal const val AUTO_DIARIZATION_THREADS = 4
