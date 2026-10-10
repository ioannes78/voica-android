# Stage 14.3 — V1 release shrinker rules.
# Keep this file intentionally narrow. Prefer library consumer rules and explicit runtime contracts
# over broad app package-wide keep rules so R8 can remove unused Voica code.

# Preserve useful retrace/debug metadata in production crash reports and generic/annotation metadata
# used by AndroidX/runtime code paths.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# Voica's Opus bridge is bound by statically named JNI symbols in voica_opus_jni.cpp.
# Preserve the bridge class and native method names across obfuscation.
-keep class io.github.ioannes78.voica.opus.NativeOpusBridge {
    native <methods>;
}

# sherpa-onnx's JNI library has a Java/Kotlin ABI contract that is not safe to obfuscate.
# Native code exports Java_com_k2fsa_sherpa_onnx* symbols and also calls FindClass,
# GetFieldID and GetMethodID with literal com/k2fsa/sherpa/onnx names. Renaming or
# removing wrapper classes/members can therefore abort the process when ASR, VAD or
# speaker-diarization initializes. Keep names and members while still allowing R8 to
# optimize method bodies.
-keep,allowoptimization class com.k2fsa.sherpa.onnx.** {
    *;
}
