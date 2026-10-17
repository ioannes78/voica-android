package io.github.ioannes78.voica.playback

import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock

data class PlaybackOutputDeviceDescriptor(
    val id: Int,
    val type: Int,
    val typeLabel: String,
    val productName: String?,
    val address: String?,
    val isSink: Boolean,
) {
    fun toDiagnosticText(): String =
        buildString {
            append("id:")
            append(id)
            append(" type:")
            append(typeLabel)
            append('(')
            append(type)
            append(')')
            append(" product:")
            append(productName ?: "--")
            if (!address.isNullOrBlank()) {
                append(" address:")
                append(address)
            }
        }
}

data class PlaybackOutputDiagnosticsSnapshot(
    val sinkGeneration: Long = 0L,
    val sinkPhase: String = "IDLE",
    val trackState: String = "n/a",
    val playState: String = "n/a",
    val sampleRateHz: Int? = null,
    val channelCount: Int? = null,
    val encoding: String? = null,
    val bufferBytes: Int? = null,
    val actualRoute: PlaybackOutputDeviceDescriptor? = null,
    val routeGeneration: Long = 0L,
    val routeChanges: Long = 0L,
    val lastRouteReason: String = "n/a",
    val lastRouteChangeElapsedMs: Long? = null,
    val lastEnvironmentEvent: String = "n/a",
    val lastEnvironmentEventElapsedMs: Long? = null,
    val underrunCount: Int = 0,
    val writeCalls: Long = 0L,
    val writeBytes: Long = 0L,
    val zeroWrites: Long = 0L,
    val negativeWrites: Long = 0L,
    val lastWriteFailure: String? = null,
    val timestampAvailable: Boolean = false,
    val lastPresentedFrame: Long = 0L,
    val recoveryAttempts: Int = 0,
    val lastRecoveryResult: String = "NOT_ATTEMPTED",
)

internal class PlaybackOutputDiagnosticsAccumulator(
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var value = PlaybackOutputDiagnosticsSnapshot()

    @Synchronized
    fun reset(): PlaybackOutputDiagnosticsSnapshot {
        value = PlaybackOutputDiagnosticsSnapshot()
        return value
    }

    @Synchronized
    fun sinkCreated(
        trackState: String,
        sampleRateHz: Int,
        channelCount: Int,
        encoding: String,
        bufferBytes: Int,
        initialRoute: PlaybackOutputDeviceDescriptor?,
    ): PlaybackOutputDiagnosticsSnapshot {
        val previous = value
        value = previous.copy(
            sinkGeneration = previous.sinkGeneration + 1L,
            sinkPhase = "CREATED",
            trackState = trackState,
            playState = "STOPPED",
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            encoding = encoding,
            bufferBytes = bufferBytes,
            underrunCount = 0,
            writeCalls = 0L,
            writeBytes = 0L,
            zeroWrites = 0L,
            negativeWrites = 0L,
            lastWriteFailure = null,
            timestampAvailable = false,
            lastPresentedFrame = 0L,
        )
        return actualRoute(initialRoute, "SINK_CREATED")
    }

    @Synchronized
    fun actualRoute(
        route: PlaybackOutputDeviceDescriptor?,
        reason: String,
    ): PlaybackOutputDiagnosticsSnapshot {
        val previousRoute = value.actualRoute
        val changed = previousRoute != route
        if (!changed) {
            value = value.copy(lastRouteReason = reason)
            return value
        }
        value = value.copy(
            actualRoute = route,
            routeGeneration = value.routeGeneration + 1L,
            routeChanges =
                value.routeChanges +
                    if (previousRoute != null) 1L else 0L,
            lastRouteReason = reason,
            lastRouteChangeElapsedMs = elapsedRealtimeMs(),
        )
        return value
    }

    @Synchronized
    fun environmentEvent(reason: String): PlaybackOutputDiagnosticsSnapshot {
        value = value.copy(
            lastEnvironmentEvent = reason,
            lastEnvironmentEventElapsedMs = elapsedRealtimeMs(),
        )
        return value
    }

    @Synchronized
    fun playState(
        sinkPhase: String,
        playState: String,
        trackState: String? = null,
        underrunCount: Int? = null,
    ): PlaybackOutputDiagnosticsSnapshot {
        value = value.copy(
            sinkPhase = sinkPhase,
            playState = playState,
            trackState = trackState ?: value.trackState,
            underrunCount = underrunCount ?: value.underrunCount,
        )
        return value
    }

    @Synchronized
    fun writeResult(
        requestedBytes: Int,
        result: Int,
        underrunCount: Int,
    ): PlaybackOutputDiagnosticsSnapshot {
        value = value.copy(
            writeCalls = value.writeCalls + 1L,
            writeBytes = value.writeBytes + result.coerceAtLeast(0).toLong(),
            zeroWrites = value.zeroWrites + if (result == 0) 1L else 0L,
            negativeWrites = value.negativeWrites + if (result < 0) 1L else 0L,
            underrunCount = underrunCount,
            lastWriteFailure =
                if (result < 0) {
                    "AudioTrack.write result=$result requested=$requestedBytes"
                } else {
                    value.lastWriteFailure
                },
        )
        return value
    }

    @Synchronized
    fun writeFailure(
        detail: String,
        underrunCount: Int,
    ): PlaybackOutputDiagnosticsSnapshot {
        value = value.copy(
            writeCalls = value.writeCalls + 1L,
            underrunCount = underrunCount,
            lastWriteFailure = detail,
        )
        return value
    }

    @Synchronized
    fun position(
        framePosition: Long,
        timestampAvailable: Boolean,
        underrunCount: Int,
    ): PlaybackOutputDiagnosticsSnapshot {
        value = value.copy(
            lastPresentedFrame = framePosition.coerceAtLeast(0L),
            timestampAvailable = timestampAvailable,
            underrunCount = underrunCount,
        )
        return value
    }

    @Synchronized
    fun sinkClosed(): PlaybackOutputDiagnosticsSnapshot {
        value = value.copy(
            sinkPhase = "CLOSED",
            playState = "STOPPED",
        )
        return value
    }

    @Synchronized
    fun snapshot(): PlaybackOutputDiagnosticsSnapshot = value
}

object PlaybackOutputDiagnostics {
    private val accumulator = PlaybackOutputDiagnosticsAccumulator()

    fun resetForTests() {
        accumulator.reset()
    }

    fun sinkCreated(
        trackState: String,
        sampleRateHz: Int,
        channelCount: Int,
        encoding: String,
        bufferBytes: Int,
        initialRoute: AudioDeviceInfo?,
    ) {
        accumulator.sinkCreated(
            trackState = trackState,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
            encoding = encoding,
            bufferBytes = bufferBytes,
            initialRoute = initialRoute.toPlaybackOutputDescriptor(),
        )
    }

    fun actualRoute(device: AudioDeviceInfo?, reason: String) {
        accumulator.actualRoute(device.toPlaybackOutputDescriptor(), reason)
    }

    fun environmentEvent(reason: String) {
        accumulator.environmentEvent(reason)
    }

    fun playState(
        sinkPhase: String,
        playState: String,
        trackState: String? = null,
        underrunCount: Int? = null,
    ) {
        accumulator.playState(
            sinkPhase = sinkPhase,
            playState = playState,
            trackState = trackState,
            underrunCount = underrunCount,
        )
    }

    fun writeResult(requestedBytes: Int, result: Int, underrunCount: Int) {
        accumulator.writeResult(requestedBytes, result, underrunCount)
    }

    fun writeFailure(detail: String, underrunCount: Int) {
        accumulator.writeFailure(detail, underrunCount)
    }

    fun position(framePosition: Long, timestampAvailable: Boolean, underrunCount: Int) {
        accumulator.position(framePosition, timestampAvailable, underrunCount)
    }

    fun sinkClosed() {
        accumulator.sinkClosed()
    }

    fun snapshot(): PlaybackOutputDiagnosticsSnapshot = accumulator.snapshot()
}

fun PlaybackOutputDiagnosticsSnapshot.toP8DiagnosticsText(): String =
    buildString {
        appendLine("[P8 Android playback output]")
        appendLine("sinkGeneration=$sinkGeneration sinkPhase=$sinkPhase")
        appendLine("actualRoute=${actualRoute?.toDiagnosticText() ?: "n/a"}")
        appendLine(
            "routeGeneration=$routeGeneration routeChanges=$routeChanges " +
                "lastRouteReason=$lastRouteReason",
        )
        appendLine("lastRouteChangeElapsedMs=${lastRouteChangeElapsedMs ?: "n/a"}")
        appendLine(
            "lastEnvironmentEvent=$lastEnvironmentEvent " +
                "at=${lastEnvironmentEventElapsedMs ?: "n/a"}",
        )
        appendLine()
        appendLine("[P8 AudioTrack]")
        appendLine("trackState=$trackState playState=$playState")
        appendLine(
            "format=${sampleRateHz ?: "n/a"}Hz/" +
                "${channelCount ?: "n/a"}ch/${encoding ?: "n/a"} " +
                "bufferBytes=${bufferBytes ?: "n/a"}",
        )
        appendLine(
            "underruns=$underrunCount writes=$writeCalls writeBytes=$writeBytes " +
                "zeroWrites=$zeroWrites negativeWrites=$negativeWrites",
        )
        appendLine(
            "timestampAvailable=$timestampAvailable " +
                "lastPresentedFrame=$lastPresentedFrame",
        )
        appendLine("lastWriteFailure=${lastWriteFailure ?: "n/a"}")
        appendLine()
        appendLine("[P9 recovery]")
        append("attempts=$recoveryAttempts result=$lastRecoveryResult")
    }

internal fun AudioDeviceInfo?.toPlaybackOutputDescriptor(): PlaybackOutputDeviceDescriptor? {
    val device = this ?: return null
    return PlaybackOutputDeviceDescriptor(
        id = device.id,
        type = device.type,
        typeLabel = playbackOutputDeviceTypeLabel(device.type),
        productName = device.productName?.toString(),
        address =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.address.takeIf { it.isNotBlank() }
            } else {
                null
            },
        isSink = device.isSink,
    )
}

internal fun playbackOutputDeviceTypeLabel(type: Int): String =
    when {
        type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        type == AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        type == AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        type == AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        type == AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        else -> "TYPE_$type"
    }
