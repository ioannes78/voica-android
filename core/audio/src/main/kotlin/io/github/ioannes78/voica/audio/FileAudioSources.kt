package io.github.ioannes78.voica.audio

import java.io.File
import java.io.RandomAccessFile

class FileSeekableAudioHandle(
    file: File,
) : SeekableAudioHandle {
    private val raf = RandomAccessFile(file, "r")
    override val lengthBytes: Long = raf.length()
    private var closed = false

    @Synchronized
    override fun readAt(
        offset: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int,
    ): Int {
        check(!closed)
        require(offset >= 0L)
        require(targetOffset >= 0 && length >= 0 && targetOffset + length <= target.size)
        if (offset >= lengthBytes) return -1
        raf.seek(offset)
        return raf.read(target, targetOffset, length)
    }

    override fun close() {
        if (!closed) {
            closed = true
            raf.close()
        }
    }
}

class CanonicalWavPcmSource(
    file: File,
) : PcmSource {
    private val raf = RandomAccessFile(file, "r")
    private val info: WavPcmInfo
    private var nextSampleIndex = 0L
    private var remainingBytes: Long
    private var closed = false

    init {
        val parsed = WavPcmParser.parse(raf)
        require(parsed is WavParseResult.Valid) { "invalid WAV" }
        info = parsed.info
        require(info.isCanonical) {
            "WAV is not canonical 16 kHz mono PCM16"
        }
        raf.seek(info.dataOffset)
        remainingBytes = info.dataSizeBytes
    }

    override val sampleRateHz: Int = CanonicalPcmProfile.SAMPLE_RATE_HZ
    override val channelCount: Int = CanonicalPcmProfile.CHANNEL_COUNT

    override suspend fun read(
        target: ShortArray,
        targetOffset: Int,
        maxSamples: Int,
    ): PcmReadResult? {
        check(!closed)
        require(targetOffset >= 0 && maxSamples >= 0 && targetOffset + maxSamples <= target.size)
        if (remainingBytes <= 0L || maxSamples == 0) return null

        val wantedSamples = minOf(maxSamples.toLong(), remainingBytes / 2L).toInt()
        val bytes = ByteArray(wantedSamples * 2)
        raf.readFully(bytes)
        var cursor = 0
        repeat(wantedSamples) { index ->
            val lo = bytes[cursor++].toInt() and 0xFF
            val hi = bytes[cursor++].toInt()
            target[targetOffset + index] = ((hi shl 8) or lo).toShort()
        }

        val start = nextSampleIndex
        nextSampleIndex += wantedSamples
        remainingBytes -= wantedSamples * 2L
        return PcmReadResult(startSampleIndex = start, sampleCount = wantedSamples)
    }

    override fun close() {
        if (!closed) {
            closed = true
            raf.close()
        }
    }
}
