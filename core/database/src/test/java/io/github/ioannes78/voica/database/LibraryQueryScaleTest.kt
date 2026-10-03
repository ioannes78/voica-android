package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
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
class LibraryQueryScaleTest {
    private lateinit var database: VoicaDatabase
    private lateinit var repository: RecordingLibraryRepository
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        root = File(context.cacheDir, "stage12b-library-scale").apply {
            deleteRecursively()
            mkdirs()
        }
        repository = RecordingLibraryRepository(database, root)
        seed(1_000)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test(timeout = 20_000L)
    fun thousandRecordingLibrarySupportsDatabaseSortSearchAndCombinedFilters() =
        runBlocking {
            val bySize =
                repository.observeLibrary(
                    LibraryQueryCriteria(sort = LibrarySort.SIZE),
                ).first()
            assertEquals(1_000, bySize.size)
            assertTrue(bySize.zipWithNext().all { (a, b) ->
                a.physicalAudioBytes >= b.physicalAudioBytes
            })

            val search =
                repository.observeLibrary(
                    LibraryQueryCriteria(query = "客户 0999"),
                ).first()
            assertEquals(listOf("rec-0999"), search.map { it.id })

            val filtered =
                repository.observeLibrary(
                    LibraryQueryCriteria(
                        favoriteOnly = true,
                        folderId = "folder-client",
                        tagIds = setOf("tag-important"),
                        sourceTypes = setOf(RecordingSourceType.DEVICE_DOWNLOAD),
                        sort = LibrarySort.UPDATED,
                    ),
                ).first()

            assertEquals(50, filtered.size)
            assertTrue(filtered.all { it.isFavorite })
            assertTrue(filtered.all { it.folderId == "folder-client" })
            assertTrue(filtered.all { "重要" in it.tagNames })
            assertTrue(
                filtered.zipWithNext().all { (a, b) ->
                    a.updatedAtMs >= b.updatedAtMs
                },
            )
        }

    @Test(timeout = 15_000L)
    fun fiveHundredRecordingDateRangeRemainsDatabaseBound() =
        runBlocking {
            val rows =
                repository.observeLibrary(
                    LibraryQueryCriteria(
                        addedFromMs = 500_250L,
                        addedToMsExclusive = 500_500L,
                        sort = LibrarySort.ADDED,
                    ),
                ).first()

            assertEquals(250, rows.size)
            assertEquals("rec-0499", rows.first().id)
            assertEquals("rec-0250", rows.last().id)
        }

    private fun seed(count: Int) {
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                """
                INSERT INTO recording_folders (
                    folderId, name, createdAtMs, updatedAtMs
                ) VALUES ('folder-client', '客户资料', 1, 1)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO recording_tags (
                    tagId, name, createdAtMs, updatedAtMs
                ) VALUES ('tag-important', '重要', 1, 1)
                """.trimIndent(),
            )

            repeat(count) { index ->
                val id = "rec-" + index.toString().padStart(4, '0')
                val sourceType =
                    if (index % 2 == 0) {
                        RecordingSourceType.DEVICE_DOWNLOAD
                    } else {
                        RecordingSourceType.LOCAL_IMPORT
                    }
                val favorite = if (index % 10 == 0) 1 else 0
                val inFolder = index % 2 == 0
                val important = index % 20 == 0
                val createdAt = 500_000L + index
                val updatedAt = 600_000L + index

                db.execSQL(
                    """
                    INSERT INTO recordings (
                        id, sourceType, sourceRemoteIdentity, sourceDeviceAddress,
                        originalFilename, displayName, recordedAtLocalIso,
                        deviceReportedDurationMs, mediaDurationMs, downloadedAtMs,
                        createdAtMs, updatedAtMs, state
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """.trimIndent(),
                    arrayOf<Any?>(
                        id,
                        sourceType,
                        if (sourceType == RecordingSourceType.DEVICE_DOWNLOAD) "remote-$id" else null,
                        if (sourceType == RecordingSourceType.DEVICE_DOWNLOAD) "AA:BB" else null,
                        "$id.wav",
                        "客户 " + index.toString().padStart(4, '0'),
                        "2026-10-" + ((index % 28) + 1).toString().padStart(2, '0') + "T10:00:00",
                        if (sourceType == RecordingSourceType.DEVICE_DOWNLOAD) 1_000L else null,
                        1_000L,
                        if (sourceType == RecordingSourceType.DEVICE_DOWNLOAD) createdAt else null,
                        createdAt,
                        updatedAt,
                    ),
                )
                db.execSQL(
                    """
                    INSERT INTO recording_user_metadata (
                        recordingId, folderId, isFavorite, updatedAtMs
                    ) VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        id,
                        if (inFolder) "folder-client" else null,
                        favorite,
                        updatedAt,
                    ),
                )
                db.execSQL(
                    """
                    INSERT INTO audio_assets (
                        assetId, recordingId, role, relativePath, container, codec,
                        sampleFormat, sampleRateHz, channelCount, sizeBytes, sha256,
                        integrityState, formatValidationState, createdAtMs, verifiedAtMs
                    ) VALUES (?, ?, 'DEVICE_WAV', ?, 'WAV', 'PCM',
                              'PCM16_LE', 16000, 1, ?, ?,
                              'VERIFIED', 'VALID', ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        "asset-$id",
                        id,
                        "completed/$id.wav",
                        10_000L + index,
                        index.toString(16).padStart(64, '0').takeLast(64),
                        createdAt,
                        createdAt,
                    ),
                )
                if (important) {
                    db.execSQL(
                        """
                        INSERT INTO recording_tag_cross_refs (recordingId, tagId)
                        VALUES (?, 'tag-important')
                        """.trimIndent(),
                        arrayOf(id),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
