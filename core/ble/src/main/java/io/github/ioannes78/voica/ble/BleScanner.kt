package io.github.ioannes78.voica.ble

import kotlinx.coroutines.flow.StateFlow

interface BleScanner {
    val state: StateFlow<BleScanState>
    fun start()
    fun stop()
}
