package io.github.ioannes78.voica.ui.search

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchReturnRuntimeTest {
    @Before
    fun setUp() {
        SearchReturnRuntime.clear()
    }

    @After
    fun tearDown() {
        SearchReturnRuntime.clear()
    }

    @Test
    fun `detail return marker is consumed once`() {
        SearchReturnRuntime.markDetailOpen()

        assertTrue(SearchReturnRuntime.consumePendingReturn())
        assertFalse(SearchReturnRuntime.consumePendingReturn())
    }

    @Test
    fun `clear removes pending detail return`() {
        SearchReturnRuntime.markDetailOpen()
        SearchReturnRuntime.clear()

        assertFalse(SearchReturnRuntime.consumePendingReturn())
    }
}
