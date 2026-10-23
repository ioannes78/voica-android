package io.github.ioannes78.voica.transcript

import io.github.ioannes78.voica.model.ModelDescriptor
import java.io.File

/**
 * Runtime-neutral bridge for VAD engines that are packaged outside the production Sherpa adapter.
 *
 * The App may discover an implementation reflectively in candidate builds while formal release
 * remains free of a compile-time dependency on that candidate runtime.
 */
interface ExternalVadEngineProvider {
    fun open(
        model: ModelDescriptor,
        installedDirectory: File,
        numThreads: Int,
        maxSpeechDurationSeconds: Float,
    ): VadEngine
}
