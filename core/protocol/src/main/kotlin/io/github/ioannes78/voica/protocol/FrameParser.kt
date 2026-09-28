package io.github.ioannes78.voica.protocol

class FrameParser(
    val source: String,
) {
    private var buffer = byteArrayOf()

    var crcErrorCount: Int = 0
        private set

    var invalidLengthCount: Int = 0
        private set

    fun feed(chunk: ByteArray): List<ProtocolFrame> {
        if (chunk.isNotEmpty()) buffer += chunk
        val frames = mutableListOf<ProtocolFrame>()

        while (true) {
            val magicIndex = buffer.indexOfFirst {
                (it.toInt() and 0xFF) == ProtocolConstants.MAGIC
            }

            if (magicIndex < 0) {
                buffer = byteArrayOf()
                break
            }

            if (magicIndex > 0) {
                buffer = buffer.copyOfRange(magicIndex, buffer.size)
            }

            if (buffer.size < ProtocolConstants.HEADER_LENGTH) break

            val sequence = buffer[1].toInt() and 0xFF
            val receivedCrc = ByteCodec.readU16Le(buffer, 2)
            val length = ByteCodec.readU16Le(buffer, 4)

            if (length > ProtocolConstants.MAX_DATA_LENGTH) {
                invalidLengthCount += 1
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }

            val frameLength = ProtocolConstants.HEADER_LENGTH + length
            if (buffer.size < frameLength) break

            val data = buffer.copyOfRange(ProtocolConstants.HEADER_LENGTH, frameLength)
            val crcInput = ByteArray(2 + data.size)
            crcInput[0] = buffer[4]
            crcInput[1] = buffer[5]
            data.copyInto(crcInput, destinationOffset = 2)
            val actualCrc = Crc16Xmodem.compute(crcInput)

            buffer = if (buffer.size == frameLength) {
                byteArrayOf()
            } else {
                buffer.copyOfRange(frameLength, buffer.size)
            }

            if (actualCrc != receivedCrc) {
                crcErrorCount += 1
                continue
            }

            if (data.isNotEmpty()) {
                frames += ProtocolFrame(sequence, data)
            }
        }

        return frames
    }

    fun reset() {
        buffer = byteArrayOf()
        crcErrorCount = 0
        invalidLengthCount = 0
    }
}

class NotificationParsers {
    val ae22 = FrameParser("AE22")
    val ae23 = FrameParser("AE23")
}
