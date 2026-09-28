package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.ProtocolCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRouterTest {
    @Test
    fun ae22AndAe23KeepIndependentPartialBuffers() {
        val router = NotificationRouter()
        val ae22 = ProtocolCodec.buildGetBattery(1)
        val ae23 = ProtocolCodec.buildGetRecordState(2)

        assertTrue(
            router.accept(
                BleUuids.AE22_NOTIFY,
                ae22.copyOfRange(0, 4),
            ).isEmpty(),
        )
        val keyFrames = router.accept(BleUuids.AE23_NOTIFY, ae23)
        assertEquals(1, keyFrames.size)
        assertEquals(NotificationSource.AE23, keyFrames.single().source)

        val controlFrames = router.accept(
            BleUuids.AE22_NOTIFY,
            ae22.copyOfRange(4, ae22.size),
        )
        assertEquals(1, controlFrames.size)
        assertEquals(NotificationSource.AE22, controlFrames.single().source)

        val stats = router.stats()
        assertEquals(1, stats.ae22Frames)
        assertEquals(1, stats.ae23Frames)
    }
}
