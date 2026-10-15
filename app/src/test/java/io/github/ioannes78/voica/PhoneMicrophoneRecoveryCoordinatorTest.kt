package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.PhoneMicrophoneRecordingRegistration
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneMicrophoneRecoveryCoordinatorTest {
    private lateinit var root: File
    private val registrations = mutableListOf<PhoneMicrophoneRecordingRegistration>()
    private val registered = mutableSetOf<String>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("phone-mic-recovery").toFile()
        registrations.clear()
        registered.clear()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `staging is repaired promoted and registered as recovered recording`() = runBlocking {
        val recordingId = "recording-a"
        val staging = stagingFile(recordingId, "phone_20261015_120102.wav.part")
        writeCanonicalWav(staging, payloadBytes = 2_560, finalizedHeader = false)

        val report = coordinator().reconcileOnStartup()

        assertEquals(listOf(recordingId), report.recoveredRecordingIds)
        assertTrue(report.failures.isEmpty())
        assertFalse(staging.exists())
        val recovered = File(staging.parentFile, "phone_20261015_120102.wav")
        assertTrue(recovered.isFile)
        assertEquals(44L + 2_560L, recovered.length())
        assertEquals(2_560, readLe32(recovered, 40L))
        assertEquals(36 + 2_560, readLe32(recovered, 4L))

        val registration = registrations.single()
        assertEquals(recordingId, registration.recordingId)
        assertEquals("phone_20261015_120102.wav", registration.originalFilename)
        assertEquals("手机录音（异常恢复） 2026-10-15 12:01:02", registration.displayName)
        assertEquals(1_280L * 1_000L / 16_000L, registration.mediaDurationMs)
        assertEquals("phone/$recordingId/phone_20261015_120102.wav", registration.relativePath)
        assertEquals(16_000, registration.sampleRateHz)
        assertEquals(1, registration.channelCount)
    }

    @Test
    fun `odd trailing byte is truncated to PCM16 block alignment`() = runBlocking {
        val recordingId = "recording-odd"
        val staging = stagingFile(recordingId, "phone_20261015_120103.wav.part")
        writeCanonicalWav(staging, payloadBytes = 2_561, finalizedHeader = false)

        val report = coordinator().reconcileOnStartup()

        assertEquals(listOf(recordingId), report.recoveredRecordingIds)
        val recovered = File(staging.parentFile, "phone_20261015_120103.wav")
        assertEquals(44L + 2_560L, recovered.length())
        assertEquals(2_560, readLe32(recovered, 40L))
        assertEquals(80L, registrations.single().mediaDurationMs)
    }

    @Test
    fun `header only staging is discarded without library registration`() = runBlocking {
        val staging = stagingFile("recording-empty", "phone_20261015_120104.wav.part")
        writeCanonicalWav(staging, payloadBytes = 0, finalizedHeader = false)

        val report = coordinator().reconcileOnStartup()

        assertEquals(1, report.discardedEmptyStaging)
        assertTrue(report.recoveredRecordingIds.isEmpty())
        assertTrue(registrations.isEmpty())
        assertFalse(staging.exists())
    }

    @Test
    fun `already registered recording is never recovered twice`() = runBlocking {
        val recordingId = "recording-existing"
        registered += recordingId
        val staging = stagingFile(recordingId, "phone_20261015_120105.wav.part")
        writeCanonicalWav(staging, payloadBytes = 640, finalizedHeader = false)

        val report = coordinator().reconcileOnStartup()

        assertEquals(1, report.skippedRegistered)
        assertTrue(report.recoveredRecordingIds.isEmpty())
        assertTrue(registrations.isEmpty())
        assertTrue(staging.exists())
    }

    @Test
    fun `malformed staging is retained for diagnosis and never registered`() = runBlocking {
        val staging = stagingFile("recording-bad", "phone_20261015_120106.wav.part")
        staging.parentFile.mkdirs()
        staging.writeBytes(ByteArray(128) { 7 })

        val report = coordinator().reconcileOnStartup()

        assertTrue(report.recoveredRecordingIds.isEmpty())
        assertEquals(1, report.failures.size)
        assertTrue(registrations.isEmpty())
        assertTrue(staging.exists())
    }

    private fun coordinator() =
        PhoneMicrophoneRecoveryCoordinator(
            recordingsRoot = root,
            isRegistered = { recordingId -> recordingId in registered },
            registerRecording = { registration ->
                registrations += registration
                registered += registration.recordingId
                registration.recordingId
            },
            nowInstant = { Instant.parse("2026-10-15T05:00:00Z") },
            zoneId = { ZoneId.of("Asia/Shanghai") },
        )

    private fun stagingFile(recordingId: String, name: String): File =
        File(root, "phone/$recordingId/$name")

    private fun writeCanonicalWav(
        file: File,
        payloadBytes: Int,
        finalizedHeader: Boolean,
    ) {
        require(payloadBytes >= 0)
        file.parentFile.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            val declaredPayload = if (finalizedHeader) payloadBytes else 0
            raf.writeBytes("RIFF")
            writeLe32(raf, 36 + declaredPayload)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeLe32(raf, 16)
            writeLe16(raf, 1)
            writeLe16(raf, 1)
            writeLe32(raf, 16_000)
            writeLe32(raf, 32_000)
            writeLe16(raf, 2)
            writeLe16(raf, 16)
            raf.writeBytes("data")
            writeLe32(raf, declaredPayload)
            repeat(payloadBytes) { index -> raf.write(index and 0xff) }
        }
    }

    private fun readLe32(file: File, offset: Long): Int =
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val b0 = raf.read()
            val b1 = raf.read()
            val b2 = raf.read()
            val b3 = raf.read()
            b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

    private fun writeLe16(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff)
        raf.write(value ushr 8 and 0xff)
    }

    private fun writeLe32(raf: RandomAccessFile, value: Int) {
        raf.write(value and 0xff)
        raf.write(value ushr 8 and 0xff)
        raf.write(value ushr 16 and 0xff)
        raf.write(value ushr 24 and 0xff)
    }
}
