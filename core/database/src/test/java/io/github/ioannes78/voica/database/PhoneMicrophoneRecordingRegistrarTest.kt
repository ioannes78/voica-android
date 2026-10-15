package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneMicrophoneRecordingRegistrarTest {
    private lateinit var database: VoicaDatabase
    private lateinit var root: File
    private lateinit var registrar: PhoneMicrophoneRecordingRegistrar
    private lateinit var library: RecordingLibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, VoicaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        root = File(context.cacheDir, "phone-microphone-registrar-test").apply {
            deleteRecursively()
            mkdirs()
        }
        registrar = PhoneMicrophoneRecordingRegistrar(database, root)
        library = RecordingLibraryRepository(database, root)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun formalPhoneWavRegistersIdentityCanonicalLineageForOfflineTranscription() = runBlocking {
        registerPhoneRecording("phone-1")

        val loaded = library.loadRecording("phone-1")
        assertNotNull(loaded)
        val recording = requireNotNull(loaded)
        assertEquals(RecordingSourceType.PHONE_MICROPHONE, recording.sourceType)
        assertEquals("手机录音 2026-10-15 08:00:00", recording.displayName)
        assertEquals(1L, recording.mediaDurationMs)
        assertEquals(1, recording.assets.size)
        val canonical = recording.assets.single()
        assertEquals(AudioAssetRole.CANONICAL_WAV, canonical.role)
        assertEquals(AudioIntegrityState.VERIFIED, canonical.integrityState)
        assertEquals(AudioValidationState.VALID, canonical.formatValidationState)

        val derivation = recording.derivations.single()
        assertEquals(PHONE_CANONICAL_PROFILE_ID, derivation.profileId)
        assertEquals(canonical.assetId, derivation.sourceAssetId)
        assertEquals(canonical.assetId, derivation.outputAssetId)
        assertEquals(canonical.sha256, derivation.sourceSha256)
        assertEquals(PHONE_CANONICAL_PIPELINE_VERSION, derivation.pipelineVersion)
        assertEquals(AudioDerivationState.READY, derivation.state)

        val lineage =
            library.loadCanonicalTranscriptionLineage(
                recordingId = "phone-1",
                profileId = PHONE_CANONICAL_PROFILE_ID,
            )
        assertNotNull(lineage)
        assertEquals(canonical.assetId, requireNotNull(lineage).canonicalAssetId)
        assertEquals(canonical.sha256, lineage.canonicalSha256)
        assertEquals(PHONE_CANONICAL_PROFILE_ID, lineage.canonicalProfileId)
        assertEquals(PHONE_CANONICAL_PIPELINE_VERSION, lineage.canonicalPipelineVersion)

        // Identity lineage does not make formal phone media a reclaimable derived cache.
        assertTrue(library.reclaimableCanonicalCandidates().isEmpty())
    }

    @Test
    fun qaPhoneRecordingWithoutIdentityLineageIsRepairedOnDatabaseOpen() = runBlocking {
        registerPhoneRecording("phone-qa-old")

        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM audio_derivations WHERE recordingId = 'phone-qa-old'",
        )
        assertNull(
            library.loadCanonicalTranscriptionLineage(
                recordingId = "phone-qa-old",
                profileId = PHONE_CANONICAL_PROFILE_ID,
            ),
        )

        repairPhoneCanonicalIdentityLineage(database.openHelper.writableDatabase)

        val repaired =
            library.loadCanonicalTranscriptionLineage(
                recordingId = "phone-qa-old",
                profileId = PHONE_CANONICAL_PROFILE_ID,
            )
        assertNotNull(repaired)
        val canonical = requireNotNull(library.loadRecording("phone-qa-old")).assets.single()
        assertEquals(canonical.assetId, requireNotNull(repaired).canonicalAssetId)
        assertEquals(canonical.assetId, library.loadRecording("phone-qa-old")!!.derivations.single().sourceAssetId)
        assertEquals(canonical.assetId, library.loadRecording("phone-qa-old")!!.derivations.single().outputAssetId)
        assertEquals(AudioDerivationState.READY, library.loadRecording("phone-qa-old")!!.derivations.single().state)
    }

    private suspend fun registerPhoneRecording(recordingId: String) {
        val relativePath = "phone/$recordingId/phone_20261015_080000.wav"
        val file = File(root, relativePath).apply {
            parentFile!!.mkdirs()
            writeBytes(canonicalWav(shortArrayOf(100, -100, 200, -200)))
        }
        registrar.register(
            PhoneMicrophoneRecordingRegistration(
                recordingId = recordingId,
                originalFilename = "phone_20261015_080000.wav",
                displayName = "手机录音 2026-10-15 08:00:00",
                recordedAtLocalIso = "2026-10-15T08:00:00+08:00",
                startedAtMs = 1_000L,
                completedAtMs = 2_000L,
                relativePath = relativePath,
                mediaDurationMs = 1L,
                sizeBytes = file.length(),
                sha256 = sha256(file),
                sampleRateHz = 16_000,
                channelCount = 1,
            ),
        )
    }

    private fun canonicalWav(samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        return ByteArray(44 + dataBytes).also { bytes ->
            fun ascii(offset: Int, value: String) {
                value.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)
            }
            fun le16(offset: Int, value: Int) {
                bytes[offset] = (value and 0xff).toByte()
                bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
            }
            fun le32(offset: Int, value: Int) {
                repeat(4) { shift ->
                    bytes[offset + shift] = ((value ushr (shift * 8)) and 0xff).toByte()
                }
            }

            ascii(0, "RIFF")
            le32(4, 36 + dataBytes)
            ascii(8, "WAVE")
            ascii(12, "fmt ")
            le32(16, 16)
            le16(20, 1)
            le16(22, 1)
            le32(24, 16_000)
            le32(28, 32_000)
            le16(32, 2)
            le16(34, 16)
            ascii(36, "data")
            le32(40, dataBytes)
            var offset = 44
            samples.forEach { sample ->
                val value = sample.toInt()
                bytes[offset++] = (value and 0xff).toByte()
                bytes[offset++] = ((value ushr 8) and 0xff).toByte()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
