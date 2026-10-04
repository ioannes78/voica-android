package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnifiedSearchRepositoryTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: UnifiedSearchRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(
                context,
                VoicaDatabase::class.java,
            ).allowMainThreadQueries().build()
        repository = UnifiedSearchRepository(database, nowMs = { 1000L })
        runBlocking {
            repository.upsert(
                SearchDocumentDraft(
                    documentId = "tx:1",
                    documentType = SearchDocumentTypeValue.TRANSCRIPT_UNIT,
                    displayTitle = "客户访谈",
                    displayText = "今天讨论供应链风险和 OpenAI API 接入计划。",
                    updatedAtMs = 1L,
                ),
            )
            repository.upsert(
                SearchDocumentDraft(
                    documentId = "summary:1",
                    documentType = SearchDocumentTypeValue.SUMMARY_ITEM,
                    displayTitle = "行动项",
                    displayText = "项目进度下周复盘。",
                    updatedAtMs = 2L,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun chineseContinuousPhraseMatchesTranscript() = runBlocking {
        assertEquals(
            listOf("tx:1"),
            repository.search("供应链").map { it.documentId },
        )
    }

    @Test
    fun chineseMultipleTermsMatchTranscript() = runBlocking {
        assertEquals(
            listOf("tx:1"),
            repository.search("供应链 风险").map { it.documentId },
        )
    }

    @Test
    fun chineseEnglishMixedQueryMatchesTranscript() = runBlocking {
        assertEquals(
            listOf("tx:1"),
            repository.search("供应链 OpenAI").map { it.documentId },
        )
    }

    @Test
    fun chineseSummaryBodyMatchesSummaryItem() = runBlocking {
        assertEquals(
            listOf("summary:1"),
            repository.search("项目进度").map { it.documentId },
        )
    }

    @Test
    fun documentTypeFilterRestrictsEnglishHit() = runBlocking {
        assertEquals(
            listOf("tx:1"),
            repository.search(
                query = "openai",
                documentTypes = setOf(SearchDocumentTypeValue.TRANSCRIPT_UNIT),
            ).map { it.documentId },
        )
        assertTrue(
            repository.search(
                query = "openai",
                documentTypes = setOf(SearchDocumentTypeValue.SUMMARY_ITEM),
            ).isEmpty(),
        )
    }

    @Test
    fun ftsSyntaxCharactersAreEscapedInsteadOfExecuted() = runBlocking {
        repository.upsert(
            SearchDocumentDraft(
                documentId = "safe:1",
                documentType = SearchDocumentTypeValue.TRANSCRIPT_UNIT,
                displayTitle = "安全测试",
                displayText = "风险 AND OR 测试",
                updatedAtMs = 3L,
            ),
        )

        val results = repository.search("AND (风险) - OR *")
        assertTrue(results.any { it.documentId == "safe:1" })
    }
}
