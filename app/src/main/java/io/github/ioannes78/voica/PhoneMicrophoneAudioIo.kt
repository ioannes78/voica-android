package io.github.ioannes78.voica

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface PhoneMicrophoneInputRequest {
    data object Auto : PhoneMicrophoneInputRequest

    data class SpecificDevice(
        val deviceId: Int,
    ) : PhoneMicrophoneInputRequest {
        init {
            require(deviceId >= 0)
        }
    }
}

internal data class PhoneMicrophoneAudioDeviceDescriptor(
    val deviceId: Int,
    val deviceType: Int,
    val productName: String,
    val address: String?,
    val isSource: Boolean,
)

internal data class PhoneMicrophoneAudioDeviceInventorySnapshot(
    val generation: Long = 0L,
    val devices: List<PhoneMicrophoneAudioDeviceDescriptor> = emptyList(),
) {
    fun sourceById(deviceId: Int): PhoneMicrophoneAudioDeviceDescriptor? =
        devices.firstOrNull { it.deviceId == deviceId && it.isSource }
}

/**
 * Runtime-only inventory of Android audio input devices.
 *
 * QS668/CB08 GATT devices never enter this registry. This registry represents only devices that the
 * Android audio framework currently exposes as capture sources.
 */
internal class PhoneMicrophoneAudioDeviceRegistry(
    context: Context,
) : Closeable {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private val mutableState =
        MutableStateFlow(
            PhoneMicrophoneAudioDeviceInventorySnapshot(
                devices = currentInputDevices(),
            ),
        )
    val state: StateFlow<PhoneMicrophoneAudioDeviceInventorySnapshot> = mutableState.asStateFlow()

    private var closed = false
    private val callback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                refresh()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                refresh()
            }
        }

    init {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun snapshot(): PhoneMicrophoneAudioDeviceInventorySnapshot = state.value

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
    }

    private fun refresh() {
        synchronized(lock) {
            if (closed) return
            val current = mutableState.value
            val nextDevices = currentInputDevices()
            if (nextDevices == current.devices) return
            mutableState.value =
                PhoneMicrophoneAudioDeviceInventorySnapshot(
                    generation = current.generation + 1L,
                    devices = nextDevices,
                )
        }
    }

    private fun currentInputDevices(): List<PhoneMicrophoneAudioDeviceDescriptor> =
        audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .asSequence()
            .filter { it.isSource }
            .map { it.toPhoneMicrophoneAudioDeviceDescriptor() }
            .sortedWith(
                compareBy<PhoneMicrophoneAudioDeviceDescriptor> { it.deviceType }
                    .thenBy { it.productName }
                    .thenBy { it.deviceId },
            )
            .toList()
}

internal enum class PhoneMicrophoneRouteChangeReason {
    START,
    PLATFORM_REROUTE,
}

internal data class PhoneMicrophoneRouteObservation(
    val route: PhoneMicrophoneRoute?,
    val generation: Long,
    val changeCount: Long,
    val lastChangeReason: PhoneMicrophoneRouteChangeReason?,
    val changed: Boolean,
)

/** Pure tracker so route-generation semantics can be unit tested without Android audio hardware. */
internal class PhoneMicrophoneRouteTracker {
    private var initialized = false
    private var route: PhoneMicrophoneRoute? = null
    private var generation = 0L
    private var changeCount = 0L
    private var lastChangeReason: PhoneMicrophoneRouteChangeReason? = null

    fun reset(initialRoute: PhoneMicrophoneRoute?): PhoneMicrophoneRouteObservation {
        initialized = true
        route = initialRoute
        generation = 0L
        changeCount = 0L
        lastChangeReason = PhoneMicrophoneRouteChangeReason.START
        return observation(changed = false)
    }

    fun observe(nextRoute: PhoneMicrophoneRoute?): PhoneMicrophoneRouteObservation {
        if (!initialized) return reset(nextRoute)
        if (route == nextRoute) return observation(changed = false)

        val oldRoute = route
        route = nextRoute
        generation = Math.addExact(generation, 1L)
        changeCount = Math.addExact(changeCount, 1L)
        lastChangeReason = PhoneMicrophoneRouteChangeReason.PLATFORM_REROUTE
        val result = observation(changed = true)
        PhoneMicrophoneRouteBoundaryHub.emit(
            PhoneMicrophoneRouteBoundary(
                reason =
                    if (nextRoute == null) {
                        PhoneMicrophoneRouteBoundaryReason.ROUTE_LOST
                    } else {
                        PhoneMicrophoneRouteBoundaryReason.ROUTE_CHANGED
                    },
                oldRoute = oldRoute,
                newRoute = nextRoute,
                routeGeneration = generation,
            ),
        )
        return result
    }

    private fun observation(changed: Boolean): PhoneMicrophoneRouteObservation =
        PhoneMicrophoneRouteObservation(
            route = route,
            generation = generation,
            changeCount = changeCount,
            lastChangeReason = lastChangeReason,
            changed = changed,
        )
}

internal data class PhoneMicrophoneSignalSnapshot(
    val sampleCount: Int,
    val peakSample: Int,
    val peakDbfs: Double,
    val rms: Double,
    val rmsDbfs: Double,
    val clippingSamples: Int,
    val clippingRatio: Double,
    val zeroSamples: Int,
    val zeroSampleRatio: Double,
)

internal data class PhoneMicrophoneSignalSummary(
    val frameCount: Long,
    val sampleCount: Long,
    val peakSample: Int,
    val peakDbfs: Double,
    val rms: Double,
    val rmsDbfs: Double,
    /** Lowest observed 80 ms frame RMS. It is a calibration hint, not a lab-grade noise floor. */
    val noiseFloorCandidateDbfs: Double,
    val clippingSamples: Long,
    val clippingRatio: Double,
    val zeroSamples: Long,
    val zeroSampleRatio: Double,
)

/** Read-only PCM diagnostics. This never changes samples. */
internal object PhoneMicrophoneSignalMeter {
    private const val FULL_SCALE = 32_768.0
    private const val DBFS_FLOOR = -120.0

    fun analyze(samples: ShortArray): PhoneMicrophoneSignalSnapshot {
        require(samples.isNotEmpty())

        var peak = 0
        var sumSquares = 0.0
        var clipping = 0
        var zero = 0
        for (sample in samples) {
            val value = sample.toInt()
            val magnitude = abs(value)
            peak = maxOf(peak, magnitude)
            sumSquares += value.toDouble() * value.toDouble()
            if (magnitude >= Short.MAX_VALUE.toInt()) clipping += 1
            if (value == 0) zero += 1
        }

        val rms = sqrt(sumSquares / samples.size.toDouble())
        return PhoneMicrophoneSignalSnapshot(
            sampleCount = samples.size,
            peakSample = peak,
            peakDbfs = amplitudeToDbfs(peak.toDouble()),
            rms = rms,
            rmsDbfs = amplitudeToDbfs(rms),
            clippingSamples = clipping,
            clippingRatio = clipping.toDouble() / samples.size.toDouble(),
            zeroSamples = zero,
            zeroSampleRatio = zero.toDouble() / samples.size.toDouble(),
        )
    }

    fun amplitudeToDbfs(amplitude: Double): Double {
        if (amplitude <= 0.0) return DBFS_FLOOR
        return (20.0 * log10(amplitude / FULL_SCALE)).coerceAtLeast(DBFS_FLOOR)
    }
}

/** Aggregates accepted audible PCM across one capture run for A/B calibration. */
internal class PhoneMicrophoneSignalAccumulator {
    private var frameCount = 0L
    private var sampleCount = 0L
    private var peakSample = 0
    private var sumSquares = 0.0
    private var minimumFrameRmsDbfs = Double.POSITIVE_INFINITY
    private var clippingSamples = 0L
    private var zeroSamples = 0L

    fun add(samples: ShortArray): PhoneMicrophoneSignalSummary {
        val frame = PhoneMicrophoneSignalMeter.analyze(samples)
        frameCount = Math.addExact(frameCount, 1L)
        sampleCount = Math.addExact(sampleCount, frame.sampleCount.toLong())
        peakSample = maxOf(peakSample, frame.peakSample)
        sumSquares += frame.rms * frame.rms * frame.sampleCount.toDouble()
        minimumFrameRmsDbfs = minOf(minimumFrameRmsDbfs, frame.rmsDbfs)
        clippingSamples = Math.addExact(clippingSamples, frame.clippingSamples.toLong())
        zeroSamples = Math.addExact(zeroSamples, frame.zeroSamples.toLong())
        return snapshot()
    }

    fun snapshot(): PhoneMicrophoneSignalSummary {
        require(sampleCount > 0L)
        val rms = sqrt(sumSquares / sampleCount.toDouble())
        return PhoneMicrophoneSignalSummary(
            frameCount = frameCount,
            sampleCount = sampleCount,
            peakSample = peakSample,
            peakDbfs = PhoneMicrophoneSignalMeter.amplitudeToDbfs(peakSample.toDouble()),
            rms = rms,
            rmsDbfs = PhoneMicrophoneSignalMeter.amplitudeToDbfs(rms),
            noiseFloorCandidateDbfs = minimumFrameRmsDbfs,
            clippingSamples = clippingSamples,
            clippingRatio = clippingSamples.toDouble() / sampleCount.toDouble(),
            zeroSamples = zeroSamples,
            zeroSampleRatio = zeroSamples.toDouble() / sampleCount.toDouble(),
        )
    }
}

internal fun AudioDeviceInfo.toPhoneMicrophoneAudioDeviceDescriptor():
    PhoneMicrophoneAudioDeviceDescriptor =
    PhoneMicrophoneAudioDeviceDescriptor(
        deviceId = id,
        deviceType = type,
        productName = productName.toString().ifBlank { "未知音频设备" },
        address =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                address.trim().takeIf { it.isNotEmpty() }
            } else {
                null
            },
        isSource = isSource,
    )