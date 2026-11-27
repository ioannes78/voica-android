package io.github.ioannes78.voica.ai

import java.text.Normalizer
import java.util.Locale

/** Local lexical features, not an embedding or a semantic relevance guarantee. */
internal object OfflineQaLexicalTokenizer {
    private val questionNoise = Regex("请问|这次|本次|本录音|会议中|是什么|是多少|什么时候|有哪些|怎么样|有没有|能否|告诉我|什么|多少|如何|哪个|哪些|为什么|谁")
    private val latinWords = Regex("[a-z]+[a-z0-9_]*")
    private val numbers = Regex("[0-9]+(?:[.,][0-9]+)*")
    private val chineseNumbers = Regex("[零〇一二两三四五六七八九十百千万亿]+")
    private val hanRuns = Regex("\\p{IsHan}+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    fun features(text: String, question: Boolean = false): Features {
        val normalized = normalize(text)
        val clean = if (question) questionNoise.replace(normalized, " ") else normalized
        val phrases = hanRuns.findAll(clean).map { it.value }.filter {
            it.codePointCount(0, it.length) >= 2
        }.toSet()
        val bigrams = phrases.flatMap { phrase ->
            val points = phrase.codePoints().toArray()
            (0 until points.size - 1).map { index -> String(points, index, 2) }
        }.toSet()
        val words = latinWords.findAll(clean).map { it.value }.toSet()
        val numeric = (numbers.findAll(clean).map { it.value } +
            chineseNumbers.findAll(clean).map { it.value }).toSet()
        return Features(clean, phrases, bigrams, words, numeric)
    }

    data class Features(
        val text: String,
        val phrases: Set<String>,
        val bigrams: Set<String>,
        val words: Set<String>,
        val numbers: Set<String>,
    )
}
