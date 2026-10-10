# Post-V1 P0 — Canonical WAV Performance & UX Handoff

Status: **QA CANDIDATE / NOT FROZEN / NOT MERGED**

This document hands the implemented Post-V1 P0 branch to true-device QA. It is not a Freeze document and does not authorize merge or release.

## 1. GitHub truth

- Repository: `ioannes78/voica-android`
- Production branch: `main`
- Development branch: `post-v1-canonical-audio-p0`
- Pull request: `#26` — `[APK] Post-V1 P0 — Canonical WAV Performance & UX`
- Main baseline used for development: `e150589baffcebf03d8697c701dd716097cd70f6`
- Last implementation-code HEAD before this handoff-only commit: `9eb4fb6d4b79c66505d5fe8d687930be7328cc1b`
- Fast PR Gate for that implementation HEAD: Android PR CI `#1204` — SUCCESS

Always re-check the current PR HEAD and CI before further code changes.

## 2. Product / schema baseline

- Target versionName: `1.0.1`
- Target versionCode: `89`
- Production applicationId: `io.github.ioannes78.voica`
- QA applicationId: `io.github.ioannes78.voica.qa`
- Room: **v12 unchanged**
- No Post-V1 P0 Room migration
- Canonical profile remains `CANONICAL_PCM16_16000_MONO_WAV_V1`
- Canonical pipeline semantic version remains `1`; output contract was not changed
- Production model channel pin remains unchanged from Stage 14 V1
- ABI remains `arm64-v8a`

## 3. Implemented canonical performance changes

### 3.1 Source integrity de-duplication

Canonical generation still performs one authoritative full SHA-256 verification of the managed source before conversion.

The immediately repeated converter-level full source SHA scan was removed for coordinator-owned conversions:

- compressed media converter accepts the already verified digest;
- PCM WAV converter accepts the already verified digest;
- RAW Opus converter accepts the already verified digest and the coordinator's validated framing result.

Standalone converter use still falls back to its own SHA verification when no verified digest is supplied.

Output canonical SHA, Room lineage, source SHA lineage and corruption detection remain intact.

### 3.2 Reusable streaming buffers

The hot path now reuses bounded buffers instead of allocating a fresh conversion chain for every decoder block:

- Android MediaCodec output `ShortArray` is reused and only grows when required;
- `StreamingPcm16Normalizer` has an allocation-free `processInterleavedInto` hot-path API;
- compressed-media normalization reuses its mono output buffer;
- PCM WAV conversion reuses byte / interleaved-short / mono buffers;
- canonical WAV writer reuses a 64 KiB little-endian byte buffer;
- PCM WAV streaming chunk size is 64 KiB.

The legacy allocation-returning normalizer API remains for compatibility and tests.

### 3.3 Existing canonical WAV Fast Path preserved

An imported WAV that is already valid canonical PCM16 / 16 kHz / mono is still reused in place.

It is not copied to a second physical canonical file. `IMPORTED_ORIGINAL` and `CANONICAL_WAV` may point at the same managed relative path. Existing cleanup protections for shared-path assets remain in force.

## 4. Real canonical generation progress

High-frequency progress is **process-local StateFlow state**, not Room state. Room continues to hold durable lifecycle / terminal state only.

Runtime progress provides:

- phase;
- processed audio duration;
- total duration when known;
- processed units / total units where available;
- real fraction when determinable;
- start/update timestamps;
- cancellable flag.

Progress sources:

- PCM WAV: exact processed frames / total frames;
- compressed media: actual decoded PCM frames / known media duration;
- RAW Opus: exact processed packets and decoded sample duration.

The runtime throttles intermediate UI publication to about 4 Hz and always allows the first and terminal real progress sample through.

## 5. Recording Detail / Player UX

The recording player now has three canonical states.

### Not generated

The player card itself shows:

- `需要生成标准 WAV`;
- explanation that generation enables playback, transcription and speaker analysis;
- integrated primary `生成标准 WAV` action.

### Generating

The player card shows:

- `正在生成标准 WAV`;
- real phase label;
- real determinate progress when the total is known, otherwise indeterminate progress;
- `已处理 xx:xx / xx:xx`;
- `已耗时 xx:xx`;
- compact trailing text action `取消` while the operation is cancellable;
- disabled waveform/playback/speed preview;
- a hint that playback/transcription/speaker analysis becomes available after generation.

The old standalone full-width `取消生成标准 WAV` action was removed from the recording page. VERIFYING / COMMITTING are presented as non-cancellable finalization phases.

### Ready

The normal waveform player is restored.

## 6. Recording information card

The recording information card now contains, in order:

1. 录制时间
2. **录音时长**
3. 文件大小
4. 来源 / 格式

`录音时长` uses real `mediaDurationMs` or `deviceReportedDurationMs`; it is not estimated from file size.

## 7. Device download notification terminal-state fix

True-device regression observed before P0:

> The file had completed downloading locally, but the Android connected-device foreground notification could remain at `正在下载 · 95%`.

Root cause addressed in P0:

- every device-session foreground publish already had a generation;
- a stale queued `ACTION_ACQUIRE` could previously call `startForeground()` before diagnostics rejected the old generation;
- the service now rejects stale generations **before** publishing the notification.

Therefore an old 95% progress command cannot overwrite a newer terminal `已连接` state. `markIdle()` also advances the generation so commands arriving after release are stale.

The existing post-download repository reconciliation remains in place.

## 8. Canonical performance instrumentation

Successful canonical generation publishes an in-process benchmark snapshot and a Logcat line with tag:

`VoicaCanonicalPerf`

The compact line contains:

- recording id;
- source role / container / source bytes / duration;
- `sourceShaMs`;
- `decodeReadMs`;
- `normalizeMs`;
- `writeMs`;
- `syncMs` (`fd.sync()`);
- `moveMs`;
- `outputShaMs`;
- `pipelineMs`;
- `roomCommitMs`;
- `totalMs`;
- `rtf` when duration is known.

This instrumentation is intended to distinguish duplicate-I/O, decode, normalization, sequential write, final storage flush and output-hash costs during 30 / 60 / 120 minute true-device tests.

## 9. Deferred optimization

P0 does **not** yet change the downstream `RoomAudioSourceResolver` integrity contract that can full-SHA canonical audio when playback / ASR / diarization resolves it.

A process-local verified-canonical cache remains conditional P0.8 work. Only implement it if true-device profiling proves that downstream verification materially affects startup or sustained workload. Do not weaken the integrity contract based only on static analysis.

## 10. Required true-device QA

### Canonical generation UX

- import a supported MP3/M4A/AAC/FLAC/Ogg Opus sample;
- confirm automatic/manual canonical generation enters the player-card progress state;
- confirm percentage is monotonic when total duration is known;
- confirm processed time advances and never exceeds total;
- confirm elapsed time advances once per second;
- confirm trailing `取消` works during cancellable conversion;
- confirm no standalone full-width canonical cancel button remains;
- confirm VERIFYING / COMMITTING do not offer a misleading cancel action;
- confirm completion immediately restores normal playback UI;
- confirm playback, waveform, transcription and diarization still resolve the canonical asset.

### Canonical WAV Fast Path

- import an already canonical PCM16 / 16 kHz / mono WAV;
- confirm generation completes without a long decode/resample cycle;
- confirm playback/transcription work;
- confirm cleanup does not delete the shared original/canonical physical file incorrectly.

### Recording information

- confirm `录音时长` appears directly under `录制时间`;
- verify both local imports and device downloads show the real duration.

### Device download notification

- reproduce a normal CB08/QS668 file download and observe notification progress;
- after successful local completion, confirm the notification changes from `正在下载 · xx%` to the actual connected state (`已连接`) instead of remaining at 95/99/100%;
- repeat with the app in background;
- cancel a download and confirm stale percentage disappears;
- test download failure while connection remains;
- download two files consecutively and confirm terminal state from file #1 never overwrites progress for file #2;
- complete a download and immediately start recording; notification should switch to recording state and old download progress must not reappear.

## 11. 30 / 60 / 120 minute benchmark

Use the same physical phone and, where possible, samples cut from the same source with identical codec / bitrate / sample-rate / channel configuration.

For each duration capture the `VoicaCanonicalPerf` line and wall-clock behavior.

Primary comparison:

- `RTF = canonical total wall time / source duration`;
- `T60 / T30`;
- `T120 / T60`;
- source SHA share;
- decode/read share;
- normalization share;
- write + sync share;
- output SHA share.

The goal is not an invented fixed speed-up number. The goal is a materially lower and more stable long-duration RTF, with no recurrence of the previous observation where doubling recording duration appeared to produce roughly triple wall time.

## 12. Regression matrix

At minimum cover:

- MP3;
- M4A/MP4 AAC;
- AAC ADTS;
- FLAC;
- Ogg Opus;
- canonical PCM WAV;
- noncanonical PCM16 WAV;
- QS668/CB08 RAW Opus;
- QS668/CB08 WAV;
- automatic canonical generation;
- manual retry;
- cancellation;
- app backgrounding;
- process restart/recovery;
- insufficient storage behavior;
- corrupted source handling;
- canonical cleanup;
- recording deletion;
- playback/waveform;
- ASR;
- diarization;
- original/canonical export.

## 13. Merge / Freeze rule

Do **not** merge PR #26 and do **not** create a Post-V1 P0 Freeze until:

1. the `[APK]` Fast Gate passes;
2. the QA APK Gate passes;
3. true-device functional QA passes;
4. the device-download notification issue is confirmed fixed;
5. 30 / 60 / 120 minute benchmark evidence is collected or explicitly accepted as deferred by the user;
6. any QA findings are resolved and re-tested.

After acceptance, create the final Post-V1 P0 Freeze / Handoff update and only then continue to Stage 15.
