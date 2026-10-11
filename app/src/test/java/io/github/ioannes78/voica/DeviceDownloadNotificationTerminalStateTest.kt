package io.github.ioannes78.voica

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.ioannes78.voica.ble.BleDiagnostics
import io.github.ioannes78.voica.ble.BleScanState
import io.github.ioannes78.voica.ble.DeviceAudioFormat
import io.github.ioannes78.voica.ble.DeviceConnectionState
import io.github.ioannes78.voica.ble.DeviceFileListState
import io.github.ioannes78.voica.ble.DeviceFileOperationType
import io.github.ioannes78.voica.ble.DeviceInfo
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ble.DownloadProgress
import io.github.ioannes78.voica.ble.FileOperationStage
import io.github.ioannes78.voica.ble.FileOperationState
import io.github.ioannes78.voica.ble.FileTransferDiagnostics
import io.github.ioannes78.voica.ble.LocalDeleteResult
import io.github.ioannes78.voica.ble.LocalRecordingArtifact
import io.github.ioannes78.voica.ble.MtuPolicy
import io.github.ioannes78.voica.ble.RangeProbeDiagnostics
import io.github.ioannes78.voica.ble.RecordingDeviceState
import io.github.ioannes78.voica.ble.RemoteDeleteDiagnostics
import io.github.ioannes78.voica.ble.RemoteDeviceFile
import io.github.ioannes78.voica.protocol.RecordingGain
import java.util.concurrent.atomic.AtomicLong
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
class DeviceDownloadNotificationTerminalStateTest {
    @Test
    fun terminalDownloadStateRemainsConnectedAcrossLaterInvalidationsAndNextDownload() {
        val delegate = NotificationFakeDeviceRepository()
        val publishedLabels = mutableListOf<String>()
        val generation = AtomicLong(0L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        ServiceBackedDeviceRepository(
            context = ApplicationProvider.getApplicationContext<Context>(),
            delegate = delegate,
            scope = scope,
            foregroundServiceAcquire = { _, _, label ->
                publishedLabels += label
                generation.incrementAndGet()
            },
            foregroundServiceRelease = {},
        )
        flushUnconfined()

        delegate.fileOperation.value =
            FileOperationState.Active(
                operationId = 41L,
                operation = DeviceFileOperationType.DOWNLOAD,
                stage = FileOperationStage.TRANSFERRING,
                progress = DownloadProgress(receivedBytes = 92L, expectedBytes = 100L),
            )
        flushUnconfined()
        assertEquals("正在下载 · 92%", publishedLabels.last())

        delegate.fileOperation.value =
            FileOperationState.Completed(
                operationId = 41L,
                operation = DeviceFileOperationType.DOWNLOAD,
            )
        flushUnconfined()
        assertEquals("已连接", publishedLabels.last())

        val terminalPublishCount = publishedLabels.size
        delegate.info.value = delegate.info.value.copy(battery = delegate.info.value.battery)
        delegate.recording.value = delegate.recording.value.copy()
        flushUnconfined()

        assertEquals("已连接", publishedLabels.last())
        assertFalse(
            publishedLabels.drop(terminalPublishCount).any { it.startsWith("正在下载") },
        )

        delegate.fileOperation.value =
            FileOperationState.Active(
                operationId = 42L,
                operation = DeviceFileOperationType.DOWNLOAD,
                stage = FileOperationStage.TRANSFERRING,
                progress = DownloadProgress(receivedBytes = 23L, expectedBytes = 100L),
            )
        flushUnconfined()
        assertEquals("正在下载 · 23%", publishedLabels.last())

        delegate.fileOperation.value =
            FileOperationState.Completed(
                operationId = 42L,
                operation = DeviceFileOperationType.DOWNLOAD,
            )
        flushUnconfined()
        assertEquals("已连接", publishedLabels.last())
        assertTrue(publishedLabels.contains("正在下载 · 92%"))
        assertTrue(publishedLabels.contains("正在下载 · 23%"))
        scope.cancel()
    }

    private fun flushUnconfined() {
        runBlocking { yield() }
    }

    private class NotificationFakeDeviceRepository : DeviceRepository {
        val connection =
            MutableStateFlow<DeviceConnectionState>(
                DeviceConnectionState.Ready(
                    address = DEVICE_ADDRESS,
                    negotiatedMtu = 517,
                    capability = MtuPolicy.evaluate(517),
                ),
            )
        val info = MutableStateFlow(DeviceInfo(name = "CB08"))
        val recording = MutableStateFlow(RecordingDeviceState())
        val fileOperation = MutableStateFlow<FileOperationState>(FileOperationState.Idle)

        override val scanState: StateFlow<BleScanState> = MutableStateFlow(BleScanState())
        override val connectionState: StateFlow<DeviceConnectionState> = connection
        override val deviceInfo: StateFlow<DeviceInfo> = info
        override val recordingState: StateFlow<RecordingDeviceState> = recording
        override val deviceFileListState: StateFlow<DeviceFileListState> =
            MutableStateFlow(DeviceFileListState())
        override val fileOperationState: StateFlow<FileOperationState> = fileOperation
        override val fileTransferDiagnostics: StateFlow<FileTransferDiagnostics> =
            MutableStateFlow(FileTransferDiagnostics())
        override val remoteDeleteDiagnostics: StateFlow<RemoteDeleteDiagnostics> =
            MutableStateFlow(RemoteDeleteDiagnostics())
        override val rangeProbeDiagnostics: StateFlow<RangeProbeDiagnostics> =
            MutableStateFlow(RangeProbeDiagnostics())
        override val localRecordings: StateFlow<List<LocalRecordingArtifact>> =
            MutableStateFlow(emptyList())
        override val diagnostics: StateFlow<BleDiagnostics> = MutableStateFlow(BleDiagnostics())

        override fun missingPermissions(): Set<String> = emptySet()
        override fun onPermissionsChanged() = Unit
        override fun startScan() = Unit
        override fun stopScan() = Unit
        override fun connect(address: String) = Unit
        override fun disconnect() = Unit
        override fun setForeground(foreground: Boolean) = Unit
        override suspend fun refreshDeviceInfo() = Unit
        override suspend fun syncTime(): Boolean = true
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
    }
}
