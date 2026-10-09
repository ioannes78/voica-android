# Stage 14.3 — V1 release shrinker rules.
# Keep this file intentionally narrow. Prefer library consumer rules and explicit runtime contracts
# over broad package-wide keep rules so R8 can remove unused code.

# Preserve useful retrace/debug metadata in production crash reports and generic/annotation metadata
# used by AndroidX/runtime code paths.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# Voica's Opus bridge is bound by statically named JNI symbols in voica_opus_jni.cpp.
# Preserve the bridge class and native method names across obfuscation.
-keep class io.github.ioannes78.voica.opus.NativeOpusBridge {
    native <methods>;
}
