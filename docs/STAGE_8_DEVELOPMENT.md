# Stage 8 Development

Status: in progress

Baseline:

- `main` HEAD at Stage 8 start: `6bcc8a425e68fff23e2b5ea58764f692517ac3e2`
- Stage 7 evidence commit: `9ee341d007ff0595d5e9148bb7f319225ed30243`
- Both commits resolve to the same source tree: `807a7395f5800d3dd0b2c2fd13be8563735c77d6`
- Android PR CI run 150 was re-run before Stage 8 changes and passed the existing Stage 1–7 test/build matrix.

Confirmed Stage 8 direction:

- sherpa-onnx Android runtime
- built-in Silero VAD baseline with managed override support
- managed-download Small Bilingual Zipformer as the single first-pass/streaming ASR
- managed-download zh-en CT-Transformer punctuation
- optional SenseVoice 2024 second-pass ASR
- file ASR uses the same streaming session contract reserved for Stage 16 live ASR
- Room 1 -> 2 with explicit migration
- independent Voica model channel with automated candidate preparation, CI and a human merge gate
- Stage 9 speaker diarization remains out of Stage 8

Implementation gates:

1. Freeze the exact Room v1 schema generated from the unchanged Stage 7 entities before any entity/schema modification.
2. Prove sherpa-onnx Android runtime integration before full ASR implementation.
3. Prove ModelManager hashing/staging/atomic install/rollback before using production model packages.
4. Prove the fast pipeline before enabling SenseVoice two-pass.
5. No Stage 8 Freeze/merge until real-device acceptance and explicit user confirmation.

## Stage 8A / 8B progress

- Room v1 schema generated from unchanged Stage 7 entities and frozen with identity hash `a2d73e20eae7b30d6efcdb455cc5eec8`.
- CI now rejects Room schema drift.
- Added `:engine:sherpa` pinned to sherpa-onnx v1.13.8.
- App is pinned to arm64-v8a for the Stage 8 native runtime.
- Added a Settings-page native-load probe for the first Stage 8 real-device runtime gate.

## Runtime gate result

- Real-device Stage 8 alpha1 runtime probe passed.
- sherpa-onnx v1.13.8 native library loaded successfully on the test device.
- Stage 8B runtime gate is closed.

## Stage 8C contracts

- Added `:core:model` for model identity, capability, source, update, integrity and ModelManager contracts.
- Added `:core:transcript` for VAD, streaming ASR, second-pass ASR, punctuation, progress and transcript timeline contracts.
- File ASR and future Stage 16 live ASR share `StreamingAsrSession`.
- Token timing remains optional and maps onto the Stage 7 absolute canonical sample timeline; fabricated character timing is forbidden.

## Stage 8G / 8H progress

- Room v2 is frozen with explicit `MIGRATION_1_2`; JVM migration tests preserve Stage 7 recording/audio data and validate transcript foreign-key cascades.
- `PcmSource.totalSampleCount` is now the single authoritative denominator for VAD/file-ASR progress.
- Added `SherpaSileroVadEngine` using sherpa VAD sample offsets directly as canonical absolute sample indices.
- VAD contract tests passed CI #183 after correcting a JUnit4 test-signature issue; VAD production logic was unchanged by that fix.
- Vendored the pinned k2-fsa `silero_vad.int8.onnx` baseline into `:engine:sherpa` assets only after verifying 212,860 bytes and SHA-256 `c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20`.
- Built-in Silero remains `BUILTIN_WITH_OVERRIDE`: a future managed confirmed-good version may override it, while the APK asset remains the fallback.
