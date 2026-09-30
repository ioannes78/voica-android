package io.github.ioannes78.voica.opus

import io.github.ioannes78.voica.audio.OpusDecoder
import io.github.ioannes78.voica.audio.OpusDecoderFactory
import io.github.ioannes78.voica.audio.OpusPacketInfo
import io.github.ioannes78.voica.audio.OpusPacketInspector

class NativeOpusBackend :
    OpusPacketInspector,
    OpusDecoderFactory {

    override val version: String
        get() = NativeOpusBridge.nativeVersion()

    override fun inspect(packet: ByteArray): OpusPacketInfo {
        if (packet.isEmpty() || packet.size > MAX_PACKET_BYTES) {
            return OpusPacketInfo(
                valid = false,
                errorCode = LOCAL_INVALID_PACKET,
            )
        }

        val values = NativeOpusBridge.nativeInspect(packet)
        require(values.size == INSPECTION_FIELDS) {
            "unexpected native Opus inspection result size=${values.size}"
        }
        return OpusPacketInfo(
            valid = values[0] == 1,
            errorCode = values[1].takeUnless { it == 0 },
            toc = values[2].takeUnless { it < 0 },
            channels = values[3].takeUnless { it < 0 },
            frameCount = values[4].takeUnless { it < 0 },
            samplesPerFrame48k = values[5].takeUnless { it < 0 },
            totalSamples48k = values[6].takeUnless { it < 0 },
            bandwidth = values[7].takeUnless { it < 0 },
            payloadOffsetBytes = values[8].takeUnless { it < 0 },
            minFrameBytes = values[9].takeUnless { it < 0 },
            maxFrameBytes = values[10].takeUnless { it < 0 },
        )
    }

    override fun create(
        sampleRateHz: Int,
        channelCount: Int,
    ): OpusDecoder {
        val handle = NativeOpusBridge.nativeCreateDecoder(
            sampleRateHz = sampleRateHz,
            channels = channelCount,
        )
        check(handle != 0L) { "native Opus decoder creation returned null handle" }
        return NativeDecoder(
            initialHandle = handle,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
        )
    }

    private class NativeDecoder(
        initialHandle: Long,
        override val sampleRateHz: Int,
        override val channelCount: Int,
    ) : OpusDecoder {
        private var handle: Long = initialHandle

        @Synchronized
        override fun decode(packet: ByteArray): ShortArray {
            check(handle != 0L) { "Opus decoder is closed" }
            return NativeOpusBridge.nativeDecode(handle, packet)
        }

        @Synchronized
        override fun reset() {
            check(handle != 0L) { "Opus decoder is closed" }
            NativeOpusBridge.nativeResetDecoder(handle)
        }

        @Synchronized
        override fun close() {
            val current = handle
            if (current != 0L) {
                handle = 0L
                NativeOpusBridge.nativeDestroyDecoder(current)
            }
        }
    }

    private companion object {
        const val MAX_PACKET_BYTES = 1275
        const val INSPECTION_FIELDS = 11
        const val LOCAL_INVALID_PACKET = -1000
    }
}

internal object NativeOpusBridge {
    init {
        System.loadLibrary("voica_opus_jni")
    }

    external fun nativeVersion(): String

    external fun nativeInspect(packet: ByteArray): IntArray

    external fun nativeCreateDecoder(
        sampleRateHz: Int,
        channels: Int,
    ): Long

    external fun nativeDecode(
        handle: Long,
        packet: ByteArray,
    ): ShortArray

    external fun nativeResetDecoder(handle: Long)

    external fun nativeDestroyDecoder(handle: Long)
}
