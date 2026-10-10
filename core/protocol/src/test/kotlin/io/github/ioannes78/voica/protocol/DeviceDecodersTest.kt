package io.github.ioannes78.voica.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceDecodersTest {
    @Test
    fun detectsLittleEndianCapacity() {
        val body = ByteArray(8)
        putU32Le(body, 0, 1_000)
        putU32Le(body, 4, 2_000)

        val decoded = DeviceDecoders.decodeCapacity(body)

        assertEquals(DeviceByteOrder.LITTLE_ENDIAN, decoded.byteOrder)
        assertEquals(1_000L, decoded.remaining)
        assertEquals(2_000L, decoded.total)
    }

    @Test
    fun fallsBackToBigEndianCapacityWhenLeIsImplausible() {
        val body = ByteArray(8)
        putU32Be(body, 0, 1_000)
        putU32Be(body, 4, 2_000)

        val decoded = DeviceDecoders.decodeCapacity(body)

        assertEquals(DeviceByteOrder.BIG_ENDIAN, decoded.byteOrder)
        assertEquals(1_000L, decoded.remaining)
        assertEquals(2_000L, decoded.total)
    }

    @Test
    fun shortBodiesAreSafe() {
        assertEquals(0, DeviceDecoders.decodeBattery(byteArrayOf()))
        try {
            DeviceDecoders.decodeRecordTime(byteArrayOf(1, 2))
            throw AssertionError("短录音时间响应必须拒绝，不能伪装成 0/0")
        } catch (_: IllegalArgumentException) {
            // Malformed TIME_RESPONSE is an explicit decode failure.
        }
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Long) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun putU32Le(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
