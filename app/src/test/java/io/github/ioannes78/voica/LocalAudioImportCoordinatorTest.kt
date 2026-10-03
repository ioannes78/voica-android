package io.github.ioannes78.voica

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.audio.CanonicalWavWriter
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.VoicaDatabase
import java.io.File
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
class LocalAudioImportCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: VoicaDatabase
    private lateinit var recordingsRoot: File
    private lateinit var sourceRoot: File
    private lateinit var repository: RecordingLibraryRepository
    private lateinit var coordinator: LocalAudioImportCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        recordingsRoot =
            File(context.cacheDir, "stage12b-import-recordings").apply {
                deleteRecursively()
                mkdirs()
            }
        sourceRoot =
            File(context.cacheDir, "stage12b-import-source").apply {
                deleteRecursively()
                mkdirs()
            }
        repository =
            RecordingLibraryRepository(
                database = database,
                recordingsRoot = recordingsRoot,
                nowMs = { 10_000L },
            )
        coordinator =
            LocalAudioImportCoordinator(
                context = context,
                repository = repository,
                recordingsRoot = recordingsRoot,
                nowMs = { 10_000L },
            )
    }

    @After
    fun tearDown() {
        database.close()
        recordingsRoot.deleteRecursively()
        sourceRoot.deleteRecursively()
    }

    @Test
    fun wavImportIsTransactionalDetectsDuplicateAndAllowsExplicitCopy() = runBlocking {
        val source = File(sourceRoot, "voice.wav")
        CanonicalWavWriter(source).use { writer ->
            writer.writePcm16(shortArrayOf(0, 1_000, -1_000, 500, -500))
            writer.commit()
        }
        val uri = Uri.fromFile(source)

        val first = coordinator.import(uri)
        assertTrue(first is LocalAudioImportOutcome.Imported)
        val firstId = (first as LocalAudioImportOutcome.Imported).recordingId
        val firstRecording = requireNotNull(repository.loadRecording(firstId))
        assertEquals("LOCAL_IMPORT", firstRecording.sourceType)
        assertEquals("IMPORTED_ORIGINAL", firstRecording.assets.single().role)
        assertEquals(null, firstRecording.downloadedAtMs)

        val duplicate = coordinator.import(uri)
        assertTrue(duplicate is LocalAudioImportOutcome.Duplicate)
        duplicate as LocalAudioImportOutcome.Duplicate
        assertEquals(1, duplicate.matches.size)
        assertEquals(firstId, duplicate.matches.single().recordingId)

        val forced = coordinator.import(uri, allowDuplicate = true)
        assertTrue(forced is LocalAudioImportOutcome.Imported)
        val forcedId = (forced as LocalAudioImportOutcome.Imported).recordingId
        assertTrue(forcedId != firstId)

        val importedFiles =
            File(recordingsRoot, "imported")
                .listFiles()
                .orEmpty()
                .filter(File::isFile)
        assertEquals(2, importedFiles.size)

        val stagingFiles =
            File(recordingsRoot, "import-staging")
                .listFiles()
                .orEmpty()
                .filter(File::isFile)
        assertTrue(stagingFiles.isEmpty())
    }
}
