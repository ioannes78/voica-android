package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.RawDeviceFileEntry

data class FileListSessionSnapshot(
    val fileSessionId: Long,
    val transportSessionId: Long,
    val deviceAddress: String,
    val requestSequence: Int?,
    val dataFrameCount: Int,
    val declaredEntryCount: Int,
    val entries: List<RawDeviceFileEntry>,
    val lastDataNotificationSource: NotificationSource?,
    val listDoneNotificationSource: NotificationSource?,
    val lastDataBodySize: Int?,
    val listDoneBodySize: Int?,
    val lastFilenameFieldLength: Int?,
    val receivedListDone: Boolean,
    val startedAtMs: Long,
    val endedAtMs: Long?,
)

sealed interface FileListSessionResult {
    data class DataAccepted(
        val snapshot: FileListSessionSnapshot,
    ) : FileListSessionResult

    data class Completed(
        val snapshot: FileListSessionSnapshot,
    ) : FileListSessionResult

    data class Failed(
        val snapshot: FileListSessionSnapshot,
        val error: FileListError,
        val completionReason: FileListCompletionReason,
    ) : FileListSessionResult

    data class Ignored(
        val reason: String,
    ) : FileListSessionResult
}

class FileListSessionCoordinator {
    private data class ActiveSession(
        val fileSessionId: Long,
        val transportSessionId: Long,
        val deviceAddress: String,
        var requestSequence: Int? = null,
        var dataFrameCount: Int = 0,
        var declaredEntryCount: Int = 0,
        val entries: MutableList<RawDeviceFileEntry> = mutableListOf(),
        var lastDataNotificationSource: NotificationSource? = null,
        var listDoneNotificationSource: NotificationSource? = null,
        var lastDataBodySize: Int? = null,
        var listDoneBodySize: Int? = null,
        var lastFilenameFieldLength: Int? = null,
        var receivedListDone: Boolean = false,
        val startedAtMs: Long,
    )

    private var nextSessionId = 1L
    private var active: ActiveSession? = null

    fun start(
        transportSessionId: Long,
        deviceAddress: String,
        startedAtMs: Long,
    ): Long {
        val id = nextSessionId++
        active = ActiveSession(
            fileSessionId = id,
            transportSessionId = transportSessionId,
            deviceAddress = deviceAddress,
            startedAtMs = startedAtMs,
        )
        return id
    }

    fun noteRequestSequence(
        fileSessionId: Long,
        requestSequence: Int,
    ) {
        val current = active ?: return
        if (current.fileSessionId == fileSessionId) {
            current.requestSequence = requestSequence
        }
    }

    fun snapshot(fileSessionId: Long): FileListSessionSnapshot? {
        val current = active ?: return null
        if (current.fileSessionId != fileSessionId) return null
        return current.snapshot(endedAtMs = null)
    }

    fun accept(
        event: FileListFrameEvent,
        transportSessionId: Long,
        nowMs: Long,
    ): FileListSessionResult {
        val current = active
            ?: return FileListSessionResult.Ignored("no active file-list session")
        if (current.transportSessionId != transportSessionId) {
            return FileListSessionResult.Ignored("transport session mismatch")
        }

        return when (event) {
            is FileListFrameEvent.Data -> {
                current.dataFrameCount += 1
                current.declaredEntryCount += event.chunk.declaredCount
                current.entries += event.chunk.entries
                current.lastDataNotificationSource = event.source
                current.lastDataBodySize = event.chunk.bodySize
                current.lastFilenameFieldLength = event.chunk.filenameFieldLength
                FileListSessionResult.DataAccepted(current.snapshot(endedAtMs = null))
            }

            is FileListFrameEvent.Done -> {
                current.receivedListDone = true
                current.listDoneNotificationSource = event.source
                current.listDoneBodySize = event.bodySize
                val snapshot = current.snapshot(endedAtMs = nowMs)
                active = null
                FileListSessionResult.Completed(snapshot)
            }

            is FileListFrameEvent.Malformed -> {
                current.lastDataNotificationSource = event.source
                current.lastDataBodySize = event.bodySize
                val snapshot = current.snapshot(endedAtMs = nowMs)
                active = null
                FileListSessionResult.Failed(
                    snapshot = snapshot,
                    error = FileListError(
                        FileListErrorCode.MALFORMED_PAYLOAD,
                        event.reason,
                    ),
                    completionReason = FileListCompletionReason.MALFORMED_PAYLOAD,
                )
            }
        }
    }

    fun cancel(nowMs: Long): FileListSessionSnapshot? {
        val current = active ?: return null
        val snapshot = current.snapshot(endedAtMs = nowMs)
        active = null
        return snapshot
    }

    private fun ActiveSession.snapshot(endedAtMs: Long?): FileListSessionSnapshot =
        FileListSessionSnapshot(
            fileSessionId = fileSessionId,
            transportSessionId = transportSessionId,
            deviceAddress = deviceAddress,
            requestSequence = requestSequence,
            dataFrameCount = dataFrameCount,
            declaredEntryCount = declaredEntryCount,
            entries = entries.toList(),
            lastDataNotificationSource = lastDataNotificationSource,
            listDoneNotificationSource = listDoneNotificationSource,
            lastDataBodySize = lastDataBodySize,
            listDoneBodySize = listDoneBodySize,
            lastFilenameFieldLength = lastFilenameFieldLength,
            receivedListDone = receivedListDone,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
        )
}
