package io.github.ioannes78.voica.ai

data class OfflineQaRetrievalConfig(
    val topK: Int = 12,
    val neighborRadius: Int = 1,
) {
    init {
        require(topK in 1..12)
        require(neighborRadius in 0..1)
    }
}

/**
 * Deterministic, scoped lexical retrieval. Positive lexical matches are seeds,
 * not proof that a question is answerable. Neighbors provide context only.
 * No source text is truncated here; P3 owns the prompt budget.
 */
class OfflineQaRetrieval(private val config: OfflineQaRetrievalConfig = OfflineQaRetrievalConfig()) {
    fun retrieve(
        request: OfflineQaRequest,
        snapshot: OfflineQaSnapshot,
        history: List<OfflineQaHistoryTurn> = emptyList(),
    ): OfflineQaContext {
        require(request.source == snapshot.source)
        require(history.all { it.result.context.request.source == snapshot.source })
        val followUp = OfflineQaFollowUp.resolve(request.question, history)
        if (followUp is OfflineQaFollowUp.Unresolved) return OfflineQaContext(request, snapshot, emptyList())
        val query = OfflineQaLexicalTokenizer.features(request.question, question = true)
        val hint = (followUp as? OfflineQaFollowUp.Resolved)?.let {
            OfflineQaLexicalTokenizer.features(it.claim.text, question = true)
        }
        val anchors = (followUp as? OfflineQaFollowUp.Resolved)?.claim?.evidenceIds.orEmpty().toSet()
        val eligible = snapshot.evidence.mapIndexedNotNull { index, evidence ->
            if (inScope(request, evidence)) IndexedEvidence(index, evidence) else null
        }
        val seeds = eligible.mapNotNull { entry ->
            val document = OfflineQaLexicalTokenizer.features(entry.evidence.excerpt)
            val direct = score(query, document)
            val contextual = hint?.let { score(it, document) } ?: 0
            val anchored = entry.evidence.anchor.ref in anchors
            // In a referential question, generic words such as "负责" alone are not enough.
            val relevance = if (hint == null) direct else if (anchored || contextual > 0) {
                direct + contextual * 2 + if (anchored) 12 else 0
            } else 0
            if (relevance > 0) Scored(entry, relevance) else null
        }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.entry.index })
        val selected = linkedSetOf<String>()
        // Keep all higher-ranked direct hits ahead of contextual neighbors.
        seeds.take(config.topK).forEach { selected.add(it.entry.evidence.anchor.ref) }
        if (config.neighborRadius > 0 && selected.size < config.topK) {
            val byIndex = eligible.associateBy { it.index }
            for (seed in seeds) {
                if (selected.size >= config.topK) break
                for (offset in listOf(-1, 1)) {
                    if (selected.size >= config.topK) break
                    val neighbor = byIndex[seed.entry.index + offset] ?: continue
                    selected.add(neighbor.evidence.anchor.ref)
                }
            }
        }
        return OfflineQaContext(request, snapshot, selected.toList())
    }

    private fun inScope(request: OfflineQaRequest, evidence: OfflineQaEvidence): Boolean {
        if (request.speakerId != null && request.speakerId != evidence.speakerId) return false
        val range = request.sampleRange ?: return true
        return evidence.anchor.startSampleIndex < range.endExclusive &&
            evidence.anchor.endSampleIndexExclusive > range.start
    }

    private fun score(
        query: OfflineQaLexicalTokenizer.Features,
        document: OfflineQaLexicalTokenizer.Features,
    ): Int {
        val phrases = query.phrases.count { it in document.text }
        val bigrams = query.bigrams.count { it in document.bigrams }
        val words = query.words.count { it in document.words }
        val numbers = query.numbers.count { it in document.numbers }
        // A number alone is insufficient to seed unrelated content.
        if (phrases == 0 && bigrams == 0 && words == 0) return 0
        return 8 * phrases + 2 * bigrams + 4 * words + 6 * numbers
    }

    private data class IndexedEvidence(val index: Int, val evidence: OfflineQaEvidence)
    private data class Scored(val entry: IndexedEvidence, val score: Int)
}
