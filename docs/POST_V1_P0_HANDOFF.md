# Post-V1 P0 — Canonical WAV Performance & UX Handoff

Status: **QA CANDIDATE / NOT FROZEN / NOT MERGED**

This document hands the current Post-V1 P0 branch to true-device QA. It is not a Freeze document and does not authorize merge or release.

## 1. GitHub truth

- Repository: `ioannes78/voica-android`
- Production branch: `main`
- Development branch: `post-v1-canonical-audio-p0`
- Pull request: `#26` — `[APK] Post-V1 P0 — Canonical WAV Performance & UX`
- Main baseline used for development: `e150589baffcebf03d8697c701dd716097cd70f6`
- QA2-R2 implementation-code HEAD before this handoff-only commit: `1c268608eba2454f0669c9e82e1ee7410d5feef9`
- Fast PR Gate for that implementation HEAD: Android PR CI `#1223` — **SUCCESS**

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

## 6. Device download foreground notification QA

### 6.1 QA2-R1 result — FAILED on true device

QA2-R1 changed the repository collector so combined business-state emissions are invalidation signals only and every publish re-reads the latest connection / recording / file-operation / device-info values under one serialization boundary. This removed the stale precomputed-decision race.

True-device retest nevertheless still reproduced stale notification progress. More importantly, repeated downloads exposed a cumulative pattern:

- the first two downloads could complete normally;
- from the third download onward stale notification terminal state became much easier to reproduce;
- observed residual labels included `92%`, `99%` and `100%`;
- the residual percentage was not deterministic.

This means QA2-R1 fixed an upstream business-decision race but did not address repeated Android foreground-service command / notification updates.

### 6.2 QA2-R2 root cause and architecture

Before QA2-R2, every changed foreground label could call `DeviceSessionForegroundService.acquire()`. During one download, percentage changes such as 1%, 2%, 3% ... could therefore enqueue many `startForegroundService()` requests, each with a new generation, even though the connected-device foreground lease itself never ended.

That conflated two concepts:

1. foreground-service lease lifetime;
2. notification display revision.

QA2-R2 separates them.

### 6.3 QA2-R2 single-lease contract

For one continuous device connection/session:

- `startForegroundService()` is used to acquire the connected-device foreground lease;
- that lease generation remains stable while notification text changes;
- progress/recording/reconnect text changes do **not** acquire a new service generation;
- when the service is ACTIVE, the existing foreground notification is updated in place with `NotificationManager.notify()` using the same notification id;
- if business state changes while the initial service is still STARTING, no extra start command is queued; latest business truth is re-projected immediately when the one lease becomes ACTIVE;
- release/disconnect still invalidates the lease generation.

The existing stale-generation gate in `DeviceSessionForegroundService` remains as protection for genuinely stale lifecycle commands.

### 6.4 Progress coalescing

Download percentage notification updates are coalesced to a minimum interval of 300 ms. The delayed task does not carry an old percentage; it re-reads current business state before publishing.

Terminal/non-progress states are immediate and bypass the progress throttle, including:

- `Completed` → `已连接` while still connected;
- failure/cancellation → current connected state;
- recording state;
- reconnect/connection state;
- disconnect/release.

Therefore a delayed percentage cannot replay after terminal state.

### 6.5 Desired vs rendered diagnostics

QA2-R2 also separates notification display diagnostics from the foreground-service lease diagnostics. The display snapshot tracks:

- lease generation;
- desired revision / desired label;
- rendered revision / rendered label.

This makes future diagnosis able to distinguish business truth, requested display state and successfully rendered in-process notification update.

### 6.6 QA2-R2 regression coverage

Automated coverage now includes:

- one foreground lease across reconnect-label updates;
- five consecutive download operations on one lease;
- each download exercises late progress including 92%, 99% and 100%;
- every `Completed` returns to `已连接`;
- lease generation remains unchanged across all five downloads;
- the service acquire count remains one across the continuous session;
- later unrelated invalidations cannot reintroduce `正在下载`;
- state changes while the service is STARTING do not enqueue historical progress commands.

Fast PR Gate #1223 passed with the QA2-R2 production implementation and stress tests.

No BLE protocol, transfer accounting, file commit, Room schema, canonical pipeline, version identity or production model channel was changed for QA2-R2.

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

### QA2-R2 focused retest — highest priority

Use the same CB08/QS668 file and connection session that reproduced the cumulative failure.

Required observations:

- perform at least **5 consecutive downloads without disconnecting**;
- during transfer, system notification displays current `正在下载 · xx%`;
- after every successful local completion, notification returns to `已连接` while the device remains connected;
- specifically verify downloads #3, #4 and #5 because the previous implementation degraded from the third download onward;
- no permanent `92%`, `95%`, `99%` or `100%` residue;
- repeat at least once with the app backgrounded;
- cancel one download and verify stale percentage disappears;
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
4. QA2-R2 is confirmed with at least five consecutive true-device downloads;
5. remaining functional regression checks pass;
6. 30 / 60 / 120 minute benchmark evidence is collected or explicitly accepted as deferred by the user;
7. any additional QA findings are resolved and re-tested.

After acceptance, create the final Post-V1 P0 Freeze / Handoff update and only then continue to Stage 15.
