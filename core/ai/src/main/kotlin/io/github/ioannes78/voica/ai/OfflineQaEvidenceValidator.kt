package io.github.ioannes78.voica.ai

import java.text.Normalizer

/**
 * Conservative lexical guard for explicit Arabic numerals and common adjacent units.
 * Not a semantic entailment checker; unsupported numeric paraphrases fail closed.
 */
object OfflineQaEvidenceValidator {
    private val quantity = Regex("[+-]?[0-9]+(?:[.,:/-][0-9]+)*(?:\\s*(?:亿元|万元|千元|元|人民币|美元|公斤|千克|公里|厘米|毫米|米|小时|分钟|秒|个月|年|月|日|天|台|件|人|吨|%|kg|km|mm|cm))?", RegexOption.IGNORE_CASE)

    fun validate(result: OfflineQaResult) {
        val candidates = result.context.candidates.associateBy { it.anchor.ref }
        result.claims.forEach { claim ->
            val sources = claim.evidenceIds.map { ref ->
                candidates[ref] ?: throw OfflineQaOutputException(OfflineQaOutputError.INVALID_EVIDENCE)
            }
            val supported = sources.flatMap { tokens(it.excerpt) }.toSet()
            if (tokens(claim.text).any { it !in supported }) {
                throw OfflineQaOutputException(OfflineQaOutputError.NUMERIC_MISMATCH)
            }
        }
    }

    private fun tokens(text: String): Set<String> =
        quantity.findAll(Normalizer.normalize(text, Normalizer.Form.NFKC))
            .map { it.value.replace(Regex("\\s+"), "").lowercase(java.util.Locale.ROOT) }
            .toSet()
}
