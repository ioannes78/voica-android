package io.github.ioannes78.voica.database

import java.text.Normalizer
import java.util.Locale

object CjkSearchTokenizer {
    fun toIndexText(input: String): String =
        lexicalGroups(input)
            .flatMap { group ->
                if (group.cjk) {
                    group.text.codePoints().toArray().map { Character.toString(it) }
                } else {
                    listOf(group.text)
                }
            }
            .joinToString(" ")

    fun toMatchQuery(input: String): String {
        val expressions =
            lexicalGroups(input).mapNotNull { group ->
                if (group.text.isBlank()) {
                    null
                } else if (group.cjk) {
                    val phrase =
                        group.text.codePoints().toArray()
                            .joinToString(" ") { Character.toString(it) }
                    quote(phrase)
                } else {
                    quote(group.text) + "*"
                }
            }
        return expressions.joinToString(" AND ")
    }

    private fun lexicalGroups(input: String): List<LexicalGroup> {
        val normalized =
            Normalizer.normalize(input, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .trim()
        if (normalized.isEmpty()) return emptyList()

        val result = mutableListOf<LexicalGroup>()
        val buffer = StringBuilder()
        var bufferIsCjk: Boolean? = null

        fun flush() {
            if (buffer.isNotEmpty() && bufferIsCjk != null) {
                result += LexicalGroup(buffer.toString(), bufferIsCjk!!)
                buffer.setLength(0)
            }
        }

        normalized.codePoints().forEach { codePoint ->
            val cjk = isCjk(codePoint)
            val word = cjk || Character.isLetterOrDigit(codePoint) || codePoint == '_'.code
            if (!word) {
                flush()
                bufferIsCjk = null
                return@forEach
            }
            if (bufferIsCjk != null && bufferIsCjk != cjk) {
                flush()
            }
            bufferIsCjk = cjk
            buffer.appendCodePoint(codePoint)
        }
        flush()
        return result
    }

    private fun quote(token: String): String =
        """ + token.replace(""", """") + """

    private fun isCjk(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x2FA1F

    private data class LexicalGroup(
        val text: String,
        val cjk: Boolean,
    )
}
