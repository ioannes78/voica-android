package io.github.ioannes78.voica.ble

import java.util.LinkedHashMap

class BleScanAccumulator {
    private val devices = LinkedHashMap<String, BleScanDevice>()

    @Synchronized
    fun clear() {
        devices.clear()
    }

    @Synchronized
    fun upsert(device: BleScanDevice): List<BleScanDevice> {
        devices[device.address] = device
        return devices.values
            .sortedWith(
                compareByDescending<BleScanDevice> { it.advertisesAe20 }
                    .thenByDescending { it.likelyQs668 }
                    .thenByDescending { it.rssi },
            )
    }

    @Synchronized
    fun snapshot(): List<BleScanDevice> = devices.values.toList()
}
