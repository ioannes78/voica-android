package io.github.ioannes78.voica.sherpa

import java.lang.reflect.InvocationTargetException

/**
 * Injects the revision-2 compact CTC model path into a patched sherpa
 * OfflineFunAsrNanoModelConfig without making revision-1 source depend on a
 * not-yet-released AAR member.
 *
 * The patched AAR contract adds:
 *   var ctcCompact: String = ""
 *
 * Empty keeps stock FunASR behavior. A non-empty value enables retention of
 * the dual-output encoder state for the same OfflineStream until CTC scores are
 * consumed or explicitly discarded.
 */
internal fun configurePatchedFunAsrCtcModelConfig(
    funAsrConfig: Any,
    bundle: FunAsrCtcBundle,
) {
    val setter =
        funAsrConfig.javaClass.methods.singleOrNull { method ->
            method.name == CTC_COMPACT_SETTER &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java
        } ?: error(
            "FunASR revision 2 requires patched sherpa-onnx config ABI: $CTC_COMPACT_SETTER",
        )

    val path = bundle.compactScorer.absolutePath
    try {
        setter.invoke(funAsrConfig, path)
    } catch (error: InvocationTargetException) {
        throw IllegalStateException(
            "Patched sherpa FunASR CTC config setter failed",
            error.targetException ?: error,
        )
    } catch (error: ReflectiveOperationException) {
        throw IllegalStateException(
            "Patched sherpa FunASR CTC config setter invocation failed",
            error,
        )
    }

    // Verify when the patched config exposes the normal Kotlin getter. This
    // catches an ABI that accepted the call but did not preserve the path.
    val getter =
        funAsrConfig.javaClass.methods.singleOrNull { method ->
            method.name == CTC_COMPACT_GETTER && method.parameterCount == 0
        }
    if (getter != null) {
        val actual = getter.invoke(funAsrConfig) as? String
        check(actual == path) {
            "Patched sherpa FunASR CTC config did not retain compact scorer path"
        }
    }
}

private const val CTC_COMPACT_SETTER = "setCtcCompact"
private const val CTC_COMPACT_GETTER = "getCtcCompact"
