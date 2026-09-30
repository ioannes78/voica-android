package io.github.ioannes78.voica.audio

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

data class WavPcmInfo(
    val audioFormat: Int,
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val byteRate: Long,
    val blockAlign: Int,
    val dataOffset: Long,
    val dataSizeBytes: Long,
) {
    val frameCount: Long
        get() = if (blockAlign > 0) dataSizeBytes / blockAlign else 0L

    val durationUs: Long
        get() = if (sampleRateHz > 0) frameCount * 1_000_000L / sampleRateHz else 0L

    val isPcm: Boolean get() = audioFormat == PCM_FORMAT
    val isCanonical: Boolean
        get() = isPcm &&
            sampleRateHz == CanonicalPcmProfile.SAMPLE_RATE_HZ &&
            channelCount == CanonicalPcmProfile.CHANNEL_COUNT &&
            bitsPerSample == CanonicalPcmProfile.BITS_PER_SAMPLE &&
            blockAlign == CanonicalPcmProfile.BYTES_PER_SAMPLE

    companion object {
        const val PCM_FORMAT = 1
    }
}

sealed interface WavParseResult {
    data class Valid(val info: WavPcmInfo) : WavParseResult
    data class Invalid(val reason: String) : WavParseResult
}

object WavPcmParser {
    fun parse(file: File): WavParseResult {
        if (!file.isFile) return WavParseResult.Invalid("file missing")
        return runCatching {
            RandomAccessFile(file, "r").use(::parse)
        }.getOrElse { error ->
            WavParseResult.Invalid(error.message ?: error::class.java.simpleName)
        }
    }

    fun parse(raf: RandomAccessFile): WavParseResult {
        if (raf.length() < RIFF_HEADER_BYTES) {
            return WavParseResult.Invalid("file shorter than RIFF header")
        }
        raf.seek(0)
        if (raf.readAscii(4) != "RIFF") return WavParseResult.Invalid("missing RIFF")
        raf.readU32Le() // RIFF chunk size; tolerate trailing app metadata.
        if (raf.readAscii(4) != "WAVE") return WavParseResult.Invalid("missing WAVE")

        var fmt: FmtChunk? = null
        var dataOffset: Long? = null
        var dataSize: Long? = null

        while (raf.filePointer + CHUNK_HEADER_BYTES <= raf.length()) {
            val chunkId = raf.readAscii(4)
            val chunkSize = raf.readU32Le()
            val chunkData = raf.filePointer
            val chunkEnd = chunkData + chunkSize
            if (chunkEnd > raf.length()) {
                return WavParseResult.Invalid("$chunkId chunk exceeds file")
            }

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < PCM_FMT_BYTES) {
                        return WavParseResult.Invalid("fmt chunk too short")
                    }
                    fmt = FmtChunk(
                        audioFormat = raf.readU16Le(),
                        channels = raf.readU16Le(),
                        sampleRate = raf.readU32Le().toInt(),
                        byteRate = raf.readU32Le(),
                        blockAlign = raf.readU16Le(),
                        bitsPerSample = raf.readU16Le(),
                    )
                }
                "data" -> {
                    dataOffset = chunkData
                    dataSize = chunkSize
                }
            }

            val paddedEnd = chunkEnd + (chunkSize and 1L)
            if (paddedEnd > raf.length()) {
                return WavParseResult.Invalid("$chunkId padding exceeds file")
            }
            raf.seek(paddedEnd)
            if (fmt != null && dataOffset != null) break
        }

        val format = fmt ?: return WavParseResult.Invalid("missing fmt chunk")
        val pcmOffset = dataOffset ?: return WavParseResult.Invalid("missing data chunk")
        val pcmBytes = dataSize ?: return WavParseResult.Invalid("missing data size")

        if (format.channels <= 0) return WavParseResult.Invalid("invalid channel count")
        if (format.sampleRate <= 0) return WavParseResult.Invalid("invalid sample rate")
        if (format.blockAlign <= 0) return WavParseResult.Invalid("invalid block align")
        if (pcmBytes % format.blockAlign != 0L) {
            return WavParseResult.Invalid("data size is not frame aligned")
        }

        return WavParseResult.Valid(
            WavPcmInfo(
                audioFormat = format.audioFormat,
                sampleRateHz = format.sampleRate,
                channelCount = format.channels,
                bitsPerSample = format.bitsPerSample,
                byteRate = format.byteRate,
                blockAlign = format.blockAlign,
                dataOffset = pcmOffset,
                dataSizeBytes = pcmBytes,
            ),
        )
    }

    private data class FmtChunk(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val byteRate: Long,
        val blockAlign: Int,
        val bitsPerSample: Int,
    )

    private fun RandomAccessFile.readAscii(length: Int): String {
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.US_ASCII)
    }

    private fun RandomAccessFile.readU16Le(): Int {
        val lo = read()
        val hi = read()
        if (lo < 0 || hi < 0) throw EOFException()
        return lo or (hi shl 8)
    }

    private fun RandomAccessFile.readU32Le(): Long {
        var value = 0L
        repeat(4) { shift ->
            val byte = read()
            if (byte < 0) throw EOFException()
            value = value or (byte.toLong() shl (shift * 8))
        }
        return value and 0xFFFF_FFFFL
    }

    private const val RIFF_HEADER_BYTES = 12L
    private const val CHUNK_HEADER_BYTES = 8L
    private const val PCM_FMT_BYTES = 16L
}
