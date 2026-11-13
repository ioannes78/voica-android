package io.github.ioannes78.voica.database

import io.github.ioannes78.voica.ai.AiSummaryResult
import io.github.ioannes78.voica.ai.StructuredTranscriptInput

class OfflineSummaryEvidenceMappingException(
    val unknownEvidenceRefs: Set<String>,
) : IllegalArgumentException(
        "offline summary contains evidence outside the frozen transcript snapshot: " +
            unknownEvidenceRefs.joinToString(),
    ) {
    init {
        require(unknownEvidenceRefs.isNotEmpty())
    }
}

/** Maps validated semantic evidence IDs back to WENZHI-owned canonical audio coordinates. */
object OfflineSummaryEvidenceMapper {
    fun map(
        input: StructuredTranscriptInput,
        result: AiSummaryResult,
    ): List<OfflineSummaryEvidencePersistenceDraft> {
        val evidenceByRef =
            input.units
                .mapNotNull { it.evidence }
                .associateBy { it.ref }
        val requestedRefs =
            result.sections
                .asSequence()
                .flatMap { it.items.asSequence() }
                .flatMap { item -> item.evidenceRefs.asSequence() }
                .toSet()
        val unknown = requestedRefs - evidenceByRef.keys
        if (unknown.isNotEmpty()) {
            throw OfflineSummaryEvidenceMappingException(unknown)
        }

        return result.sections.flatMap { section ->
            section.items.flatMap { item ->
                item.evidenceRefs.distinct().map { ref ->
                    val evidence = checkNotNull(evidenceByRef[ref])
                    OfflineSummaryEvidencePersistenceDraft(
                        summaryItemId = item.id,
                        sourceRef = evidence.ref,
                        sourceKind = evidence.sourceKind,
                        sourceId = evidence.sourceId,
                        startSampleIndex = evidence.startSampleIndex,
                        endSampleIndexExclusive = evidence.endSampleIndexExclusive,
                        speakerId = evidence.speakerId,
                        assignmentQuality = evidence.assignmentQuality,
                    )
                }
            }
        }
    }
}
