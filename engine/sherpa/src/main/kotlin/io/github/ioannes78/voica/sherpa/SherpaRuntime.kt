package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.OnlineRecognizer

data class SherpaRuntimeProbeResult(
    val available: Boolean,
    val runtimeVersion: String,
    val error: String? = null,
)

/**
 * Single ownership point for sherpa-onnx runtime metadata and native-load probing.
 *
 * A successful Android build proves API/AAR resolution. [probeNativeLoad] is
 * intentionally a device/runtime probe because Android arm64 native libraries
 * cannot be executed by the host JVM unit-test process.
 */
object SherpaRuntime {
    const val RUNTIME_ID = "sherpa-onnx"
    const val RUNTIME_VERSION = "1.13.8"
    const val PROVIDER_CPU = "cpu"
    const val DEFAULT_NUM_THREADS = 2

    fun probeNativeLoad(): SherpaRuntimeProbeResult =
        runCatching {
            System.loadLibrary("sherpa-onnx-jni")
            // Keep an explicit compile-time dependency on the Kotlin ASR API as
            // part of the spike; do not instantiate a recognizer without a model.
            OnlineRecognizer::class.java.name
        }.fold(
            onSuccess = {
                SherpaRuntimeProbeResult(
                    available = true,
                    runtimeVersion = RUNTIME_VERSION,
                )
            },
            onFailure = { error ->
                SherpaRuntimeProbeResult(
                    available = false,
                    runtimeVersion = RUNTIME_VERSION,
                    error = error.message ?: error::class.java.simpleName,
                )
            },
        )
}
