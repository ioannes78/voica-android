package io.github.ioannes78.voica.ble

import java.util.UUID

object BleUuids {
    val AE20_SERVICE: UUID = UUID.fromString("0000ae20-0000-1000-8000-00805f9b34fb")
    val AE21_WRITE: UUID = UUID.fromString("0000ae21-0000-1000-8000-00805f9b34fb")
    val AE22_NOTIFY: UUID = UUID.fromString("0000ae22-0000-1000-8000-00805f9b34fb")
    val AE23_NOTIFY: UUID = UUID.fromString("0000ae23-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
