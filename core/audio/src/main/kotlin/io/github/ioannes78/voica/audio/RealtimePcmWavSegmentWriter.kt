package io.github.ioannes78.voica.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class RealtimePcmWavSegmentFinalization(
    val file: File,
    val sampleCount: Long,
    val sizeBytes: Long,
    val sha256: String,
    val hadDiscontinuity: Boolean,
)

/**
 * Incremental canonical PCM16 writer for one Stage 16 realtime transport segment.
 *
 * Media time is never synthesized: only received canonical samples are written. Epoch changes are
 * concatenated because recorder PAUSE contains no media samples. An explicit discontinuity authorizes
 * the next non-contiguous epoch-local sample index without inserting silence and permanently marks the
 * local segment incomplete.
 *
 * The writer uses `<target>.part` until RIFF lengths are patched and fsynced. Callers must run file IO
 * on their own worker; this class is deliberately not a canonical source callback.
 */
class RealtimePcmWavSegmentWriter(
    val targetFile: File,
    val canonicalSessionId: Long,
) : Closeable {
    private val stagingFile = File(targetFile.parentFile, targetFile.name + ".part")
    private var randomAccess: RandomAccessFile? = null
    private var closed = false
    private var finalized = false
    private var activeEpochId: Long? = null
    private var expectedNextSampleIndex = 0L
    private var sampleCount = 0L
    private var hadDiscontinuity = false

    init {
        require(canonicalSessionId > 0L)
        require(targetFile.name.endsWith(".wav", ignoreCase = true)) {
            "realtime canonical segment target must be a .wav file"
        }
        val parent = requireNotNull(targetFile.parentFile) { "realtime WAV target requires a parent directory" }
        require(parent.exists() || parent.mkdirs()) { "unable to create realtime WAV directory" }
        require(!targetFile.exists()) { "realtime WAV target already exists" }
        require(!stagingFile.exists()) { "realtime WAV staging file already exists" }

        randomAccess = RandomAccessFile(stagingFile, "rw").also { file ->
            file.setLength(0L)
            writeHeader(file, dataSizeBytes = 0L)
        }
    }

    fun accept(frame: RealtimeCanonicalPcmFrame) {
        checkActive()
        require(frame.sessionId == canonicalSessionId) {
            "canonical session changed inside one realtime WAV segment"
        }

        val currentEpoch = activeEpochId
        if (currentEpoch == null) {
            activeEpochId = frame.epochId
            expectedNextSampleIndex = frame.startSampleIndex
        } else if (currentEpoch != frame.epochId) {
            require(frame.epochId > currentEpoch) { "canonical epoch must advance monotonically" }
            require(frame.startSampleIndex == 0L) {
                "new canonical epoch must restart epoch-local sample index at zero"
            }
            activeEpochId = frame.epochId
            expectedNextSampleIndex = 0L
        }

        require(frame.startSampleIndex == expectedNextSampleIndex) {
            "canonical PCM gap/overlap without explicit discontinuity: expected=$expectedNextSampleIndex actual=${frame.startSampleIndex}"
        }

        val file = checkNotNull(randomAccess)
        writePcm16Le(file, frame.samples)
        sampleCount = Math.addExact(sampleCount, frame.sampleCount.toLong())
        expectedNextSampleIndex = frame.endSampleIndexExclusive
    }

    fun markDiscontinuity(epochId: Long, resumeAtSampleIndex: Long) {
        checkActive()
        require(epochId > 0L)
        require(resumeAtSampleIndex >= 0L)
        val currentEpoch = activeEpochId
        if (currentEpoch == null) {
            activeEpochId = epochId
        } else {
            require(currentEpoch == epochId) {
                "discontinuity epoch does not match active realtime WAV epoch"
            }
        }
        expectedNextSampleIndex = resumeAtSampleIndex
        hadDiscontinuity = true
    }

    fun finalizeSegment(): RealtimePcmWavSegmentFinalization {
        checkActive()
        val dataBytes = Math.multiplyExact(sampleCount, BYTES_PER_SAMPLE.toLong())
        require(dataBytes <= MAX_RIFF_DATA_BYTES) {
            "realtime WAV exceeds RIFF32 data limit"
        }

        val file = checkNotNull(randomAccess)
        file.seek(0L)
        writeHeader(file, dataBytes)
        file.fd.sync()
        file.close()
        randomAccess = null
        closed = true

        moveAtomicallyBestEffort(stagingFile, targetFile)
        finalized = true
        return RealtimePcmWavSegmentFinalization(
            file = targetFile,
            sampleCount = sampleCount,
            sizeBytes = targetFile.length(),
            sha256 = sha256(targetFile),
            hadDiscontinuity = hadDiscontinuity,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            randomAccess?.close()
        } finally {
            randomAccess = null
            if (!finalized) stagingFile.delete()
        }
    }

    private fun checkActive() {
        check(!closed && !finalized) { "realtime WAV segment writer is closed" }
    }

    private companion object {
        const val HEADER_SIZE = 44L
        const val BYTES_PER_SAMPLE = 2
        const val BITS_PER_SAMPLE = 16
        const val CHANNEL_COUNT = 1
        const val MAX_RIFF_DATA_BYTES = 0xffff_ffffL - 36L

        fun writeHeader(file: RandomAccessFile, dataSizeBytes: Long) {
            val byteRate = CanonicalPcmProfile.SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE
            val blockAlign = CHANNEL_COUNT * BYTES_PER_SAMPLE

            file.writeBytes("RIFF")
            writeIntLe(file, dataSizeBytes + 36L)
            file.writeBytes("WAVE")
            file.writeBytes("fmt ")
            writeIntLe(file, 16L)
            writeShortLe(file, 1)
            writeShortLe(file, CHANNEL_COUNT)
            writeIntLe(file, CanonicalPcmProfile.SAMPLE_RATE_HZ.toLong())
            writeIntLe(file, byteRate.toLong())
            writeShortLe(file, blockAlign)
            writeShortLe(file, BITS_PER_SAMPLE)
            file.writeBytes("data")
            writeIntLe(file, dataSizeBytes)
            check(file.filePointer == HEADER_SIZE)
        }

        fun writePcm16Le(file: RandomAccessFile, samples: ShortArray) {
            val bytes = ByteArray(samples.size * BYTES_PER_SAMPLE)
            var byteIndex = 0
            for (sample in samples) {
                val value = sample.toInt()
                bytes[byteIndex++] = (value and 0xff).toByte()
                bytes[byteIndex++] = ((value ushr 8) and 0xff).toByte()
            }
            file.write(bytes)
        }

        fun writeShortLe(file: RandomAccessFile, value: Int) {
            require(value in 0..0xffff)
            file.write(value and 0xff)
            file.write((value ushr 8) and 0xff)
        }

        fun writeIntLe(file: RandomAccessFile, value: Long) {
            require(value in 0..0xffff_ffffL)
            repeat(4) { shift ->
                file.write(((value ushr (shift * 8)) and 0xff).toInt())
            }
        }

        fun moveAtomicallyBestEffort(source: File, target: File) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath())
            }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}
