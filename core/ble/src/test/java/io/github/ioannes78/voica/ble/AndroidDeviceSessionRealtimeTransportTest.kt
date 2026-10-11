package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.RealtimeProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AndroidDeviceSessionRealtimeTransportTest {
    @Test
    fun realtimeCommandMappingUsesFrozenProtocolBuilders() {
        val sequence = 0x2A
        assertArrayEquals(
            RealtimeProtocol.buildStart(sequence),
            buildRealtimeCommandFrame(RealtimeControlCommand.START, sequence),
        )
        assertArrayEquals(
            RealtimeProtocol.buildPause(sequence),
            buildRealtimeCommandFrame(RealtimeControlCommand.PAUSE, sequence),
        )
        assertArrayEquals(
            RealtimeProtocol.buildResume(sequence),
            buildRealtimeCommandFrame(RealtimeControlCommand.RESUME, sequence),
        )
        assertArrayEquals(
            RealtimeProtocol.buildStop(sequence),
            buildRealtimeCommandFrame(RealtimeControlCommand.STOP, sequence),
        )
    }
}
