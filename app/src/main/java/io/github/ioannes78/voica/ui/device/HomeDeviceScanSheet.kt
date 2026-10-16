package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ble.BleScanDevice

/**
 * Presentation-only device picker for homepage scans.
 * Scanning/connecting remains owned by the existing DeviceViewModel/BLE repository.
 */
@Composable
internal fun HomeDeviceScanSheet(
    visible: Boolean,
    isScanning: Boolean,
    compatibleDevices: List<BleScanDevice>,
    otherDevices: List<BleScanDevice>,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    onRescan: () -> Unit,
) {
    if (!visible) return

    var showOtherDevices by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "选择录音设备",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (isScanning) {
                            "正在扫描附近设备"
                        } else {
                            "选择设备后自动返回主页"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isScanning) {
                    TextButton(onClick = onRescan) { Text("重新扫描") }
                }
            }

            if (isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (compatibleDevices.isNotEmpty()) {
                    item(key = "compatible-header") {
                        Text(
                            "兼容录音设备",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    items(compatibleDevices, key = { device -> device.address }) { device ->
                        CompactScanDeviceRow(
                            device = device,
                            onConnect = { address ->
                                onConnect(address)
                                onDismiss()
                            },
                        )
                    }
                } else {
                    item(key = "compatible-empty") {
                        Text(
                            if (isScanning) "正在查找 QS668 / CB08…" else "暂未发现兼容录音设备",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }

                if (otherDevices.isNotEmpty()) {
                    item(key = "other-toggle") {
                        TextButton(
                            onClick = { showOtherDevices = !showOtherDevices },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (showOtherDevices) {
                                    "收起其它蓝牙设备 (${otherDevices.size})"
                                } else {
                                    "其它蓝牙设备 ${otherDevices.size} >"
                                },
                            )
                        }
                    }
                    if (showOtherDevices) {
                        items(otherDevices, key = { device -> device.address }) { device ->
                            CompactScanDeviceRow(
                                device = device,
                                onConnect = { address ->
                                    onConnect(address)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
