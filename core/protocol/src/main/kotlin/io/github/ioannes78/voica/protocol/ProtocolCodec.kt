package io.github.ioannes78.voica.protocol

import java.nio.charset.StandardCharsets

object ProtocolCodec {
    fun buildFrame(sequence: Int, data: ByteArray): ByteArray {
        require(sequence in 0..255) { "sequence 必须位于 0..255" }
        require(data.isNotEmpty()) { "协议 DATA 不能为空" }
        require(data.size <= ProtocolConstants.MAX_DATA_LENGTH) {
            "DATA 长度超过 ${ProtocolConstants.MAX_DATA_LENGTH}"
        }

        val frame = ByteArray(ProtocolConstants.HEADER_LENGTH + data.size)
        frame[0] = ProtocolConstants.MAGIC.toByte()
        frame[1] = sequence.toByte()
        frame[4] = (data.size and 0xFF).toByte()
        frame[5] = ((data.size ushr 8) and 0xFF).toByte()
        data.copyInto(frame, destinationOffset = ProtocolConstants.HEADER_LENGTH)

        val crcInput = ByteArray(2 + data.size)
        crcInput[0] = frame[4]
        crcInput[1] = frame[5]
        data.copyInto(crcInput, destinationOffset = 2)
        val crc = Crc16Xmodem.compute(crcInput)

        frame[2] = (crc and 0xFF).toByte()
        frame[3] = ((crc ushr 8) and 0xFF).toByte()
        return frame
    }

    fun buildCommand(
        sequence: Int,
        type: Int,
        command: Int,
        params: ByteArray = byteArrayOf(),
    ): ByteArray {
        require(type in 0..255)
        require(command in 0..255)
        val data = ByteArray(2 + params.size)
        data[0] = type.toByte()
        data[1] = command.toByte()
        params.copyInto(data, destinationOffset = 2)
        return buildFrame(sequence, data)
    }

    fun buildSyncTime(sequence: Int, time: DeviceTime): ByteArray {
        val params = byteArrayOf(
            (time.year and 0xFF).toByte(),
            ((time.year ushr 8) and 0xFF).toByte(),
            time.month.toByte(),
            time.day.toByte(),
            time.hour.toByte(),
            time.minute.toByte(),
            time.second.toByte(),
        )
        return buildCommand(
            sequence,
            ProtocolConstants.Type.CONTROL,
            ProtocolConstants.Control.SYNC_TIME,
            params,
        )
    }

    fun encodeFilename24(filename: String): ByteArray {
        val output = ByteArray(ProtocolConstants.FILENAME_FIELD_LENGTH)
        var offset = 0

        for (codePoint in filename.codePoints().toArray()) {
            val encoded = String(Character.toChars(codePoint))
                .toByteArray(StandardCharsets.UTF_8)
            if (offset + encoded.size > output.size) break
            encoded.copyInto(output, destinationOffset = offset)
            offset += encoded.size
        }
        return output
    }

    fun buildImportRequest(
        sequence: Int,
        filename: String,
        offset: Long = 0,
    ): ByteArray {
        val params = ByteArray(4 + ProtocolConstants.FILENAME_FIELD_LENGTH)
        ByteCodec.writeU32Le(offset, params, 0)
        encodeFilename24(filename).copyInto(params, destinationOffset = 4)
        return buildCommand(
            sequence,
            ProtocolConstants.Type.FILE,
            ProtocolConstants.File.IMPORT_REQUEST,
            params,
        )
    }

    fun buildSegmentRequest(
        sequence: Int,
        filename: String,
        start: Long,
        end: Long,
    ): ByteArray {
        require(end >= start) { "end 必须大于或等于 start" }
        val params = ByteArray(8 + ProtocolConstants.FILENAME_FIELD_LENGTH)
        ByteCodec.writeU32Le(start, params, 0)
        ByteCodec.writeU32Le(end, params, 4)
        encodeFilename24(filename).copyInto(params, destinationOffset = 8)
        return buildCommand(
            sequence,
            ProtocolConstants.Type.FILE,
            ProtocolConstants.File.IMPORT_SEGMENT,
            params,
        )
    }

    fun buildDeleteOneRequest(sequence: Int, filename: String): ByteArray {
        val params = ByteArray(4 + ProtocolConstants.FILENAME_FIELD_LENGTH)
        encodeFilename24(filename).copyInto(params, destinationOffset = 4)
        return buildCommand(
            sequence,
            ProtocolConstants.Type.FILE,
            ProtocolConstants.File.DELETE_ONE,
            params,
        )
    }

    fun buildGetCapacity(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.CONTROL, ProtocolConstants.Control.GET_CAPACITY)

    fun buildGetBattery(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.CONTROL, ProtocolConstants.Control.GET_BATTERY)

    fun buildGetVersion(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.CONTROL, ProtocolConstants.Control.GET_VERSION)

    fun buildGetAuth(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.CONTROL, ProtocolConstants.Control.GET_AUTH)

    fun buildFileListRequest(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.FILE, ProtocolConstants.File.LIST_REQUEST)

    fun buildRecordStart(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.KEY, ProtocolConstants.Key.APP_RECORD_START, byteArrayOf(1))

    fun buildRecordSave(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.KEY, ProtocolConstants.Key.APP_RECORD_SAVE, byteArrayOf(1))

    fun buildRecordPause(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.KEY, ProtocolConstants.Key.APP_RECORD_PAUSE, byteArrayOf(1))

    fun buildRecordResume(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.KEY, ProtocolConstants.Key.APP_RECORD_RESUME, byteArrayOf(1))

    fun buildGetRecordState(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.KEY, ProtocolConstants.Key.GET_STATE)

    fun buildGetRecordTime(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.KEY, ProtocolConstants.Key.GET_TIME)

    fun buildGetRecordFilename(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.KEY, ProtocolConstants.Key.GET_FILENAME)

    fun buildGetRecordingGain(sequence: Int): ByteArray =
        buildCommand(sequence, ProtocolConstants.Type.KEY, ProtocolConstants.Key.GET_GAIN)

    fun buildSetRecordingGain(sequence: Int, gainValue: Int): ByteArray {
        require(
            gainValue in ProtocolConstants.RecordingGainValue.LOW..
                ProtocolConstants.RecordingGainValue.HIGH,
        ) { "录音增益必须位于 1..3" }
        return buildCommand(
            sequence,
            ProtocolConstants.Type.KEY,
            ProtocolConstants.Key.SET_GAIN,
            byteArrayOf(gainValue.toByte()),
        )
    }
}
