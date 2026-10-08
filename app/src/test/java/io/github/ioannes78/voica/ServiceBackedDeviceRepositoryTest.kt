package io.github.ioannes78.voica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.ble.BleDiagnostics
import io.github.ioannes78.voica.ble.BleScanDevice
import io.github.ioannes78.voica.ble.BleScanState
import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceFileListState
import io.github.ioannes78.voica.ble.DeviceInfo
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.DisconnectReason
import io.github.ioannes78.voica.ble.FileOperationState
import io.github.ioannes78.voica.ble.FileTransferDiagnostics
import io.github.ioannes78.voica.ble.LocalDeleteResult
import io.github.ioannes78.voica.ble.LocalRecordingArtifact
import io.github.ioannes78.voica.ble.RangeProbeDiagnostics
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RemoteDeleteDiagnostics
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import io.github.ioannes78.voica.protocol.RecordingGain
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServiceBackedDeviceRepositoryTest {
    @Test
    fun activeScanStopsThenWaitsForSettlerBeforeSingleConnect() {
        val delegate = FakeDeviceRepository(scanning = true)
        val settleGate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            ServiceBackedDeviceRepository(
                context = ApplicationProvider.getApplicationContext<Context>(),
                delegate = delegate,
                scope = scope,
                scanToConnectSettler = { settleGate.await() },
            )
        delegate.events.clear()

        repository.connect(DEVICE_ADDRESS)

        assertEquals(listOf("stopScan"), delegate.events)
        assertFalse(delegate.events.contains("setForeground:true"))
        settleGate.complete(Unit)
        assertEquals(
            listOf("stopScan", "connect:$DEVICE_ADDRESS"),
            delegate.events,
        )
        scope.cancel()
    }

    @Test
    fun connectAfterScanEndedSkipsSettlerAndConnectsOnce() {
        val delegate = FakeDeviceRepository(scanning = false)
        val settleCalls = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            ServiceBackedDeviceRepository(
                context = ApplicationProvider.getApplicationContext<Context>(),
                delegate = delegate,
                scope = scope,
                scanToConnectSettler = { settleCalls.incrementAndGet() },
            )
        delegate.events.clear()

        repository.connect(DEVICE_ADDRESS)

        assertEquals(0, settleCalls.get())
        assertEquals(listOf("connect:$DEVICE_ADDRESS"), delegate.events)
        scope.cancel()
    }

    @Test
    fun secondTapDuringSettleSupersedesFirstAndOnlyLatestConnects() {
        val delegate = FakeDeviceRepository(scanning = true)
        val settleGate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            ServiceBackedDeviceRepository(
                context = ApplicationProvider.getApplicationContext<Context>(),
                delegate = delegate,
                scope = scope,
                scanToConnectSettler = { settleGate.await() },
            )
        delegate.events.clear()

        repository.connect(DEVICE_ADDRESS)
        repository.connect(SECOND_DEVICE_ADDRESS)

        assertEquals(listOf("stopScan"), delegate.events)
        settleGate.complete(Unit)
        assertEquals(
            listOf("stopScan", "connect:$SECOND_DEVICE_ADDRESS"),
            delegate.events,
        )
        scope.cancel()
    }

    @Test
    fun disconnectDuringSettleCancelsLateConnect() {
        val delegate = FakeDeviceRepository(scanning = true)
        val settleGate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            ServiceBackedDeviceRepository(
                context = ApplicationProvider.getApplicationContext<Context>(),
                delegate = delegate,
                scope = scope,
                scanToConnectSettler = { settleGate.await() },
            )
        delegate.events.clear()

        repository.connect(DEVICE_ADDRESS)
        repository.disconnect()
        settleGate.complete(Unit)

        assertTrue(delegate.events.contains("stopScan"))
        assertTrue(delegate.events.contains("disconnect"))
        assertFalse(delegate.events.any { it.startsWith("connect:") })
        scope.cancel()
    }

    @Test
    fun foregroundCallbackDoesNotReachDelegateWhileScanIsActiveAndIsIdempotentAfterward() {
        val delegate = FakeDeviceRepository(scanning = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            ServiceBackedDeviceRepository(
                context = ApplicationProvider.getApplicationContext<Context>(),
                delegate = delegate,
                scope = scope,
            )
        delegate.events.clear()

        repository.setForeground(true)

        assertTrue(delegate.events.isEmpty())

        delegate.scan.value = delegate.scan.value.copy(isScanning = false)
        repository.setForeground(true)
        repository.setForeground(true)

        assertEquals(listOf("setForeground:true"), delegate.events)
        scope.cancel()
    }

    @Test
    fun reconnectWaitingRefreshesForegroundServiceWithoutReissuingDelegateForeground() {
        val delegate = FakeDeviceRepository(scanning = false, missingPermissions = emptySet())
        val serviceAcquireCount = AtomicInteger(0)
        val serviceReleaseCount = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository =
            ServiceBackedDeviceRepository(
                context = ApplicationProvider.getApplicationContext<Context>(),
                delegate = delegate,
                scope = scope,
                foregroundServiceAcquire = { _, _, _ -> serviceAcquireCount.incrementAndGet().toLong() },
                foregroundServiceRelease = { serviceReleaseCount.incrementAndGet() },
            )
        delegate.events.clear()

        delegate.connection.value =
            DeviceConnectionState.ReconnectWaiting(DEVICE_ADDRESS, attempt = 1, delayMs = 1_000L)
        flushUnconfined()
        delegate.connection.value =
            DeviceConnectionState.ReconnectWaiting(DEVICE_ADDRESS, attempt = 2, delayMs = 2_000L)
        flushUnconfined()
        delegate.connection.value =
            DeviceConnectionState.ReconnectWaiting(DEVICE_ADDRESS, attempt = 3, delayMs = 4_000L)
        flushUnconfined()

        assertEquals(3, serviceAcquireCount.get())
        assertEquals(
            listOf("setForeground:true"),
            delegate.events.filter { it.startsWith("setForeground:") },
        )

        delegate.connection.value =
            DeviceConnectionState.Disconnected(DEVICE_ADDRESS, DisconnectReason.USER)
        flushUnconfined()

        assertEquals(1, serviceReleaseCount.get())
        assertEquals(
            listOf("setForeground:true", "setForeground:false"),
            delegate.events.filter { it.startsWith("setForeground:") },
        )
        scope.cancel()
    }

    private fun flushUnconfined() {
        runBlocking { yield() }
    }

    private class FakeDeviceRepository(
        scanning: Boolean,
        private val missingPermissions: Set<String> = setOf("test.permission"),
    ) : DeviceRepository {
        val events = mutableListOf<String>()
        val scan =
            MutableStateFlow(
                BleScanState(
                    isScanning = scanning,
                    devices =
                        listOf(
                            BleScanDevice(
                                address = DEVICE_ADDRESS,
                                name = "CB08",
                                rssi = -55,
                                lastSeenElapsedMs = 1L,
                                advertisesAe20 = true,
                                likelyQs668 = true,
                            ),
                            BleScanDevice(
                                address = SECOND_DEVICE_ADDRESS,
                                name = "CB08-B",
                                rssi = -65,
                                lastSeenElapsedMs = 2L,
                                advertisesAe20 = true,
                                likelyQs668 = true,
                            ),
                        ),
                ),
            )
        val connection = MutableStateFlow<DeviceConnectionState>(DeviceConnectionState.Idle)

        override val scanState: StateFlow<BleScanState> = scan
        override val connectionState: StateFlow<DeviceConnectionState> = connection
        override val deviceInfo: StateFlow<DeviceInfo> = MutableStateFlow(DeviceInfo())
        override val recordingState: StateFlow<RecordingDeviceState> =
            MutableStateFlow(RecordingDeviceState())
        override val deviceFileListState: StateFlow<DeviceFileListState> =
            MutableStateFlow(DeviceFileListState())
        override val fileOperationState: StateFlow<FileOperationState> =
            MutableStateFlow(FileOperationState.Idle)
        override val fileTransferDiagnostics: StateFlow<FileTransferDiagnostics> =
            MutableStateFlow(FileTransferDiagnostics())
        override val remoteDeleteDiagnostics: StateFlow<RemoteDeleteDiagnostics> =
            MutableStateFlow(RemoteDeleteDiagnostics())
        override val rangeProbeDiagnostics: StateFlow<RangeProbeDiagnostics> =
            MutableStateFlow(RangeProbeDiagnostics())
        override val localRecordings: StateFlow<List<LocalRecordingArtifact>> =
            MutableStateFlow(emptyList())
        override val diagnostics: StateFlow<BleDiagnostics> = MutableStateFlow(BleDiagnostics())

        override fun missingPermissions(): Set<String> = missingPermissions

        override fun onPermissionsChanged() = Unit

        override fun startScan() {
            events += "startScan"
            scan.value = scan.value.copy(isScanning = true)
        }

        override fun stopScan() {
            events += "stopScan"
            scan.value = scan.value.copy(isScanning = false)
        }

        override fun connect(address: String) {
            events += "connect:$address"
        }

        override fun disconnect() {
            events += "disconnect"
        }

        override fun setForeground(foreground: Boolean) {
            events += "setForeground:$foreground"
        }

        override suspend fun refreshDeviceInfo() = Unit

        override suspend fun syncTime(): Boolean = false

        override suspend fun startRecording() = Unit

        override suspend fun pauseRecording() = Unit

        override suspend fun resumeRecording() = Unit

        override suspend fun saveRecording() = Unit

        override suspend fun syncRecordingState() = Unit

        override suspend fun setRecordingGain(gain: RecordingGain) = Unit

        override suspend fun refreshDeviceFiles() = Unit

        override suspend fun downloadDeviceFile(file: RemoteDeviceFile, format: DeviceAudioFormat) = Unit

        override suspend fun cancelDeviceFileDownload() = Unit

        override suspend fun deleteRemoteRecording(file: RemoteDeviceFile) = Unit

        override suspend fun runRangeProbe(file: RemoteDeviceFile) = Unit

        override suspend fun deleteLocalRecording(localId: String): LocalDeleteResult =
            throw UnsupportedOperationException("not used")
    }

    private companion object {
        const val DEVICE_ADDRESS = "D1:A1:C4:00:0A:5E"
        const val SECOND_DEVICE_ADDRESS = "D1:A1:C4:00:0A:5F"
    }
}
