# Stage 14.4.1 — Export UX Closure

Status: real-device QA candidate; not a Final Freeze/Handoff document.

## Context

Stage 14.4 RC3 security/privacy/model-supply-chain QA passed on a real device. During that QA, export itself was functional for recordings, transcripts, and AI summaries, but completion feedback was too weak and the default save location was not exposed as a visible/configurable product setting.

Stage 14.4.1 closes that V1 UX gap before Stage 14.5 Release CI.

## Candidate identity

- versionCode: `86`
- Release versionName: `1.0.0-rc3-r1`
- production-like QA versionName: `1.0.0-rc3-r1-export-qa`
- Release applicationId: `io.github.ioannes78.voica`
- QA applicationId: `io.github.ioannes78.voica.qa`
- Room: schema 12
- production model channel contents: unchanged

## UX contract

- default one-tap export location remains `系统下载 / Voica`;
- settings entry is now `存储与导出`;
- users can choose a custom export folder through Android Storage Access Framework `OpenDocumentTree`;
- the selected tree permission is persisted and reused for later exports;
- recording audio, transcript TXT/Markdown, and AI-summary TXT/Markdown share the same preferred destination;
- users can restore the default `下载/Voica` destination;
- export completion is projected through a global Material 3 Snackbar showing the exported filename and visible location label;
- when a concrete exported URI is available, Snackbar exposes an `打开` action;
- failures remain visible even when successful-result feedback is disabled;
- the most recent exported file is retained for the `打开已导出文件` action in settings;
- successful-result feedback can be enabled/disabled in settings;
- no Room schema change is introduced.

## Data/security boundary

The custom export tree stores only the user-granted SAF URI and human-readable label in application preferences. The existing Stage 14.4 backup exclusions remain in force, so these settings do not enter Android cloud/device-transfer backup.

No new broad storage permission is requested. Voica writes custom destinations only through the user-granted SAF tree URI.

Stage 14.4 model-channel pinning, cleartext rejection, credential encryption, R8/Sherpa JNI protection, native trim, and production-signing guardrails remain unchanged.

## CI

Final candidate CI:

- Android PR CI run number: `1120`;
- run id: `38017604938`;
- binary/workflow head: `b541cfee3387cbb531587b5d9a2abff244881925`;
- result: **SUCCESS**.

The full gate passed:

- existing unit tests;
- minified/shrunk production-like QA APK;
- unsigned Release APK and Release AAB;
- v86 / RC3-R1 application/version identity;
- Stage 14.4 security boundary checks;
- QA signing identity;
- R8 outputs and package-size boundary;
- Sherpa JNI post-R8 ABI gate;
- Sherpa native trim gate;
- Production signing negative guardrails;
- committed Room schema v1-v12 provenance gate.

An earlier run (#1117) failed before App compilation because the external Xiph Opus source download returned a TLS/SSL connection error; the subsequent runs passed the same native toolchain and App build.

## QA APK

- size: `30,671,031` bytes;
- SHA-256: `936a08cbb71a87d12bbbae31eea359dcc267f3a64c169e2b09d06c7100370cd8`.

## Real-device acceptance gate

Before Stage 14.4.1 is accepted, validate:

1. in-place upgrade from the v85 QA package preserves recordings, transcripts, summaries, models, and settings;
2. default recording export completes to `下载/Voica` and produces visible result feedback;
3. transcript TXT/Markdown and AI-summary TXT/Markdown use the same default destination and visible feedback;
4. Snackbar `打开` opens the exported file through Android intent routing;
5. selecting a custom folder persists access and subsequent recording/transcript/summary exports go to that folder without a per-export picker on Android 10+;
6. `恢复默认` returns subsequent exports to `下载/Voica`;
7. disabling successful-result feedback suppresses success Snackbar while export itself continues to work;
8. failure feedback remains visible;
9. `打开已导出文件` shows/opens the most recent exported file;
10. no regression to local ASR/diarization, playback, notifications, or model management is observed during smoke use.
