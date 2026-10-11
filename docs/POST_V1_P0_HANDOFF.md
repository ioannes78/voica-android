# Post-V1 P0 — Canonical WAV Performance & UX Handoff

Status: **QA CANDIDATE / NOT FROZEN / NOT MERGED**

This document hands the current Post-V1 P0 branch to true-device QA. It is not a Freeze document and does not authorize merge or release.

## 1. GitHub truth

- Repository: `ioannes78/voica-android`
- Production branch: `main`
- Development branch: `post-v1-canonical-audio-p0`
- Pull request: `#26` — `[APK] Post-V1 P0 — Canonical WAV Performance & UX`
- Main baseline used for development: `e150589baffcebf03d8697c701dd716097cd70f6`
- QA2-R1 implementation-code HEAD before this handoff-only commit: `ef6c4fc3996150dbb2b0a5059077e0e292c80373`
- Fast PR Gate for that implementation HEAD: Android PR CI `#1217` — **SUCCESS**

Always re-check the current PR HEAD and CI before further code changes.

## 2. Product / schema baseline

- target `versionName`: `1.0.1`
- target `versionCode`: `89`
- production applicationId: `io.github.ioannes78.voica`
- QA applicationId: `io.github.ioannes78.voica.qa`
- Room: **v12 unchanged**
- no Post-V1 P0 Room migration
- canonical profile remains `CANONICAL_PCM16_16000_MONO_WAV_V1`
- canonical output contract remains PCM16 / 16 kHz / mono WAV
- production model channel pin remains unchanged from Stage 14 V1
- ABI remains `arm64-v8a`

## 3. Implemented canonical performance changes

### 3.1 Source integrity de-duplication

Canonical generation still performs one authoritative full SHA-256 verification of the managed source before conversion. The immediately repeated converter-level full source SHA scan was removed for coordinator-owned compressed media, PCM WAV and RAW Opus conversions. RAW Opus also reuses the coordinator validation result.

Output canonical SHA, Room lineage, source SHA lineage and corruption detection remain intact.

### 3.2 Reusable streaming buffers

The conversion hot path now reuses bounded buffers instead of allocating a fresh array chain for every block:

- Android MediaCodec output `ShortArray` reuse;
- allocation-free `StreamingPcm16Normalizer.processInterleavedInto` hot path;
- reusable normalizer output buffer;
- reusable PCM WAV byte/interleaved/mono buffers;
- reusable 64 KiB canonical WAV writer byte buffer;
- PCM WAV streaming chunk size increased to 64 KiB.

### 3.3 Existing canonical WAV Fast Path preserved

A managed WAV that is already PCM16 / 16 kHz / mono canonical audio is reused in place. It is not copied to a second physical canonical file. Existing shared-path cleanup protections remain in force.

## 4. Canonical generation progress and player UX

Canonical high-frequency progress is process-local runtime state, not Room state. Room continues to hold durable lifecycle / terminal state only.

Progress sources:

- PCM WAV: exact processed frames / total frames;
- compressed media: MediaCodec presentation timeline when available, decoded-frame time as fallback;
- RAW Opus: exact packet/sample progress.

The player card now has three states:

1. not generated — integrated `生成标准 WAV` action;
2. generating — real phase/progress, `已处理 / 总时长`, `已耗时`, trailing `取消` while cancellable;
3. ready — normal waveform player.

The old standalone full-width canonical cancel action was removed.

The recording information card now shows, in order:

1. 录制时间
2. 录音时长
3. 文件大小
4. 来源 / 格式

`录音时长` uses actual media/device duration metadata, not file-size estimation.

## 5. QA1-R1 — compressed-duration progress overrun

True-device QA found MP3 files where `已处理` exceeded the displayed total by tens of seconds while decoding remained active.

The fix:

- compressed progress prefers MediaCodec output PTS (`presentationTimeUs + block duration`);
- metadata duration alone is no longer treated as decoder completion;
- explicit decoder completion / final-work semantics control terminal progress;
- displayed processed duration is clamped to the known total;
- when metadata duration is exhausted before decoder EOS, determinate fraction no longer pretends the work is complete;
- progress throttling remains active during the overrun interval;
- decoding is never truncated just to match metadata duration;
- `VoicaCanonicalPerf` records metadata / decoded-frame / media-timeline / canonical duration drift.

True-device result: **QA1-R1 PASSED** on the two reported reproducing MP3 files. Captured UI showed `21:22 / 21:22` and `27:44 / 27:44` without processed-duration overrun.

## 6. QA2-R1 — device download notification terminal-state race

### 6.1 True-device failure

After the first P0 notification fix, CB08/QS668 downloads could still finish locally while the Android connected-device foreground notification remained at `正在下载 · xx%`.

Repeated testing showed that the residual percentage was nondeterministic: the same file could remain at `92%`, `100%`, or another late progress value. Most runs remained at `100%`.

### 6.2 Root cause

The lower service layer already had a generation gate that rejects stale queued `ACTION_ACQUIRE` commands before publishing the notification. That fix remains valid, but it only protects against a command that already carries an old generation.

`ServiceBackedDeviceRepository` still had two competing notification decision paths:

- a long-lived `combine(connectionState, recordingState, fileOperationState, deviceInfo)` collector that precomputed a `DeviceSessionForegroundDecision`;
- an explicit post-download `finally` reconciliation that read the latest terminal state.

A progress decision such as `Active(DOWNLOAD, 92%)` could be computed, then be scheduled late. Meanwhile the download could advance to `Completed` and publish `已连接`. When the older precomputed progress decision later resumed, it requested a **newer** foreground-service generation and therefore legitimately passed the lower generation gate, restoring the stale progress notification.

This explains why the frozen percentage varied between runs.

### 6.3 QA2-R1 fix

The repository no longer carries a precomputed device-session decision across the `combine` scheduling boundary.

The combined flow is now only an **invalidation signal**. When the collector actually runs, it calls one reconciliation path that:

1. enters the existing repository serialization lock;
2. re-reads the current values of connection, recording, file-operation and device-info StateFlows;
3. computes the notification decision from those latest values;
4. publishes the foreground-service state while still under the same serialization boundary.

The explicit post-download `finally` reconciliation uses the same path.

Therefore a delayed invalidation originally caused by `Active(DOWNLOAD)` cannot publish an obsolete percentage after `Completed`, `Failed` or `Cancelled` has become current truth. If it executes late, it simply re-reads the terminal state and projects `已连接` (or the current recording/reconnect state).

The lower `DeviceSessionForegroundService` generation gate is retained as a second protection layer against stale queued service intents.

No BLE protocol, transfer accounting, Room schema, file commit logic, SHA contract or model channel was changed for QA2-R1.

### 6.4 Regression coverage

A dedicated device-download notification terminal-state test now covers:

- active download `92%` → terminal `Completed` → `已连接`;
- later unrelated state invalidations after terminal must not reintroduce `正在下载`;
- a new subsequent download operation may publish a new progress state;
- the second operation terminal state again returns to `已连接`.

Fast PR Gate #1217 passed with the QA2-R1 production fix and test.

## 7. Canonical performance instrumentation

Successful canonical generation publishes `VoicaCanonicalPerf` diagnostics including:

- source role/container/bytes/duration;
- source SHA time;
- decode/read time;
- normalization time;
- WAV write time;
- `fd.sync()` time;
- atomic move time;
- output SHA time;
- pipeline time;
- metadata duration;
- decoded-frame duration;
- media-timeline duration;
- canonical duration;
- duration delta;
- Room commit time;
- total wall time;
- RTF.

This is intended for the planned 30 / 60 / 120 minute true-device benchmark.

## 8. Required true-device QA

### QA2-R1 focused retest — highest priority

Use the same CB08/QS668 file that reproduced the stale system notification and repeat the download multiple times.

Required observations:

- during transfer, system notification displays current `正在下载 · xx%`;
- after the file has committed and registered successfully, notification must leave download state and show `已连接` while the device remains connected;
- no permanent `92%`, `95%`, `99%` or `100%` residue;
- repeat the same file several times because the original failure was timing-dependent;
- repeat at least once with the app backgrounded;
- cancel one download and verify stale percentage disappears;
- perform two downloads consecutively and verify operation #1 terminal state cannot overwrite operation #2 progress, and operation #2 completion returns to `已连接`;
- complete a download and immediately start recording; notification must become the recording state and old download progress must not return.

### Canonical generation UX / functional regression

- progress never displays processed duration greater than total;
- generation completion restores the ready waveform player;
- cancellation works only in cancellable phases;
- playback, waveform, ASR and diarization still resolve canonical audio;
- canonical-compatible WAV Fast Path remains fast and safe;
- recording info contains the real recording duration.

### 30 / 60 / 120 minute benchmark

Use the same physical phone and comparable source media. Capture `VoicaCanonicalPerf` for each duration and compare RTF, `T60/T30`, `T120/T60`, source SHA, decode/read, normalization, write/sync and output SHA shares.

## 9. Deferred optimization

P0 still does not change the downstream `RoomAudioSourceResolver` full-SHA integrity contract. A process-local verified-canonical resolver cache remains conditional work only if true-device profiling proves meaningful benefit.

## 10. Merge / Freeze rule

Do **not** merge PR #26 and do **not** create Post-V1 P0 Freeze until:

1. current `[APK]` Fast Gate passes;
2. current QA APK Gate passes;
3. QA1-R1 remains accepted;
4. QA2-R1 is confirmed on repeated true-device downloads;
5. remaining functional regression checks pass;
6. 30 / 60 / 120 minute benchmark evidence is collected or explicitly accepted as deferred by the user;
7. any additional QA findings are resolved and re-tested.

After acceptance, create the final Post-V1 P0 Freeze / Handoff update and only then continue to Stage 15.
