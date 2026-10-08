package io.github.ioannes78.voica.ui.ai

import io.github.ioannes78.voica.database.EffectiveTranscriptionRef

internal fun isSummaryStale(
    lineageTranscriptionId: String?,
    lineageRevisionId: String?,
    effective: EffectiveTranscriptionRef?,
): Boolean =
    effective == null ||
        lineageTranscriptionId != effective.transcriptionId ||
        lineageRevisionId != effective.revisionId

internal fun summaryStaleFingerprint(
    summaryId: String,
    effective: EffectiveTranscriptionRef?,
): String =
    listOf(
        summaryId,
        effective?.transcriptionId.orEmpty(),
        effective?.revisionId.orEmpty(),
    ).joinToString("|")
