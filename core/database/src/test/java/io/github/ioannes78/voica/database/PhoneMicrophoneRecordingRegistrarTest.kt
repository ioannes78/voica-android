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
    fun formalPhoneWavRegistersInExistingLibraryWithoutFakeDerivation() = runBlocking {
        val relativePath = "phone/phone-1/phone_20261015_080000.wav"
        val file = File(root, relativePath).apply {
            parentFile!!.mkdirs()
            writeBytes(canonicalWav(shortArrayOf(100, -100, 200, -200)))
        }
        val digest = sha256(file)

        registrar.register(
            PhoneMicrophoneRecordingRegistration(
                recordingId = "phone-1",
                originalFilename = "phone_20261015_080000.wav",
                displayName = "手机录音 2026-10-15 08:00:00",
                recordedAtLocalIso = "2026-10-15T08:00:00+08:00",
                startedAtMs = 1_000L,
                completedAtMs = 2_000L,
                relativePath = relativePath,
                mediaDurationMs = 1L,
                sizeBytes = file.length(),
                sha256 = digest,
                sampleRateHz = 16_000,
                channelCount = 1,
            ),
        )

        val loaded = library.loadRecording("phone-1")
        assertNotNull(loaded)
        val recording = requireNotNull(loaded)
        assertEquals(RecordingSourceType.PHONE_MICROPHONE, recording.sourceType)
        assertEquals("手机录音 2026-10-15 08:00:00", recording.displayName)
        assertEquals(1L, recording.mediaDurationMs)
        assertEquals(1, recording.assets.size)
        assertEquals(AudioAssetRole.CANONICAL_WAV, recording.assets.single().role)
        assertEquals(AudioIntegrityState.VERIFIED, recording.assets.single().integrityState)
        assertEquals(AudioValidationState.VALID, recording.assets.single().formatValidationState)
        assertTrue(recording.derivations.isEmpty())

        // Direct phone-source canonical WAV is formal media, not a reclaimable derived cache.
        assertTrue(library.reclaimableCanonicalCandidates().isEmpty())
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
