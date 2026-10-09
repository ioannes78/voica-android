# Stage 14.3 — R8 / Resource Shrink / APK Size Optimization

Status: RC2 implementation candidate; not a Final Freeze/Handoff document.

## Scope boundary

Stage 14.3 starts from the accepted Stage 14.1 product baseline and the Stage 14.2 release/signing boundary.

This RC2 slice changes build/packaging behavior only:

- enables R8 minification for `release`;
- enables Android resource shrinking for `release`;
- uses `proguard-android-optimize.txt` plus a deliberately narrow app rule set;
- adds a non-debuggable, production-like `releaseQa` variant signed with the public QA signer for real-device testing;
- keeps the true PR `release` APK/AAB unsigned because the production private key is not provisioned yet;
- keeps Room at schema 12;
- does not modify the production model channel;
- does not modify notification, durable-attention, playback, model-install or task-state business logic.

Native-library removal is deliberately **not** combined with RC2. Native trimming is deferred until this shrink-only candidate passes real-device QA.

## RC2 identity

- versionCode: `82`
- Release versionName: `1.0.0-rc2`
- production-like QA versionName: `1.0.0-rc2-shrink-qa`
- Release applicationId: `io.github.ioannes78.voica`
- production-like QA applicationId: `io.github.ioannes78.voica.qa`
- Room: schema 12
- ABI: `arm64-v8a`

The production-like QA build uses the existing public QA certificate but inherits the Release shrink/non-debuggable configuration. It can upgrade the preceding Stage 14 QA package because the application ID and QA signer stay the same and versionCode advances from 81 to 82.

## R8 rules

The app rule set is intentionally narrow:

- retain source/line/signature/annotation metadata useful for retrace/runtime contracts;
- explicitly preserve `io.github.ioannes78.voica.opus.NativeOpusBridge` native method names because Voica's Opus JNI library exports statically named JNI symbols.

No package-wide `-keep` rule was added.

The first full CI shrink completed without R8 missing-rule failures, so no broad compatibility suppression was required.

## CI result

Android PR CI:

- run number: `1092`
- run id: `37964691716`
- result: **SUCCESS**

The gate passed:

- all existing unit tests;
- minified/shrunk production-like QA APK build;
- minified/shrunk unsigned Release APK build;
- minified/shrunk Release AAB build;
- stable QA signer verification for the production-like QA APK;
- Release/QA package and version identity;
- non-debuggable manifest checks;
- unsigned true-Release boundary in PR CI;
- R8 mapping/configuration/seeds/usage output existence;
- production signing negative guardrails;
- Room v1-v12 committed schema provenance gate.

## Package-size baseline and RC2 result

Stage 14.2 QA baseline APK:

- bytes: `56,893,276`
- SHA-256: `a6e57b18f8c5895fe310cc4fd25ecf4d0bb9c6c4cb52bf612ad629f8f5033870`

Stage 14.3 RC2 production-like QA APK:

- bytes: `35,500,243`
- SHA-256: `a7289ea873c6187aecfb0f1d883909ed42310e8d3ccd42546624c17cfc89c800`

Reduction:

- `21,393,033` bytes smaller;
- **37.60%** APK reduction.

### ZIP payload composition

| Category | Stage 14.2 baseline | Stage 14.3 RC2 | Change |
| --- | ---: | ---: | ---: |
| DEX compressed payload | 23,195,729 B | 2,297,624 B | -90.09% |
| Native `.so` payload | 32,654,984 B | 32,517,264 B | -0.42% |
| Android resources payload | 648,822 B | 286,720 B | -55.81% |
| Assets payload | 153,695 B | 161,052 B | +4.79% |

DEX also collapsed from 22 dex files / 74,794,976 uncompressed bytes to one `classes.dex` / 4,632,624 uncompressed bytes.

After R8/resource shrinking, native libraries now account for approximately **91.6%** of the APK. This confirms that further material package reduction must come from the native dependency set, not from additional broad Java/Kotlin keep-rule removal.

## Native audit — deferred RC3 candidate

RC2 intentionally retains the complete Sherpa native package.

Current production-like APK native payload includes:

| Library | Bytes |
| --- | ---: |
| `libonnxruntime.so` | 22,249,552 |
| `libsherpa-onnx-jni.so` | 4,771,760 |
| `libsherpa-onnx-c-api.so` | 4,465,168 |
| `libvoica_opus_jni.so` | 580,000 |
| `libsherpa-onnx-cxx-api.so` | 440,688 |
| `libandroidx.graphics.path.so` | 10,096 |

Static audit observations for the two Sherpa C/C++ API libraries:

- Voica code explicitly loads `sherpa-onnx-jni`, not the C/C++ API libraries;
- the packaged DEX contains the `sherpa-onnx-jni` library name but not the C/C++ API library names;
- `libsherpa-onnx-jni.so` does not list `libsherpa-onnx-c-api.so` or `libsherpa-onnx-cxx-api.so` in its ELF `DT_NEEDED` dependencies;
- together the two libraries occupy `4,905,856` bytes, about 13.82% of the RC2 APK.

These observations make them strong **audit candidates**, but not yet safe deletion targets. A later Stage 14.3 native-trim candidate may exclude them only after RC2 passes real-device tests and must then re-test every shipped Sherpa path: SenseVoice, Qwen3-ASR, Silero VAD, Pyannote segmentation, CAM++ speaker embedding, punctuation, isolated model validation and model benchmark/validation paths that remain product-reachable.

## Production signing boundary

Production signing remains externally blocked until the project owner can provision the first production keystore/certificate on a trusted computer.

RC2 does not weaken that boundary. The production-like QA APK is a test artifact only and must never be treated as the final V1 production signer.
