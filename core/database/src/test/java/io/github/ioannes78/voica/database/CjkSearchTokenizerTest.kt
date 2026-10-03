package io.github.ioannes78.voica.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkSearchTokenizerTest {
    @Test
    fun chineseIndexIsCharacterTokenizedButEnglishWordIsKept() {
        assertEquals(
            "今 天 讨 论 供 应 链 风 险 和 openai api",
            CjkSearchTokenizer.toIndexText("今天讨论供应链风险和 OpenAI API"),
        )
    }

    @Test
    fun chineseQueryUsesOrderedPhrase() {
        assertEquals(
            "\"供 应 链\"",
            CjkSearchTokenizer.toMatchQuery("供应链"),
        )
    }

    @Test
    fun mixedQueryUsesChinesePhraseAndSafeEnglishPrefix() {
        assertEquals(
            "\"供 应 链\" AND \"openai\"*",
            CjkSearchTokenizer.toMatchQuery("供应链 OpenAI"),
        )
    }

    @Test
    fun ftsOperatorsAndPunctuationCannotBecomeRawSyntax() {
        val query = CjkSearchTokenizer.toMatchQuery("AND (风险) - OR *")
        assertTrue(query.contains("\"and\"*"))
        assertTrue(query.contains("\"风 险\""))
        assertTrue(query.contains("\"or\"*"))
        assertFalse(query.contains("("))
        assertFalse(query.contains(")"))
    }
}
