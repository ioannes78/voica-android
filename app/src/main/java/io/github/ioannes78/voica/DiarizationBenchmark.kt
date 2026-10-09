package io.github.ioannes78.voica

/**
 * Stage 13C profiling runtime was removed before the Final Candidate.
 *
 * The null-only alias temporarily preserves existing Compose function signatures while ensuring
 * no diarization benchmark runner can be constructed or attached at runtime. It must not be used
 * as a diagnostics surface in production or QA builds.
 */
@Deprecated(
    message = "Stage 13C diarization profiling was retired before the Final Candidate",
    level = DeprecationLevel.WARNING,
)
typealias DiarizationBenchmarkRunner = Nothing
