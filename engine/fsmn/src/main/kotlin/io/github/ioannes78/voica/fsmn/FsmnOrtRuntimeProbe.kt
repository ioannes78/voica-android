package io.github.ioannes78.voica.fsmn

/**
 * Stage 16B candidate-only probe for the single ONNX Runtime already packaged by sherpa-onnx.
 *
 * This module deliberately does not package another libonnxruntime.so. The native probe resolves
 * the APK's existing library dynamically and verifies that its C API is compatible with the exact
 * 1.28.2 headers pinned by sherpa-onnx v1.13.8.
 */
object FsmnOrtRuntimeProbe {
    const val EXPECTED_ONNX_RUNTIME_VERSION = "1.28.2"

    data class Result(
        val actualVersion: String,
        val expectedVersion: String = EXPECTED_ONNX_RUNTIME_VERSION,
    ) {
        val compatible: Boolean
            get() = actualVersion == expectedVersion
    }

    fun probe(): Result {
        NativeLibrary.ensureLoaded()
        return Result(actualVersion = nativeOrtVersion())
    }

    internal fun evaluate(actualVersion: String): Result =
        Result(actualVersion = actualVersion.trim())

    private external fun nativeOrtVersion(): String

    private object NativeLibrary {
        init {
            System.loadLibrary("voica_fsmn_candidate_jni")
        }

        fun ensureLoaded() = Unit
    }
}
