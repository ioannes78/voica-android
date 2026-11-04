package io.github.ioannes78.voica.ai

/**
 * R16.6: bounded, deterministic recovery after a REDUCE model's one permitted repair.
 *
 * This does NOT manufacture evidence or attach a missing ref to an unrelated generated item.
 * Every restored item is an unchanged, previously validated child item (except for its local id).
 * If the result has no safe room, we keep the hard coverage failure rather than silently dropping
 * an item that is the only representation of an already covered hard anchor.
 */
object OfflineSummaryHardAnchorRecovery {
    fun recover(
        raw: String,
        allowedSourceRefs: Set<String>,
        allowedEvidenceRefs: Set<String>,
        hardRetentionItems: List<OfflineSummaryIntermediateItem>,
    ): String? {
        if (hardRetentionItems.isEmpty()) return null

        // A fresh plain Set deliberately checks all structural/ref bounds without the
        // generation-only coverage gate. Never recover invalid JSON, unknown refs, or bad schema.
        val decoded =
            runCatching {
                OfflineSummaryIntermediateCodec.decode(
                    raw = raw,
                    allowedSourceRefs = LinkedHashSet(allowedSourceRefs),
                    allowedEvidenceRefs = allowedEvidenceRefs,
                )
            }.getOrNull() ?: return null

        val coveredRefs = decoded.items.flatMapTo(LinkedHashSet()) { it.sourceRefs }
        val missing =
            hardRetentionItems.withIndex().filter { (_, item) ->
                item.sourceRefs.none(coveredRefs::contains)
            }
        if (missing.isEmpty()) return null

        val excess =
            (decoded.items.size + missing.size - OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEMS)
                .coerceAtLeast(0)

        // Only non-anchor output items may be displaced. Do not erase an already covered
        // hard anchor, even if the replacement itself would improve the numerical coverage.
        val anchorRefs = hardRetentionItems.flatMapTo(LinkedHashSet()) { it.sourceRefs }
        val removable =
            decoded.items.withIndex()
                .filter { (_, item) -> item.sourceRefs.none(anchorRefs::contains) }
                .sortedWith(
                    compareBy<IndexedValue<OfflineSummaryIntermediateItem>> { removalPriority(it.value) }
                        .thenByDescending { it.index },
                )
        if (removable.size < excess) return null

        val removedIndexes = removable.take(excess).mapTo(HashSet()) { it.index }
        val recoveredItems =
            decoded.items.filterIndexed { index, _ -> index !in removedIndexes }.toMutableList()
        val usedIds = recoveredItems.mapTo(HashSet()) { it.id }
        for ((position, upstream) in missing) {
            // The facts, classifications, epistemic status and provenance are sourced verbatim
            // from the preceding validated MAP/REDUCE checkpoint; only the item id is local.
            val id =
                (1..OfflineSummaryLongContract.MAX_INTERMEDIATE_ITEMS + hardRetentionItems.size)
                    .map { "r${position + 1}_${it}" }
                    .firstOrNull { usedIds.add(it) }
                    ?: return null
            recoveredItems += upstream.copy(id = id)
        }

        val recovered = OfflineSummaryIntermediateCodec.encode(decoded.copy(items = recoveredItems))
        // Reapply the *original* generation-time hard gate and every structural/ref check.
        return recovered.takeIf {
            runCatching {
                OfflineSummaryIntermediateCodec.decode(it, allowedSourceRefs, allowedEvidenceRefs)
            }.isSuccess
        }
    }

    private fun removalPriority(item: OfflineSummaryIntermediateItem): Int {
        val typeWeight =
            when (item.type) {
                OfflineSummaryIntermediateItemType.OTHER -> 0
                OfflineSummaryIntermediateItemType.KEY_POINT -> 1
                OfflineSummaryIntermediateItemType.FACT -> 2
                OfflineSummaryIntermediateItemType.DECISION,
                OfflineSummaryIntermediateItemType.ACTION_ITEM,
                OfflineSummaryIntermediateItemType.RISK,
                OfflineSummaryIntermediateItemType.QUESTION,
                OfflineSummaryIntermediateItemType.FOLLOW_UP,
                -> 3
            }
        val quantitative = if (QUANTITATIVE_SIGNAL.containsMatchIn(item.text)) 1 else 0
        return typeWeight + quantitative
    }

    private val QUANTITATIVE_SIGNAL = Regex("[0-9零〇一二三四五六七八九十百千万亿两点.%％成倍趴]")
}
