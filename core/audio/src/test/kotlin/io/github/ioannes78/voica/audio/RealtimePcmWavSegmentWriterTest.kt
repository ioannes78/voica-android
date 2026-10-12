package io.github.ioannes78.voica.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimePcmWavSegmentWriterTest {
    @Test
    fun `writer persists canonical pcm across pause epochs with valid wav header`() {
        val root = Files.createTempDirectory("voica-realtime-wav").toFile()
        try {
            val target = File(root, "part01.wav")
            val writer = RealtimePcmWavSegmentWriter(target, canonicalSessionId = 7)
            writer.accept(frame(sessionId = 7, epochId = 1, start = 0, samples = shortArrayOf(1, -2)))
            writer.accept(
                frame(
                    sessionId = 7,
                    epochId = 2,
                    start = 0,
                    samples = shortArrayOf(Short.MAX_VALUE),
                    reason = RealtimeCanonicalEpochStartReason.CONTROL_BOUNDARY,
                ),
            )

            val result = writer.finalizeSegment()
            val bytes = target.readBytes()

            assertEquals(3L, result.sampleCount)
            assertEquals(50L, result.sizeBytes)
            assertFalse(result.hadDiscontinuity)
            assertEquals(64, result.sha256.length)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            assertEquals(16_000, intLe(bytes, 24))
            assertEquals(32_000, intLe(bytes, 28))
            assertEquals(1, shortLe(bytes, 22))
            assertEquals(16, shortLe(bytes, 34))
            assertEquals(6, intLe(bytes, 40))
            assertEquals(listOf(1, 0, 254, 255, 255, 127), bytes.drop(44).map { it.toInt() and 0xff })
            assertFalse(File(root, "part01.wav.part").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `explicit discontinuity never inserts fake silence`() {
        val root = Files.createTempDirectory("voica-realtime-wav-gap").toFile()
        try {
            val target = File(root, "part01.wav")
            val writer = RealtimePcmWavSegmentWriter(target, canonicalSessionId = 3)
            writer.accept(frame(3, 1, 0, shortArrayOf(10, 11)))
            writer.markDiscontinuity(epochId = 1, resumeAtSampleIndex = 10)
            writer.accept(frame(3, 1, 10, shortArrayOf(12, 13)))

            val result = writer.finalizeSegment()

            assertTrue(result.hadDiscontinuity)
            assertEquals(4L, result.sampleCount)
            assertEquals(8, intLe(target.readBytes(), 40))
            assertEquals(52L, target.length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unannounced canonical gap is rejected and close removes staging`() {
        val root = Files.createTempDirectory("voica-realtime-wav-invalid-gap").toFile()
        try {
            val target = File(root, "part01.wav")
            val writer = RealtimePcmWavSegmentWriter(target, canonicalSessionId = 5)
            writer.accept(frame(5, 1, 0, shortArrayOf(1, 2)))

            assertThrows(IllegalArgumentException::class.java) {
                writer.accept(frame(5, 1, 10, shortArrayOf(3, 4)))
            }
            writer.close()

            assertFalse(target.exists())
            assertFalse(File(root, "part01.wav.part").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `existing target is never overwritten`() {
        val root = Files.createTempDirectory("voica-realtime-wav-existing").toFile()
        try {
            val target = File(root, "part01.wav")
            target.writeText("existing")

            assertThrows(IllegalArgumentException::class.java) {
                RealtimePcmWavSegmentWriter(target, canonicalSessionId = 1)
            }
            assertEquals("existing", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun frame(
        sessionId: Long,
        epochId: Long,
        start: Long,
        samples: ShortArray,
        reason: RealtimeCanonicalEpochStartReason? =
            if (start == 0L) RealtimeCanonicalEpochStartReason.SESSION_START else null,
    ) = RealtimeCanonicalPcmFrame(
        sessionId = sessionId,
        epochId = epochId,
        startSampleIndex = start,
        samples = samples,
        epochStartReason = reason,
    )

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun shortLe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
}
