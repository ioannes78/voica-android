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


## Stage 8I–8N progress

- Added managed model package extraction, hashing, staging, atomic promotion,
  exact revision leases, confirmed-good activation, rollback, and builtin Silero fallback.
- Remote catalogs are HTTPS-only, bounded in size, cached, and their models-array
  SHA-256 digest is verified before use.
- App Settings now exposes runtime probe, update check, model download/cancel,
  candidate verification and activation. Downloading a candidate never silently
  activates it.
- Candidate activation is guarded by a real sherpa-onnx native smoke test for VAD,
  streaming ASR, punctuation, and second-pass ASR.
- Added fast file transcription and bounded high-quality two-pass transcription.
  Both preserve Stage 7 canonical absolute sample indices.
- Added TranscriptionRepository state-machine enforcement, startup interruption
  reconciliation, active-model lineage snapshots, and atomic COMPLETED persistence.
- Added an app-scoped single-job TranscriptionCoordinator with exact model revision
  leases, Fast/High Quality mode selection, cancellation and recoverable failure handling.
- Added local-recording UI actions for Fast / High Quality transcription, real phase
  progress, cancellation, missing-model diagnostics, and read-only transcript results.
- Android CI #218 passed the complete Stage 1–8N test/build/schema matrix at commit
  `6004f0cce73e37bb5f1bea842e6960188f8ffb18`.

## Model-channel delivery status

- The independent public repository `ioannes78/voica-model-channel` is initialized
  and is now the authoritative model-distribution repository.
- Its `main/manifests/production.json` is live and intentionally contains only the
  APK-bundled Silero VAD baseline until downloadable candidates pass real-device gates.
- Model-channel validation CI #2 passed at commit
  `f353a6f799db738588007eba342c124379050147`.
- Small Bilingual uses a deterministic slim ZIP with the documented encoder-int8 /
  decoder-fp32 / joiner-int8 / tokens combination. RK35xx accelerator assets remain
  out of scope for the generic Android CPU path.
- CT-Transformer 2024-04-12 int8 and SenseVoice 2024-07-17 int8-only use pinned
  official upstream TAR_BZ2 packages with exact byte size and SHA-256.
- Candidate workflows publish a prerelease `production.json` before opening/updating
  the human-gated manifest PR. A Debug Voica APK can therefore validate the exact
  unmerged candidate catalog before production publication.
- Final Stage 8 model-channel acceptance still requires each candidate workflow run,
  Android download + package/file integrity verification, native smoke activation,
  relevant transcription acceptance, and only then human merge into production.


## Stage 8O long-duration and production ASR package verification

- Added pure-JVM virtual 30/60/120 minute pipeline tests. They exercise the full VAD -> first-pass ASR -> punctuation path with fixed 4,096-sample reads, absolute Long sample indices and no whole-file PCM allocation.
- Added cancellation coverage during a virtual 120-minute VAD scan; cancellation must close VAD/PCM resources and must not open ASR or punctuation engines.
- The Small Bilingual release contains both fp32 and int8 variants, including an int8 decoder file. However, the model-specific sherpa-onnx int8 invocation for `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16` explicitly uses:
  - `encoder.int8.onnx` (renamed from `encoder-epoch-99-avg-1.int8.onnx`)
  - `decoder.onnx` (renamed from `decoder-epoch-99-avg-1.onnx`)
  - `joiner.int8.onnx` (renamed from `joiner-epoch-99-avg-1.int8.onnx`)
  - `tokens.txt`
- Voica follows that documented generic CPU combination for the first production candidate. The installed payload is therefore approximately 41 MB + 14 MB + 3.1 MB plus tokens, roughly 58–60 MB before filesystem overhead.
- The unproven all-int8 decoder variant may be benchmarked later as a separate revision, but it must not replace the documented baseline without Android native-load and recognition-quality evidence.
- Rockchip RK356x/RK3576/RK3588 ~50 MB release assets are accelerator-specific and are not valid substitutes for the generic Android arm64 CPU package.


## Stage 8P progress

- Added the confirmed App-side update policy: automatic production-manifest checks
  are enabled by default.
- Only the built-in/override Silero VAD model is eligible for automatic download,
  verification, native smoke activation and switching.
- Small Bilingual ASR, CT-Transformer punctuation and SenseVoice remain explicit
  user-confirmed downloads/updates even when an update is discovered automatically.
- Existing transcription jobs keep their exact model revision leases; activation of
  a newly confirmed model affects only future jobs.
- Long-duration 30/60/120 minute synthetic tests now advance the absolute sample
  cursor without repeatedly filling zero PCM buffers, preserving the same bounded-read
  assertions with lower CI CPU cost.


## Stage 8Q model-channel bootstrap hardening

- Android PR CI #232 passed at commit `da7039e6b727ee529daad2c1528a6e58daea252a`
  after the bootstrap-catalog regression test was changed to read the committed asset
  directly rather than relying on Robolectric asset packaging.
- Added deterministic candidate builders and manual-dispatch workflows for
  CT-Transformer punctuation and SenseVoice 2024 int8.
- Added an initial Silero-only production manifest template plus an independent
  production-manifest validator workflow.
- These bootstrap-only files are outside the Android PR CI path filter; the latest
  Android code/test baseline remains CI #232 until an Android/docs path changes.


## Stage 8R candidate-channel acceptance loop

- Added a Debug-only candidate manifest override. Release/non-debug builds always use
  the fixed production manifest and ignore any stored override.
- Debug candidate URLs are restricted to HTTPS release assets under
  `ioannes78/voica-model-channel/releases/download/.../production.json`.
- Changing the candidate URL requires a full App restart because ModelManager is
  application-scoped and resolves its catalog source at startup.
- Settings is now vertically scrollable so model download/activate/rollback controls
  remain reachable on small screens.
- Stage 8 full acceptance candidate is versionCode 21 /
  `0.8.0-stage8-alpha2`.
