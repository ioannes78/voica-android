package io.github.ioannes78.voica.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class CanonicalWavCommit(
    val file: File,
    val pcmSampleCount: Long,
    val sizeBytes: Long,
    val sha256: String,
    val durationUs: Long,
)

class CanonicalWavWriter(
    private val finalFile: File,
) : AutoCloseable {
    private val partFile = File(finalFile.parentFile, finalFile.name + ".part")
    private val raf: RandomAccessFile
    private var sampleCount = 0L
    private var closed = false
    private var committed = false

    init {
        finalFile.parentFile?.mkdirs()
        if (partFile.exists() && !partFile.delete()) {
            error("cannot remove stale WAV part")
        }
        raf = RandomAccessFile(partFile, "rw")
        raf.setLength(0L)
        writeHeader(raf, 0L)
    }

    fun writePcm16(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        check(!closed) { "writer closed" }
        require(offset >= 0 && length >= 0 && offset + length <= samples.size)
        val bytes = ByteArray(length * 2)
        var out = 0
        for (index in offset until offset + length) {
            val sample = samples[index].toInt()
            bytes[out++] = (sample and 0xFF).toByte()
            bytes[out++] = ((sample ushr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
        sampleCount += length
    }

    fun commit(): CanonicalWavCommit {
        check(!closed) { "writer closed" }
        val pcmBytes = sampleCount * CanonicalPcmProfile.BYTES_PER_SAMPLE
        writeHeader(raf, pcmBytes)
        raf.fd.sync()
        raf.close()
        closed = true

        val expectedLength = WAV_HEADER_BYTES + pcmBytes
        check(partFile.length() == expectedLength) {
            "WAV length mismatch expected=$expectedLength actual=${partFile.length()}"
        }

        try {
            Files.move(
                partFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                partFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        committed = true

        val digest = sha256(finalFile)
        return CanonicalWavCommit(
            file = finalFile,
            pcmSampleCount = sampleCount,
            sizeBytes = finalFile.length(),
            sha256 = digest,
            durationUs = sampleIndexToTimeUs(
                sampleCount,
                CanonicalPcmProfile.SAMPLE_RATE_HZ,
            ),
        )
    }

    override fun close() {
        if (!closed) {
            runCatching { raf.close() }
            closed = true
        }
        if (!committed) partFile.delete()
    }

    private fun writeHeader(target: RandomAccessFile, pcmBytes: Long) {
        require(pcmBytes <= UINT32_MAX - 36L) { "WAV exceeds RIFF32 size" }
        target.seek(0)
        target.write("RIFF".encodeToByteArray())
        target.writeU32Le(36L + pcmBytes)
        target.write("WAVE".encodeToByteArray())
        target.write("fmt ".encodeToByteArray())
        target.writeU32Le(16)
        target.writeU16Le(WavPcmInfo.PCM_FORMAT)
        target.writeU16Le(CanonicalPcmProfile.CHANNEL_COUNT)
        target.writeU32Le(CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong())
        target.writeU32Le(CanonicalPcmProfile.BYTES_PER_SECOND.toLong())
        target.writeU16Le(CanonicalPcmProfile.BYTES_PER_SAMPLE)
        target.writeU16Le(CanonicalPcmProfile.BITS_PER_SAMPLE)
        target.write("data".encodeToByteArray())
        target.writeU32Le(pcmBytes)
    }

    private fun RandomAccessFile.writeU16Le(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun RandomAccessFile.writeU32Le(value: Long) {
        repeat(4) { shift -> write(((value ushr (shift * 8)) and 0xFF).toInt()) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44L
        const val UINT32_MAX = 0xFFFF_FFFFL
        const val BUFFER_SIZE = 64 * 1024
    }
}
