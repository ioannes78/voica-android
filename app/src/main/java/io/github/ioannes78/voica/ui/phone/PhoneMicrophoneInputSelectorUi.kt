package io.github.ioannes78.voica.ui.phone

import android.media.AudioDeviceInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.PhoneMicrophoneAudioDeviceDescriptor
import io.github.ioannes78.voica.PhoneMicrophoneInputRequest
import io.github.ioannes78.voica.PhoneMicrophoneInputSelectionRuntime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneMicrophoneInputSelectorRow(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(context.applicationContext) {
        PhoneMicrophoneInputSelectionRuntime.ensureInitialized(context.applicationContext)
    }
    val state by PhoneMicrophoneInputSelectionRuntime.state.collectAsState()
    var sheetVisible by remember { mutableStateOf(false) }

    val selectedLabel =
        when (state.request) {
            PhoneMicrophoneInputRequest.Auto -> "自动"
            is PhoneMicrophoneInputRequest.SpecificDevice ->
                state.selectedDevice?.compactInputLabel() ?: "已断开"
        }

    Row(
        modifier =
            modifier
                .clickable { sheetVisible = true }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "输入：$selectedLabel",
            modifier = Modifier.widthIn(max = 180.dp),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (state.selectedDeviceMissing) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = "选择输入设备",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (!sheetVisible) return
    ModalBottomSheet(onDismissRequest = { sheetVisible = false }) {
        Text(
            "选择输入设备",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Text(
            if (state.selectedDeviceMissing) {
                "手动选择的麦克风当前不可用；开始录音时不会静默切回手机麦克风"
            } else {
                "手机录音与手机实时转写共用此输入设备"
            },
            style = MaterialTheme.typography.bodySmall,
            color =
                if (state.selectedDeviceMissing) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable {
                PhoneMicrophoneInputSelectionRuntime.selectAuto()
                sheetVisible = false
            },
            headlineContent = { Text("自动") },
            supportingContent = { Text("由 Android 自动选择当前输入设备") },
            trailingContent = {
                if (state.request == PhoneMicrophoneInputRequest.Auto) {
                    Icon(Icons.Outlined.Check, contentDescription = "已选择")
                }
            },
        )
        HorizontalDivider()
        if (state.devices.isEmpty()) {
            Text(
                "Android 当前没有报告可用的音频输入设备。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            state.devices.forEach { device ->
                val selected =
                    (state.request as? PhoneMicrophoneInputRequest.SpecificDevice)?.deviceId ==
                        device.deviceId
                ListItem(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (PhoneMicrophoneInputSelectionRuntime.selectDevice(device.deviceId)) {
                            sheetVisible = false
                        }
                    },
                    headlineContent = {
                        Text(device.productName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            "${device.inputTypeLabel()} · Android ID ${device.deviceId}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        if (selected) Icon(Icons.Outlined.Check, contentDescription = "已选择")
                    },
                )
            }
        }
    }
}

private fun PhoneMicrophoneAudioDeviceDescriptor.compactInputLabel(): String =
    when (deviceType) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> "USB 麦克风"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        -> "蓝牙耳机"
        else -> productName.trim().ifBlank { "输入设备" }
    }

private fun PhoneMicrophoneAudioDeviceDescriptor.inputTypeLabel(): String =
    when (deviceType) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机内置麦克风"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机麦克风"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> "USB 麦克风"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙耳机麦克风"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Audio 麦克风"
        else -> "Android 输入设备 · type $deviceType"
    }
