package io.github.ioannes78.voica.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import java.util.UUID

internal class AndroidGattBackend(
    private val gatt: BluetoothGatt,
) : GattOperationStarter {
    @SuppressLint("MissingPermission")
    override fun start(operation: GattOperation): Boolean =
        when (operation) {
            is GattOperation.DiscoverServices -> gatt.discoverServices()
            is GattOperation.EnableNotification -> enableNotification(operation.characteristicUuid)
            is GattOperation.RequestMtu -> gatt.requestMtu(operation.mtu)
            is GattOperation.WriteCharacteristic ->
                writeCharacteristic(operation.characteristicUuid, operation.value)
        }

    fun snapshotShape(): GattShapeSnapshot {
        val service = gatt.getService(BleUuids.AE20_SERVICE)
        val ae21 = service?.getCharacteristic(BleUuids.AE21_WRITE)
        val ae22 = service?.getCharacteristic(BleUuids.AE22_NOTIFY)
        val ae23 = service?.getCharacteristic(BleUuids.AE23_NOTIFY)
        return GattShapeSnapshot(
            ae20Found = service != null,
            ae21Found = ae21 != null,
            ae21Properties = ae21?.properties,
            ae22Found = ae22 != null,
            ae22Properties = ae22?.properties,
            ae22CccdFound = ae22?.getDescriptor(BleUuids.CCCD) != null,
            ae23Found = ae23 != null,
            ae23Properties = ae23?.properties,
            ae23CccdFound = ae23?.getDescriptor(BleUuids.CCCD) != null,
        )
    }

    fun validateShape(shape: GattShapeSnapshot = snapshotShape()): BleError? {
        if (!shape.ae20Found) {
            return BleError(BleErrorCode.SERVICE_MISSING, "AE20 service missing", recoverable = false)
        }
        if (!shape.ae21Found) {
            return BleError(BleErrorCode.CHARACTERISTIC_MISSING, "AE21 missing", recoverable = false)
        }
        if (!shape.ae22Found) {
            return BleError(BleErrorCode.CHARACTERISTIC_MISSING, "AE22 missing", recoverable = false)
        }
        if (!shape.ae23Found) {
            return BleError(BleErrorCode.CHARACTERISTIC_MISSING, "AE23 missing", recoverable = false)
        }

        val ae21WriteNoResponse =
            (shape.ae21Properties ?: 0) and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val ae22Notify =
            (shape.ae22Properties ?: 0) and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val ae23Notify =
            (shape.ae23Properties ?: 0) and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

        if (!ae21WriteNoResponse || !ae22Notify || !ae23Notify) {
            return BleError(
                BleErrorCode.CHARACTERISTIC_PROPERTY_MISMATCH,
                "AE21 writeNoResponse=" + ae21WriteNoResponse +
                    " AE22 notify=" + ae22Notify +
                    " AE23 notify=" + ae23Notify,
                recoverable = false,
            )
        }
        if (!shape.ae22CccdFound || !shape.ae23CccdFound) {
            return BleError(
                BleErrorCode.CHARACTERISTIC_MISSING,
                "CCCD missing: AE22=" + shape.ae22CccdFound +
                    " AE23=" + shape.ae23CccdFound,
                recoverable = false,
            )
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(characteristicUuid: UUID): Boolean {
        val characteristic = gatt
            .getService(BleUuids.AE20_SERVICE)
            ?.getCharacteristic(characteristicUuid)
            ?: return false
        val descriptor = characteristic.getDescriptor(BleUuids.CCCD) ?: return false

        if (!gatt.setCharacteristicNotification(characteristic, true)) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        characteristicUuid: UUID,
        value: ByteArray,
    ): Boolean {
        val characteristic = gatt
            .getService(BleUuids.AE20_SERVICE)
            ?.getCharacteristic(characteristicUuid)
            ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }
}
