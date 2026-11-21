package io.github.ioannes78.voica.ai

/**
 * R16.6: last-resort, source-grounded REDUCE recovery after the one permitted LLM repair.
 *
 * Only coverage failures are eligible. The protected items are copied verbatim from validated
 * upstream checkpoints with their own refs; no references are added to unrelated model text.
 * This is deliberately bounded by the existing six-item intermediate schema.
 */
object OfflineSummaryDeterministicReduceRecovery {
    fun recover(
        raw: String,
        inputNodes: List<OfflineSummaryValidatedIntermediateNode>,
        allowedSourceRefs: Set<String>,
        allowedEvidenceRefs: Set<String>,
    ): String? {
        val generationRefs = allowedSourceRefs as? OfflineSummaryGenerationSourceRefs ?: return null
        val requiredGroups = generationRefs.requiredCoverageGroups ?: return null
        if (inputNodes.isEmpty() || requiredGroups.isEmpty()) return null

        // Reject invalid JSON, bad enums, out-of-range/unknown refs, or any structural fault.
        // Strip only the generation-time coverage gate, never the membership/bounds validation.
        val parsed =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw,
                    allowedSourceRefs.toCollection(LinkedHashSet()),
                    allowedEvidenceRefs.toCollection(LinkedHashSet()),
                )
            }.getOrNull() ?: return null
        val coverageError =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(raw, allowedSourceRefs, allowedEvidenceRefs)
            }.exceptionOrNull() as? OfflineSummaryIntermediateException
                ?: return null
        if (coverageError.code != OfflineSummaryIntermediateErrorCode.INSUFFICIENT_SOURCE_COVERAGE) {
            return null
        }

        val upstream = inputNodes.flatMap { it.summary.items }
        val anchorIndexes = offlineSummaryRetentionAnchorIndexes(upstream)
        if (anchorIndexes.size != requiredGroups.size ||
            anchorIndexes.size > OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEMS
        ) {
            return null
        }
        val anchored = anchorIndexes.map { upstream[it] }
        if (anchored.indices.any { anchored[it].sourceRefs.none(requiredGroups[it]::contains) }) {
            return null // Never recover against an inconsistent anchor contract.
        }

        val anchorTexts = anchored.mapTo(HashSet()) { it.text }
        val anchorRefs = anchored.flatMapTo(HashSet()) { it.sourceRefs }
        val extraCapacity = OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEMS - anchored.size
        val supplementary =
            parsed.items
                .filterNot { it.text in anchorTexts }
                .withIndex()
                .sortedWith(
                    compareByDescending<IndexedValue<OfflineSummaryIntermediateItem>> {
                        it.value.sourceRefs.count { ref -> ref !in anchorRefs }
                    }.thenBy { it.index },
                )
                .take(extraCapacity)
                .map { it.value }

        // Source-ordered, immutable originals first, with up to two generated complementary items.
        // Re-ID all items to preserve the strict uniqueness and length contract.
        val items =
            (anchored + supplementary).mapIndexed { index, item ->
                item.copy(id = "R${index + 1}")
            }
        val recovered = OfflineSummaryIntermediateCodec.encode(parsed.copy(items = items))
        return runCatching {
            OfflineSummaryIntermediateCodec.decode(recovered, allowedSourceRefs, allowedEvidenceRefs)
            recovered
        }.getOrNull()
    }
}
