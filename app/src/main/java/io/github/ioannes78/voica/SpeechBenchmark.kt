package io.github.ioannes78.voica

import android.app.Application
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelUseRegistry

/**
 * QA5 compatibility shell.
 *
 * The Stage 13A benchmark product feature was removed after final model selection.
 * This type remains temporarily only to keep the existing QA5 navigation/composable
 * signatures source-compatible until Recording Detail V2 is reworked in QA6.
 * It intentionally exposes no benchmark API and performs no work.
 */
@Suppress("UNUSED_PARAMETER")
class SpeechBenchmarkRunner(
    application: Application,
    pcmSourceResolver: PcmSourceResolver,
    modelManager: ModelManager,
    modelUseRegistry: ModelUseRegistry,
    engineProvider: Stage8TranscriptionEngineProvider,
    localSpeechSettings: () -> LocalSpeechSettings,
)
