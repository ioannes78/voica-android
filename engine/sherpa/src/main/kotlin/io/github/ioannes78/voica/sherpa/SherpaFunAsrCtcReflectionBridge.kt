package io.github.ioannes78.voica.sherpa

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Optional ABI bridge to the Stage 16B patched sherpa-onnx AAR.
 *
 * The production dependency is still the stock v1.13.8 AAR while the native CTC patch is gated.
 * Referencing future Kotlin methods directly would therefore make revision 1 uncompilable. This
 * narrow reflection bridge lets the current app compile unchanged and makes revision 2 fail closed
 * unless the patched AAR exposes both required methods with stable names.
 *
 * Required patched OfflineRecognizer methods:
 *   getFunAsrNanoCtcScores(stream: OfflineStream, targetIds: IntArray): FloatArray
 *   discardFunAsrNanoCtcState(stream: OfflineStream): Unit
 *
 * Score layout is row-major [frame][blank, target0, target1, ...]. Duplicate target ids remain
 * duplicate columns so repeated-symbol CTC state transitions retain their target-position identity.
 */
internal class SherpaFunAsrCtcReflectionBridge(
    private val recognizer: Any,
) {
    private val scoreMethod: Method? =
        recognizer.javaClass.methods.singleOrNull { method ->
            method.name == SCORE_METHOD_NAME &&
                method.parameterCount == 2 &&
                method.parameterTypes[1] == IntArray::class.java
        }

    private val discardMethod: Method? =
        recognizer.javaClass.methods.singleOrNull { method ->
            method.name == DISCARD_METHOD_NAME && method.parameterCount == 1
        }

    val isAvailable: Boolean
        get() = scoreMethod != null && discardMethod != null

    fun requireAvailable() {
        check(isAvailable) {
            "FunASR revision 2 requires patched sherpa-onnx CTC score/discard ABI"
        }
    }

    fun score(
        stream: Any,
        targetIds: IntArray,
    ): CtcAlignmentScores {
        require(targetIds.isNotEmpty()) { "FunASR CTC target ids must not be empty" }
        requireAvailable()
        val raw =
            invoke(scoreMethod!!, stream, targetIds) as? FloatArray
                ?: error("Patched sherpa FunASR CTC score ABI returned a non-FloatArray result")

        val candidateCount = targetIds.size + 1
        require(raw.isNotEmpty() && raw.size % candidateCount == 0) {
            "Patched sherpa FunASR CTC score matrix has invalid shape"
        }
        val frameCount = raw.size / candidateCount
        require(frameCount > 0) { "Patched sherpa FunASR CTC score matrix has no frames" }

        val blank = FloatArray(frameCount)
        val targets = FloatArray(frameCount * targetIds.size)
        for (frame in 0 until frameCount) {
            val sourceOffset = frame * candidateCount
            blank[frame] = raw[sourceOffset]
            raw.copyInto(
                destination = targets,
                destinationOffset = frame * targetIds.size,
                startIndex = sourceOffset + 1,
                endIndex = sourceOffset + candidateCount,
            )
        }

        return CtcAlignmentScores(
            frameCount = frameCount,
            targetTokenIds = targetIds.copyOf(),
            blankLogProbabilities = blank,
            targetLogProbabilities = targets,
        )
    }

    fun discard(stream: Any) {
        requireAvailable()
        invoke(discardMethod!!, stream)
    }

    private fun invoke(
        method: Method,
        vararg args: Any,
    ): Any? =
        try {
            method.invoke(recognizer, *args)
        } catch (error: InvocationTargetException) {
            throw IllegalStateException(
                "Patched sherpa FunASR CTC ABI failed: ${method.name}",
                error.targetException ?: error,
            )
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException(
                "Patched sherpa FunASR CTC ABI invocation failed: ${method.name}",
                error,
            )
        }

    internal companion object {
        const val SCORE_METHOD_NAME = "getFunAsrNanoCtcScores"
        const val DISCARD_METHOD_NAME = "discardFunAsrNanoCtcState"
    }
}
