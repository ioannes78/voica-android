package io.github.ioannes78.voica

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.audio.CanonicalWavWriter
import io.github.ioannes78.voica.audio.CompressedAudioDecodeResult
import io.github.ioannes78.voica.audio.CompressedAudioDecoder
import io.github.ioannes78.voica.audio.CompressedAudioDescriptor
import io.github.ioannes78.voica.audio.DecodedPcmFormat
import io.github.ioannes78.voica.audio.Pcm16DecodeSink
import io.github.ioannes78.voica.audio.WavParseResult
import io.github.ioannes78.voica.audio.WavPcmParser
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.ImportedOriginalRegistration
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.VoicaDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class CanonicalImportedAudioTest {
    private lateinit var database: VoicaDatabase
    private lateinit var recordingsRoot: File
    private lateinit var repository: RecordingLibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        recordingsRoot =
            File(context.cacheDir, "stage12b-canonical-import").apply {
                deleteRecursively()
                mkdirs()
            }
        repository =
            RecordingLibraryRepository(
                database = database,
                recordingsRoot = recordingsRoot,
                nowMs = { 20_000L },
            )
    }

    @After
    fun tearDown() {
        database.close()
        recordingsRoot.deleteRecursively()
    }

    @Test
    fun importedCanonicalWavIsReusedWithoutDeletingOriginal() = runBlocking {
        val importedDir = File(recordingsRoot, "imported").apply { mkdirs() }
        val source = File(importedDir, "source.wav")
        CanonicalWavWriter(source).use { writer ->
            writer.writePcm16(shortArrayOf(0, 100, -100, 200, -200))
            writer.commit()
        }
        val recordingId =
            repository.registerImportedOriginal(
                registrationFor(
                    source = source,
                    filename = "source.wav",
                    container = "WAV",
                    codec = "PCM",
                    sampleFormat = "PCM16_LE",
                    sampleRateHz = 16_000,
                    channelCount = 1,
                    durationMs = 1,
                ),
            )

        val coordinator =
            CanonicalAudioCoordinator(
                repository = repository,
                recordingsRoot = recordingsRoot,
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            )

        assertEquals(CanonicalGenerationOutcome.Ready, coordinator.generate(recordingId))
        val recording = requireNotNull(repository.loadRecording(recordingId))
        val original = recording.assets.single { it.role == AudioAssetRole.IMPORTED_ORIGINAL }
        val canonical = recording.assets.single { it.role == AudioAssetRole.CANONICAL_WAV }
        assertEquals(original.relativePath, canonical.relativePath)
        assertTrue(source.isFile)
    }

    @Test
    fun importedCompressedAudioUsesDecoderAndProducesCanonicalWav() = runBlocking {
        val importedDir = File(recordingsRoot, "imported").apply { mkdirs() }
        val source = File(importedDir, "source.mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        }
        val recordingId =
            repository.registerImportedOriginal(
                registrationFor(
                    source = source,
                    filename = "source.mp3",
                    container = "MP3",
                    codec = "audio/mpeg",
                    sampleFormat = null,
                    sampleRateHz = 48_000,
                    channelCount = 2,
                    durationMs = 1,
                ),
            )

        val fakeDecoder =
            object : CompressedAudioDecoder {
                override suspend fun decode(
                    file: File,
                    isCancelled: () -> Boolean,
                    sink: Pcm16DecodeSink,
                ): CompressedAudioDecodeResult {
                    val format = DecodedPcmFormat(48_000, 2)
                    sink.onFormat(format)
                    val frames = 48
                    val samples = ShortArray(frames * 2) { index ->
                        if (index % 2 == 0) 1_000 else 3_000
                    }
                    sink.onSamples(samples, frames)
                    return CompressedAudioDecodeResult(
                        input =
                            CompressedAudioDescriptor(
                                mimeType = "audio/mpeg",
                                sampleRateHz = 48_000,
                                channelCount = 2,
                                durationUs = 1_000,
                                decoderName = "fake",
                            ),
                        output = format,
                        decodedFrameCount = frames.toLong(),
                    )
                }
            }

        val coordinator =
            CanonicalAudioCoordinator(
                repository = repository,
                recordingsRoot = recordingsRoot,
                applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                mediaDecoder = fakeDecoder,
            )

        assertEquals(CanonicalGenerationOutcome.Ready, coordinator.generate(recordingId))
        val recording = requireNotNull(repository.loadRecording(recordingId))
        val original = recording.assets.single { it.role == AudioAssetRole.IMPORTED_ORIGINAL }
        val canonical = recording.assets.single { it.role == AudioAssetRole.CANONICAL_WAV }
        assertTrue(original.relativePath != canonical.relativePath)

        val canonicalFile = File(recordingsRoot, canonical.relativePath)
        val parsed = WavPcmParser.parse(canonicalFile)
        assertTrue(parsed is WavParseResult.Valid)
        assertTrue((parsed as WavParseResult.Valid).info.isCanonical)
        assertTrue(source.isFile)
    }

    private fun registrationFor(
        source: File,
        filename: String,
        container: String,
        codec: String?,
        sampleFormat: String?,
        sampleRateHz: Int?,
        channelCount: Int?,
        durationMs: Long?,
    ): ImportedOriginalRegistration =
        ImportedOriginalRegistration(
            originalFilename = filename,
            displayName = filename.substringBeforeLast('.'),
            relativePath = source.relativeTo(recordingsRoot).invariantSeparatorsPath,
            sourceMimeType = codec,
            container = container,
            codec = codec,
            sampleFormat = sampleFormat,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            mediaDurationMs = durationMs,
            sizeBytes = source.length(),
            sha256 = sha256(source),
            importedAtMs = 20_000L,
            providerAuthority = "test",
            sourceLastModifiedMs = 19_000L,
        )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
