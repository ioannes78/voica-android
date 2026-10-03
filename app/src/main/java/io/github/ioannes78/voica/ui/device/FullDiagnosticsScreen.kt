package io.github.ioannes78.voica.ui.device

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.ioannes78.voica.ble.BleDiagnostics
import io.github.ioannes78.voica.ble.FileTransferDiagnostics
import io.github.ioannes78.voica.ble.RangeProbeDiagnostics
import io.github.ioannes78.voica.ble.RemoteDeleteDiagnostics

private enum class DiagnosticSection {
    CONNECTION,
    RECORDING,
    FILE_LIST,
    TRANSFER,
    DELETE,
    RANGE,
    ERRORS_LOGS,
}

@Composable
fun FullDiagnosticsScreen(
    padding: PaddingValues,
    diagnostics: BleDiagnostics,
    fileTransfer: FileTransferDiagnostics,
    remoteDelete: RemoteDeleteDiagnostics,
    rangeProbe: RangeProbeDiagnostics,
    onBack: () -> Unit,
) {
    var expanded by androidx.compose.runtime.remember {
        mutableStateOf(setOf(DiagnosticSection.CONNECTION))
    }
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "返回",
                    )
                }
                Text(
                    "设备诊断",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 10.dp),
                )
                Column {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("复制完整诊断信息") },
                            leadingIcon = {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                clipboard.setText(
                                    AnnotatedString(
                                        buildDiagnosticsText(
                                            diagnostics,
                                            fileTransfer,
                                            remoteDelete,
                                            rangeProbe,
                                        ),
                                    ),
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("全部展开") },
                            onClick = {
                                menuExpanded = false
                                expanded = DiagnosticSection.entries.toSet()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("全部收起") },
                            onClick = {
                                menuExpanded = false
                                expanded = emptySet()
                            },
                        )
                    }
                }
            }
        }

        item {
            DiagnosticExpandableSection(
                title = "连接与 GATT",
                expanded = DiagnosticSection.CONNECTION in expanded,
                onToggle = {
                    expanded = expanded.toggle(DiagnosticSection.CONNECTION)
                },
            ) {
                DiagnosticLine("Session", diagnostics.sessionId)
                DiagnosticLine("GATT", diagnostics.gattStage)
                DiagnosticLine("Requested MTU", diagnostics.requestedMtu)
                DiagnosticLine("Actual MTU", diagnostics.negotiatedMtu)
                DiagnosticLine("MTU tier", diagnostics.mtuCapability.tier)
                DiagnosticLine("36B atomic", diagnostics.mtuCapability.atomic36Supported)
                DiagnosticLine("168B data", diagnostics.mtuCapability.data168Supported)
                DiagnosticLine("AE20 found", diagnostics.shape.ae20Found)
                DiagnosticLine("AE21 found", diagnostics.shape.ae21Found)
                DiagnosticLine("AE21 properties", diagnostics.shape.ae21Properties)
                DiagnosticLine("AE22 found", diagnostics.shape.ae22Found)
                DiagnosticLine("AE22 properties", diagnostics.shape.ae22Properties)
                DiagnosticLine("AE22 CCCD", diagnostics.shape.ae22CccdFound)
                DiagnosticLine("AE22 subscribed", diagnostics.ae22Subscribed)
                DiagnosticLine("AE23 found", diagnostics.shape.ae23Found)
                DiagnosticLine("AE23 properties", diagnostics.shape.ae23Properties)
                DiagnosticLine("AE23 CCCD", diagnostics.shape.ae23CccdFound)
                DiagnosticLine("AE23 subscribed", diagnostics.ae23Subscribed)
                DiagnosticLine("Queue active", diagnostics.queue.activeOperation)
                DiagnosticLine("Queue wait", diagnostics.queue.waitingCount)
                DiagnosticLine("Queue closed", diagnostics.queue.closed)
                DiagnosticLine("Last GATT status", diagnostics.lastGattStatus)
                DiagnosticLine("Reconnect attempt", diagnostics.reconnectAttempt)
                DiagnosticLine("AE22 frames", diagnostics.notifications.ae22Frames)
                DiagnosticLine("AE23 frames", diagnostics.notifications.ae23Frames)
                DiagnosticLine("AE22 CRC", diagnostics.notifications.ae22CrcErrors)
                DiagnosticLine("AE23 CRC", diagnostics.notifications.ae23CrcErrors)
                DiagnosticLine("AE22 invalid length", diagnostics.notifications.ae22InvalidLengths)
                DiagnosticLine("AE23 invalid length", diagnostics.notifications.ae23InvalidLengths)
                DiagnosticLine(
                    "Unknown notifications",
                    diagnostics.notifications.unknownCharacteristicNotifications,
                )
                DiagnosticLine(
                    "Last TX",
                    listOf(
                        diagnostics.lastTxType,
                        diagnostics.lastTxCommand,
                        diagnostics.lastTxSequence,
                    ).joinToString("/"),
                )
            }
        }

        item {
            DiagnosticExpandableSection(
                title = "录音协议",
                expanded = DiagnosticSection.RECORDING in expanded,
                onToggle = {
                    expanded = expanded.toggle(DiagnosticSection.RECORDING)
                },
            ) {
                val r = diagnostics.recording
                DiagnosticLine("Recording state", r.statusDecoded)
                DiagnosticLine("Recording raw", r.statusRaw)
                DiagnosticLine("Freshness", r.freshness)
                DiagnosticLine("Duration", r.durationSeconds)
                DiagnosticLine("Current bytes", r.currentSizeBytes)
                DiagnosticLine("Filename", r.filename)
                DiagnosticLine("Gain", r.gainDecoded)
                DiagnosticLine("Gain raw", r.gainRaw)
                DiagnosticLine(
                    "RX source/cmd",
                    listOf(r.lastResponseSource, r.lastResponseCommand).joinToString("/"),
                )
                DiagnosticLine(
                    "REQ/RSP seq",
                    listOf(r.lastRequestSequence, r.lastResponseSequence).joinToString("/"),
                )
                DiagnosticLine(
                    "RX latency",
                    r.lastResponseLatencyMs?.let { it.toString() + " ms" },
                )
                DiagnosticLine("Last sync", r.lastSyncReason)
                DiagnosticLine("Command result", r.lastCommandResultCode)
                DiagnosticLine("Polling", r.pollingActive)
                DiagnosticLine(
                    "Hardware event",
                    r.lastHardwareEvent?.let {
                        it.kind.name + "/" + it.source +
                            "/cmd=" + it.command + "/seq=" + it.sequence
                    },
                )
                DiagnosticLine("Decode error", r.lastDecodeError)
                DiagnosticLine("Recording error", r.lastOperationError)
            }
        }

        item {
            DiagnosticExpandableSection(
                title = "设备文件列表",
                expanded = DiagnosticSection.FILE_LIST in expanded,
                onToggle = {
                    expanded = expanded.toggle(DiagnosticSection.FILE_LIST)
                },
            ) {
                val f = diagnostics.fileList
                DiagnosticLine("File session", f.sessionId)
                DiagnosticLine("Transport session", f.transportSessionId)
                DiagnosticLine("File request seq", f.requestSequence)
                DiagnosticLine("File frames", f.dataFrameCount)
                DiagnosticLine(
                    "Declared/parsed",
                    f.declaredEntryCount.toString() + "/" + f.parsedEntryCount,
                )
                DiagnosticLine("Data RX source", f.lastDataNotificationSource)
                DiagnosticLine("Done RX source", f.listDoneNotificationSource)
                DiagnosticLine("Last data body", f.lastDataBodySize)
                DiagnosticLine("Filename field", f.lastFilenameFieldLength)
                DiagnosticLine("Done body", f.listDoneBodySize)
                DiagnosticLine("List done", f.receivedListDone)
                DiagnosticLine("Newest raw filename", f.newestRawFilename)
                DiagnosticLine("Newest resolved filename", f.newestResolvedFilename)
                DiagnosticLine("Newest rawTimeValue", f.newestRawTimeValue)
                DiagnosticLine("Newest size bytes", f.newestSizeBytes)
                DiagnosticLine("Newest resolution", f.newestFilenameResolution)
                DiagnosticLine("Completion", f.completionReason)
                DiagnosticLine("Session duration", f.durationMs?.let { it.toString() + " ms" })
                DiagnosticLine("File malformed", f.lastMalformedReason)
                DiagnosticLine("File error", f.lastOperationError)
            }
        }

        item {
            DiagnosticExpandableSection(
                title = "文件传输",
                expanded = DiagnosticSection.TRANSFER in expanded,
                onToggle = {
                    expanded = expanded.toggle(DiagnosticSection.TRANSFER)
                },
            ) {
                DiagnosticLine("Operation", fileTransfer.operationId)
                DiagnosticLine("Transport session", fileTransfer.transportSessionId)
                DiagnosticLine("Remote identity", fileTransfer.remoteIdentity)
                DiagnosticLine("List filename", fileTransfer.listFilename)
                DiagnosticLine("Request filename", fileTransfer.requestFilename)
                DiagnosticLine("Requested format", fileTransfer.requestedFormat)
                DiagnosticLine("Request filename bytes", fileTransfer.requestFilenameByteLength)
                DiagnosticLine("Request frame bytes", fileTransfer.requestFrameLength)
                DiagnosticLine("Request seq", fileTransfer.requestSequence)
                DiagnosticLine("Actual filename", fileTransfer.actualTransferFilename)
                DiagnosticLine("START source", fileTransfer.startSource)
                DiagnosticLine("DATA source", fileTransfer.lastDataSource)
                DiagnosticLine("END source", fileTransfer.endSource)
                DiagnosticLine("DATA frames", fileTransfer.dataFrameCount)
                DiagnosticLine(
                    "Expected/received",
                    (fileTransfer.expectedBytes?.toString() ?: "--") +
                        "/" + fileTransfer.receivedBytes,
                )
                DiagnosticLine("First data", fileTransfer.firstDataPrefixHex)
                DiagnosticLine("Container", fileTransfer.detectedContainer)
                DiagnosticLine("Remote status", fileTransfer.remoteStatusCode)
                DiagnosticLine(
                    "Transfer error",
                    fileTransfer.lastError?.let {
                        it.code.name + (it.detail?.let { detail -> ": " + detail } ?: "")
                    },
                )
            }
        }

        item {
            DiagnosticExpandableSection(
                title = "设备删除",
                expanded = DiagnosticSection.DELETE in expanded,
                onToggle = {
                    expanded = expanded.toggle(DiagnosticSection.DELETE)
                },
            ) {
                DiagnosticLine("Operation", remoteDelete.operationId)
                DiagnosticLine("Remote identity", remoteDelete.remoteIdentity)
                DiagnosticLine("Payload", remoteDelete.payloadStrategy)
                DiagnosticLine("Request body bytes", remoteDelete.requestBodyLength)
                DiagnosticLine("Response source", remoteDelete.responseSource)
                DiagnosticLine("Status", remoteDelete.responseStatusCode)
                DiagnosticLine("Response body", remoteDelete.responseBodyHex)
                DiagnosticLine(
                    "Latency",
                    remoteDelete.responseLatencyMs?.let { it.toString() + " ms" },
                )
                DiagnosticLine("Verification", remoteDelete.verificationResult)
                DiagnosticLine("Outcome unknown", remoteDelete.outcomeUnknown)
                DiagnosticLine(
                    "Delete error",
                    remoteDelete.lastError?.let {
                        it.code.name + (it.detail?.let { detail -> ": " + detail } ?: "")
                    },
                )
            }
        }

        item {
            DiagnosticExpandableSection(
                title = "Range Probe",
                expanded = DiagnosticSection.RANGE in expanded,
                onToggle = {
                    expanded = expanded.toggle(DiagnosticSection.RANGE)
                },
            ) {
                DiagnosticLine("Operation", rangeProbe.operationId)
                DiagnosticLine("Remote identity", rangeProbe.remoteIdentity)
                DiagnosticLine(
                    "Range",
                    if (rangeProbe.startOffset != null &&
                        rangeProbe.requestedEnd != null
                    ) {
                        rangeProbe.startOffset.toString() +
                            ".." + rangeProbe.requestedEnd
                    } else {
                        null
                    },
                )
                DiagnosticLine("Received", rangeProbe.receivedBytes)
                DiagnosticLine("Actual filename", rangeProbe.actualTransferFilename)
                DiagnosticLine("First data", rangeProbe.firstDataPrefixHex)
                DiagnosticLine("Matches local", rangeProbe.matchesLocalBytes)
                DiagnosticLine("End semantics", rangeProbe.inferredEndSemantics)
                DiagnosticLine("Remote status", rangeProbe.remoteStatusCode)
                DiagnosticLine(
                    "Range error",
                    rangeProbe.lastError?.let {
                        it.code.name + (it.detail?.let { detail -> ": " + detail } ?: "")
                    },
                )
            }
        }

        item {
            DiagnosticExpandableSection(
                title = "错误与日志",
                expanded = DiagnosticSection.ERRORS_LOGS in expanded,
                onToggle = {
                    expanded = expanded.toggle(DiagnosticSection.ERRORS_LOGS)
                },
            ) {
                DiagnosticLine(
                    "Last BLE error",
                    diagnostics.lastError?.let {
                        it.code.name + (it.detail?.let { detail -> ": " + detail } ?: "")
                    },
                )
                if (diagnostics.logs.isEmpty()) {
                    DiagnosticLine("Recent logs", "无")
                } else {
                    diagnostics.logs.takeLast(50).forEachIndexed { index, log ->
                        DiagnosticLine("Log " + (index + 1), log)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticExpandableSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            trailingContent = {
                Icon(
                    if (expanded) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ExpandMore
                    },
                    contentDescription = null,
                )
            },
            modifier = Modifier.clickable(onClick = onToggle),
        )
        if (expanded) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                content()
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun DiagnosticLine(
    label: String,
    value: Any?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value?.toString() ?: "--",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.35f),
        )
    }
}

private fun Set<DiagnosticSection>.toggle(
    section: DiagnosticSection,
): Set<DiagnosticSection> =
    if (section in this) this - section else this + section

private fun buildDiagnosticsText(
    diagnostics: BleDiagnostics,
    fileTransfer: FileTransferDiagnostics,
    remoteDelete: RemoteDeleteDiagnostics,
    rangeProbe: RangeProbeDiagnostics,
): String =
    buildString {
        appendLine("Voica BLE diagnostics")
        appendLine("Session: " + diagnostics.sessionId)
        appendLine("GATT: " + diagnostics.gattStage)
        appendLine("Requested MTU: " + diagnostics.requestedMtu)
        appendLine("Actual MTU: " + (diagnostics.negotiatedMtu ?: "--"))
        appendLine("MTU tier: " + diagnostics.mtuCapability.tier)
        appendLine("36B atomic: " + diagnostics.mtuCapability.atomic36Supported)
        appendLine("168B data: " + diagnostics.mtuCapability.data168Supported)
        appendLine("AE20: " + diagnostics.shape.ae20Found)
        appendLine("AE21: " + diagnostics.shape.ae21Found + "/" + diagnostics.shape.ae21Properties)
        appendLine("AE22: " + diagnostics.shape.ae22Found + "/" + diagnostics.ae22Subscribed)
        appendLine("AE23: " + diagnostics.shape.ae23Found + "/" + diagnostics.ae23Subscribed)
        appendLine(
            "Queue: " + (diagnostics.queue.activeOperation ?: "idle") +
                " wait=" + diagnostics.queue.waitingCount,
        )
        appendLine("Reconnect: " + diagnostics.reconnectAttempt)
        appendLine("Last GATT status: " + (diagnostics.lastGattStatus ?: "--"))
        appendLine(
            "AE22 frames/CRC/invalid: " +
                diagnostics.notifications.ae22Frames + "/" +
                diagnostics.notifications.ae22CrcErrors + "/" +
                diagnostics.notifications.ae22InvalidLengths,
        )
        appendLine(
            "AE23 frames/CRC/invalid: " +
                diagnostics.notifications.ae23Frames + "/" +
                diagnostics.notifications.ae23CrcErrors + "/" +
                diagnostics.notifications.ae23InvalidLengths,
        )
        appendLine(
            "Last TX: " + diagnostics.lastTxType + "/" +
                diagnostics.lastTxCommand + "/" + diagnostics.lastTxSequence,
        )
        appendLine()
        appendLine("[Recording]")
        appendLine(diagnostics.recording.toString())
        appendLine()
        appendLine("[File list]")
        appendLine(diagnostics.fileList.toString())
        appendLine()
        appendLine("[File transfer]")
        appendLine(fileTransfer.toString())
        appendLine()
        appendLine("[Remote delete]")
        appendLine(remoteDelete.toString())
        appendLine()
        appendLine("[Range probe]")
        appendLine(rangeProbe.toString())
        appendLine()
        appendLine("[Last BLE error]")
        appendLine(diagnostics.lastError?.toString() ?: "--")
        appendLine()
        appendLine("[Recent logs]")
        diagnostics.logs.takeLast(50).forEach(::appendLine)
    }
