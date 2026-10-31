package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class VoicaDatabaseCurrentCreateTest {
    private lateinit var context: Context
    private lateinit var database: VoicaDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE_NAME)
        database =
            Room.databaseBuilder(
                context,
                VoicaDatabase::class.java,
                TEST_DATABASE_NAME,
            )
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DATABASE_NAME)
    }

    @Test
    fun currentSchemaCreatesCleanDatabaseAtVersion17() {
        val writableDatabase = database.openHelper.writableDatabase

        assertEquals(17, writableDatabase.version)
        database.recordingDao()
        database.transcriptionDao()
        database.diarizationDao()
        database.aiSummaryDao()
        database.offlineSummaryLongDao()
        database.stage12cContentDao()
        database.stage13B5Qa4Dao()
        database.searchDao()
        database.realtimeTranscriptionDao()
        database.timelineAnnotationDao()

        assertTrue(tableExists(writableDatabase, "offline_summary_tasks"))
        assertTrue(tableExists(writableDatabase, "offline_summary_nodes"))
    }

    private fun tableExists(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        tableName: String,
    ): Boolean =
        db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0) == 1
        }

    private companion object {
        const val TEST_DATABASE_NAME = "voica-current-create-test.db"
    }
}
