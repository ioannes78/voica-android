package io.github.ioannes78.voica.ui.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `library query is exposed until unified search consumes it`() {
        SearchReturnRuntime.requestSearchLaunch("  战争  ")

        assertEquals("战争", SearchReturnRuntime.launchQuery.value)
        SearchReturnRuntime.consumeSearchLaunch("战争")
        assertNull(SearchReturnRuntime.launchQuery.value)
    }

    @Test
    fun `different query cannot consume pending launch`() {
        SearchReturnRuntime.requestSearchLaunch("战争")

        SearchReturnRuntime.consumeSearchLaunch("和平")

        assertEquals("战争", SearchReturnRuntime.launchQuery.value)
    }
}
