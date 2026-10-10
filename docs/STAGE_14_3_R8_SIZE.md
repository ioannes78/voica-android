# Stage 14.3 — R8 / Resource Shrink / APK Size Optimization

Status: accepted Stage 14.3 implementation baseline; not a Final Freeze/Handoff document.

## Scope boundary

Stage 14.3 starts from the accepted Stage 14.1 product baseline and the Stage 14.2 release/signing boundary.

The accepted result changes build/packaging behavior only:

- enables R8 minification for `release`;
- enables Android resource shrinking for `release`;
- uses `proguard-android-optimize.txt` plus a narrow app rule set;
- protects the sherpa-onnx Java/Kotlin JNI ABI from R8 renaming/removal;
- excludes only the unused standalone Sherpa C/C++ API libraries from packaging;
- retains the Sherpa JNI bridge and ONNX Runtime;
- uses a non-debuggable, production-like `releaseQa` variant signed with the public QA signer for real-device testing;
- keeps the true PR `release` APK/AAB unsigned because the production private key is not provisioned yet;
- keeps Room at schema 12;
- does not modify the production model channel;
- does not modify notification, durable-attention, playback, model-install or task-state business logic.

## Accepted RC2-R2 identity

- versionCode: `84`
- Release versionName: `1.0.0-rc2-r2`
- production-like QA versionName: `1.0.0-rc2-r2-shrink-qa`
- Release applicationId: `io.github.ioannes78.voica`
- production-like QA applicationId: `io.github.ioannes78.voica.qa`
- Room: schema 12
- ABI: `arm64-v8a`

## R8 / JNI correction

The first shrink-only RC2 exposed a native-process exit when starting ASR or diarization. Root cause was R8 renaming/removing sherpa-onnx Java/Kotlin wrapper classes and members that the native library resolves through fixed JNI class/member names.

The accepted rule preserves the sherpa-onnx JNI wrapper ABI while still allowing optimization:

`-keep,allowoptimization class com.k2fsa.sherpa.onnx.** { *; }`

CI verifies the post-R8 APK still defines the original names for:

- `OfflineRecognizer`;
- `OfflineRecognizerResult`;
- `Vad`;
- `SpeechSegment`;
- `OfflineSpeakerDiarization`;
- `OfflineSpeakerDiarizationSegment`.

RC2-R1 with this correction passed real-device ASR, VAD and diarization testing.

## Native trim

The accepted RC2-R2 excludes only:

- `libsherpa-onnx-c-api.so`;
- `libsherpa-onnx-cxx-api.so`.

It retains:

- `libsherpa-onnx-jni.so`;
- `libonnxruntime.so`;
- `libvoica_opus_jni.so`;
- AndroidX path native runtime.

Static and CI checks confirmed the Sherpa JNI library does not `DT_NEEDED` either removed standalone API library. The APK/AAB packaging gate requires both trimmed libraries to be absent while the JNI bridge and ONNX Runtime remain present.

## Package-size result

Stage 14.2 QA baseline APK:

- `56,893,276` bytes.

Stage 14.3 RC2-R1 after R8/resource shrink and JNI correction:

- `35,565,779` bytes.

Accepted Stage 14.3 RC2-R2 after native trim:

- `30,650,407` bytes;
- SHA-256: `177cdc2d95781bbf1ba9e9cde883fcdc5d0315bae80f52c4fa734cc5e4590ce4`.

Reduction:

- RC2-R1 → RC2-R2: `4,915,372` bytes / **13.82%**;
- Stage 14.2 baseline → RC2-R2: `26,242,869` bytes / **46.13%**.

## CI result

Android PR CI:

- run number: `1100`;
- run id: `38005812040`;
- result: **SUCCESS**.

The gate passed:

- all existing unit tests;
- minified/shrunk production-like QA APK;
- unsigned Release APK;
- Release AAB;
- QA signer verification;
- Release/QA identity and non-debuggable checks;
- R8 mapping/configuration/seeds/usage outputs;
- post-R8 Sherpa JNI ABI checks;
- APK/AAB native-trim checks plus JNI ELF dependency check;
- production signing negative guardrails;
- Room v1-v12 schema provenance gate.

## Real-device acceptance

The project owner explicitly confirmed Stage 14.3 RC2-R2 real-device testing **PASS**.

Coverage included:

- SenseVoice/local ASR path;
- Silero VAD JNI/runtime path;
- full speaker diarization path;
- a dedicated VAD test file with known silence/speech windows, where detected speech intervals aligned with the designed speech windows and silence intervals were filtered;
- no recurrence of the original post-R8 native process exit.

Stage 14.3 is therefore accepted and no further aggressive trimming of `libonnxruntime.so` or `libsherpa-onnx-jni.so` is planned for V1.

## Production signing boundary

Production signing remains externally blocked until the project owner can provision the first production keystore/certificate on a trusted computer. The production-like QA APK is a test artifact only and must never be treated as the final V1 production signer.
