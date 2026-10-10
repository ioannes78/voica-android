# Stage 14.4.1 — Export UX Closure

Status: **QA-R2 real-device candidate; not a Final Freeze/Handoff document.**

## Context

Stage 14.4 RC3 security/privacy/model-supply-chain QA passed on a real device. During that QA, export itself was functional for recordings, transcripts, and AI summaries, but completion feedback and save-location visibility required V1 product closure.

The first Stage 14.4.1 candidate (v86 / RC3-R1) added a global Snackbar result layer plus export-feedback settings. Real-device review showed that this duplicated the pre-existing Toast feedback and made the settings page unnecessarily complex.

QA-R2 deliberately simplifies that design before Stage 14.5 Release CI.

## Candidate identity

- versionCode: `87`
- Release versionName: `1.0.0-rc3-r2`
- production-like QA versionName: `1.0.0-rc3-r2-export-qa`
- Release applicationId: `io.github.ioannes78.voica`
- QA applicationId: `io.github.ioannes78.voica.qa`
- Room: schema 12
- production model channel contents: unchanged

## QA-R2 UX contract

- default one-tap export location remains `系统下载 / Voica`;
- settings entry remains `存储与导出`;
- users can choose a custom export folder through Android Storage Access Framework `OpenDocumentTree`;
- the selected tree permission is persisted and reused for later exports;
- recording audio, transcript TXT/Markdown, and AI-summary TXT/Markdown share the same preferred destination;
- `导出位置` is reduced to exactly two rows:
  1. `保存位置` — shows the current destination and opens the system folder picker when tapped;
  2. `恢复默认` — restores `系统下载 / Voica` and is disabled when already using the default;
- for Android external-storage tree URIs, the settings page attempts to show a readable logical path such as `内部存储 / Documents / Voica` instead of only the leaf folder name or a `content://` URI;
- the Stage 14.4.1 global export Snackbar host is removed;
- the existing product Toast notifications remain the user-visible export feedback mechanism;
- the entire `导出反馈` settings section is removed, including the success-feedback switch and `打开已导出文件` entry;
- `恢复默认` changes the page state immediately and does not emit a Toast or Snackbar;
- no Room schema change is introduced.

## Data/security boundary

The custom export tree stores the user-granted SAF URI and human-readable label in application preferences. The existing Stage 14.4 backup exclusions remain in force, so these settings do not enter Android cloud/device-transfer backup.

No new broad storage permission is requested. Voica writes custom destinations only through the user-granted SAF tree URI.

Stage 14.4 model-channel pinning, cleartext rejection, credential encryption, R8/Sherpa JNI protection, native trim, and production-signing guardrails remain unchanged.

## CI

QA-R2 candidate CI:

- Android PR CI run number: `1127`;
- run id: `38028616994`;
- binary/workflow head: `ac85d67bd09168359c02ad108a372d890b59ec08`;
- result: **SUCCESS**.

The full applicable gate passed:

- existing unit tests and App/Compose compilation;
- minified/shrunk production-like QA APK;
- v87 / RC3-R2 production-like QA identity;
- Stage 14.4 security boundary checks;
- QA signing identity;
- R8 outputs and package-size boundary;
- Sherpa JNI post-R8 ABI gate;
- Sherpa native trim gate;
- Production signing negative guardrails;
- committed Room schema v1-v12 provenance gate;
- QA artifact upload.

Production Release APK/AAB-only checks remain gated to `[RELEASE]`/manual runs under the existing tiered CI policy.

## QA APK

- size: `30,651,379` bytes;
- SHA-256: `26ac55484e6e238ddc47e45228fdc29853c1a3b196250c33111643930f20f58f`.

## Real-device acceptance gate

Before Stage 14.4.1 is accepted, validate:

1. cover-install v87 over the current QA package and confirm recordings/transcripts/summaries/models/settings remain intact;
2. `存储与导出 → 导出位置` shows only `保存位置` and `恢复默认`;
3. default `保存位置` shows `系统下载 / Voica`;
4. tapping `保存位置` selects a custom folder, the readable location updates, and the persisted SAF permission survives an App restart;
5. recording, transcript, and AI-summary exports all use the selected custom folder;
6. each export produces only the existing Toast feedback and no global Snackbar overlay;
7. tapping `恢复默认` immediately returns the first row to `系统下载 / Voica` without an additional Toast/Snackbar;
8. subsequent exports return to the default destination;
9. the removed `导出反馈` switch and `打开已导出文件` entry do not appear;
10. no regression is observed during a brief playback/transcript/summary smoke test.

Only after explicit real-device PASS may Stage 14.4.1 be accepted and Stage 14.5 begin.
