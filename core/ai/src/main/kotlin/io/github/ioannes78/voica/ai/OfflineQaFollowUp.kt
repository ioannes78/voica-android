package io.github.ioannes78.voica.ai

/** Narrow deterministic reference resolution; historical claims are search hints only. */
internal sealed interface OfflineQaFollowUp {
    data object Standalone : OfflineQaFollowUp
    data object Unresolved : OfflineQaFollowUp
    data class Resolved(val claim: OfflineQaClaim) : OfflineQaFollowUp

    companion object {
        private val ordinal = Regex("第([一二三四1234])(?:项|条|点|个)")
        private val unsupportedOrdinal = Regex("第[零〇一二三四五六七八九十百0-9]+(?:项|条|点|个)")
        private val demonstrative = Regex("^(?:那|这)(?:个|项|条|件|点|笔|部分|方面|是|由|要|有)|^(?:它|他|她|上述|前面|刚才|上一条|上一项)|^(?:谁负责|何时|什么时候|多少钱|多少呢|为什么|然后呢|后来呢)[？?。！!]*$")

        fun resolve(question: String, history: List<OfflineQaHistoryTurn>): OfflineQaFollowUp {
            val normalized = OfflineQaLexicalTokenizer.normalize(question).trim()
            val matches = ordinal.findAll(normalized).toList()
            val ordinalLike = unsupportedOrdinal.containsMatchIn(normalized)
            if (!ordinalLike && !demonstrative.containsMatchIn(normalized)) return Standalone
            // Do not skip an unanswered latest turn or guess among multiple claims.
            val claims = history.takeLast(OfflineQaPromptContract.MAX_HISTORY_TURNS)
                .lastOrNull()?.result?.claims.orEmpty()
            if (ordinalLike) {
                if (matches.size != 1 || unsupportedOrdinal.findAll(normalized).count() != 1) return Unresolved
                val index = when (matches.single().groupValues[1]) {
                    "一", "1" -> 0
                    "二", "2" -> 1
                    "三", "3" -> 2
                    "四", "4" -> 3
                    else -> return Unresolved
                }
                return claims.getOrNull(index)?.let(::Resolved) ?: Unresolved
            }
            return claims.singleOrNull()?.let(::Resolved) ?: Unresolved
        }
    }
}
