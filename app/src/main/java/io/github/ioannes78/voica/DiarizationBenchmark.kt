package io.github.ioannes78.voica

import android.app.Application
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry

/**
 * QA5 compatibility shell.
 *
 * The Stage 13A diarization benchmark product feature was removed after CAM++
 * was selected. This type remains temporarily only to keep existing QA5
 * navigation/composable signatures source-compatible until Recording Detail V2
 * is reworked in QA6. It intentionally exposes no benchmark API and performs no work.
 */
@Suppress("UNUSED_PARAMETER")
class DiarizationBenchmarkRunner(
    application: Application,
    pcmSourceResolver: PcmSourceResolver,
    modelManager: ModelManager,
    modelUseRegistry: ModelUseRegistry,
    engineProvider: Stage9DiarizationEngineProvider,
    localSpeechSettings: () -> LocalSpeechSettings,
)
