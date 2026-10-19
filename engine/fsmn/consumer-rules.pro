# Stage 16B candidate runtime is instantiated reflectively by the isolated model validator.
-keep,allowoptimization class io.github.ioannes78.voica.fsmn.FsmnModelCandidateValidator {
    public <init>();
}

# Native symbols in voica_fsmn_runtime_probe.cpp use statically named JNI entry points.
-keep class io.github.ioannes78.voica.fsmn.FsmnNativeBridge {
    native <methods>;
}

-keep class io.github.ioannes78.voica.fsmn.FsmnOrtRuntimeProbe {
    native <methods>;
}
