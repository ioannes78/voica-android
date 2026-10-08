# Stage 13B.1 — Long-task architecture audit

Status: **AUDITED / NO PRODUCT BEHAVIOR CHANGE**

Stage: **13B — stability, background execution, and real long-recording validation**

This document records the repository truth inspected before Stage 13B background-execution work. It is an architecture audit only. It does not change runtime behavior, Room schema, the Stage 13A model matrix, or the production model channel.

## 1. Verified baseline

- Repository: `ioannes78/voica-android`
- Production branch: `main`
- Stage 13B starting `main` HEAD: `29fbc973698a84408cf99e4213d73996b8147f1d`
- Stage 13B development branch: `stage13b-stability-background-longrecording`
- Stage 13A PR: `#16`, merged into the starting HEAD above
- Room schema: **v7**
- Stage 13A app version at handoff: `versionCode 48`, `versionName 0.13.0-stage13a-qa6`
- Production model channel repository: `ioannes78/voica-model-channel`
- Production model channel HEAD at audit time: `e4e64d29b8c92b97de4298ec6e292c33273f3ba4`

The production model channel remains read-only in Stage 13B unless an explicit later decision authorizes a change.

## 2. Audit conclusions

The current application does not have one durable long-task execution boundary. Long work is split among:

1. UI/ViewModel/Composable coroutine scopes;
2. the application process `applicationScope`;
3. BLE repository/session-owned in-memory jobs;
4. an isolated model-validation process;
5. Room-backed business state and startup reconciliation for selected workflows.

The persisted business truth is already stronger than the execution layer for canonical conversion, transcription, diarization, and AI summary. Stage 13B must therefore **add durable execution ownership around existing business state**, not create a second competing task-state database.

Three current lifecycle gaps are P0:

- BLE download/reconnect/polling is intentionally stopped or cancelled when the Activity leaves the foreground.
- Manual model install/activation is launched from a Compose-owned coroutine scope, so the screen lifecycle can own a minutes-long download/extract/validation operation.
- Playback is intentionally paused when the app process leaves the foreground, which is incompatible with the confirmed Stage 13B requirement for continuous background/lock-screen playback.

Large import/export operations are also ViewModel-owned today and require Stage 13B long-task treatment.

## 3. Current long-task matrix

| Workload | Current execution owner | Durable truth today | Current background/process behavior | Stage 13B disposition |
| --- | --- | --- | --- | --- |
| QS668/CB08 recording control | `DeviceViewModel` command → `DeviceRepository` / `AndroidDeviceSession` | device is the recording authority; app state is process-local | leaving foreground stops recording polling and reconnect work; no phone-microphone capture path was found | preserve device-side recording protocol; harden background connection/status reconciliation and real-device recording continuity |
| BLE recording-file download | `DefaultDeviceRepository` + `FileTransferSession` | final downloaded asset is persisted/registered; active transfer state is in memory | `setForeground(false)` requests `BACKGROUND` cancellation; transfer ownership is process-local | durable connected-device execution, idempotent task ownership, truthful notification, interruption recovery |
| BLE long connection / reconnect | `DefaultDeviceRepository` process-local jobs | remembered address only; connection state is volatile | reconnect job is cancelled on background | Stage 13B.2 must define when an active recording/download/device session warrants background connected-device execution |
| Local playback | application-owned `AndroidPlaybackController` + `StreamingPlaybackController` | source asset is durable; playback session is volatile | current code pauses and abandons audio focus when app goes to background | move playback execution ownership to a `mediaPlayback` foreground service; preserve current sample-accurate AudioTrack engine; support Home/lock-screen/background continuity plus MediaSession/system controls |
| Waveform overview | screen request → `WaveformOverviewRepository` | small cache file | bounded sampled overview; not a long full-file scan | retain design; stress-test long recordings, no redesign required |
| Canonical PCM/WAV generation | `CanonicalAudioCoordinator` on application scope | Room derivation state + canonical asset registration | can survive screen exit while process lives; process death reconciles interrupted derivations on startup | treat as local media-processing workload for long recordings; preserve Room truth and reconciliation |
| Local audio import | `RecordingLibraryViewModel.viewModelScope` → `LocalAudioImportCoordinator` | only committed imported asset is durable | long copy/validation is ViewModel-owned; cancellation deletes staging | include long-file background/lifecycle assessment; staging/final commit semantics must remain safe |
| Local audio export/share preparation | `RecordingLibraryViewModel.viewModelScope` → `LocalAudioExportCoordinator` | destination output itself; no durable active task | long copy can be cancelled with ViewModel/page lifecycle; canonical export may first trigger canonical generation | include long-file background/lifecycle assessment; avoid false completion and partial user-visible output |
| Model package download | manual UI uses `rememberCoroutineScope` → `DefaultModelManager` | `.part` may exist, but active operation state/job is process memory | UI cancellation can cancel install; explicit cancellation deletes `.part`; HTTP downloader itself supports Range resume | move ownership out of the model page; preserve safe Range resume and byte progress |
| Model package verify/extract | `DefaultModelManager` caller coroutine | staged/final installed files | same caller lifecycle as install; staging is cleaned on failure/cancel | durable install orchestration; staging never equals READY; preserve bounded extraction and path safety |
| Runtime model validation | caller binds `IsolatedModelValidationService` in `:model_validator` | temporary result file; installed candidate metadata | native validation is isolated from main process, but orchestration remains caller/process-owned; 240 s timeout | retain isolated crash containment; make orchestration durable and terminal-state safe |
| Model activation | `confirmInstalledVersion()` → validator → `ModelStorage.confirmGood()` | activation metadata | short final transaction after validation | keep atomic activation; existing active model must remain usable until candidate passes runtime validation |
| Offline transcription | `TranscriptionCoordinator` on application scope | Room transcription state/results | process-local Job; startup reconciliation marks interrupted work | add durable executor/foreground ownership while keeping Room as task truth; no second transcription state machine |
| Speaker diarization/alignment | `DiarizationCoordinator` on application scope | Room diarization/alignment state/results | process-local Job; startup reconciliation exists | same principle as transcription; preserve Stage 13A CAM++ product matrix |
| AI summary | `AiSummaryCoordinator` on application scope | Room summary state/checkpoints/results | process-local Job; interrupted summaries can be reconciled/resumed | durable network-work boundary without weakening frozen cancellation/late-response rules |
| Stage 16 realtime transcription | **not implemented as a product flow in Stage 13B** | frozen streaming-ASR contract only | no live capture/execution owner yet | reserve compatible execution/capture ownership seam only; do not implement Stage 16 product scope |

## 4. Recording-specific finding

Current Voica recording control is a **QS668/CB08 device-side recording workflow**. The app sends BLE record start/pause/resume/save commands and observes device recording state. The audit did not find an Android `AudioRecord` phone-microphone capture path.

Therefore Stage 13B must not accidentally build a phone-microphone recorder service as a substitute for the existing product behavior.

The actual Stage 13B recording problem is:

- maintain the required BLE/device session while a device recording is active;
- avoid losing status/reconciliation just because the Activity goes to Home/lock screen;
- correctly recover after Bluetooth disconnect/reconnect;
- reconcile the real device recording state after foreground return/process restart where technically possible;
- validate 30/60/120 minute **real device recordings**;
- give recording stability priority over competing model download, ASR, diarization or storage-heavy work.

Current `DefaultDeviceRepository.setForeground(false)` does **not** send a record-stop command, but it stops recording polling and cancels reconnect work. Whether the recorder continues correctly through every disconnect/background case is a real-device acceptance question, not something Stage 13B may assume from code alone.

## 5. BLE download finding

The low-level file sink already has several strong properties that Stage 13B should preserve rather than rewrite:

- bounded streaming writes;
- incremental SHA-256 calculation;
- expected-size validation;
- `flush` + `fd.sync()` before finalization;
- temporary `.part` file;
- atomic move where supported, with controlled fallback;
- downloaded WAV container validation;
- final Room/library registration only after local commit.

The missing reliability layer is mainly **execution ownership and durable active-operation recovery**, not basic byte streaming.

Normal device download currently starts at offset `0`. A range-probe implementation exists, but that is not evidence that production transfer resume is safe. Stage 13B must only implement BLE partial resume if protocol/device behavior proves identity, offset and continuation semantics. Otherwise an interrupted BLE download must restart safely instead of pretending to resume.

## 6. Playback and waveform finding

Playback already uses canonical PCM/WAV bounded streaming:

- `AudioTrack` streaming output;
- 32 KiB read buffer;
- absolute canonical 16 kHz sample index;
- sample-accurate seek addressing;
- explicit source/sink close on unload/release;
- Audio Focus handling;
- noisy-output handling;
- device-recording/playback interlock.

Existing automated tests cover synthetic 30/60/120 minute seek addressing and bounded reads, including rapid seek on a two-hour logical source. These are useful regression tests but **do not replace real-device long-recording evidence**.

Waveform overview is already bounded by design: it samples short windows at a fixed number of positions rather than loading or rescanning the whole recording. Stage 13B should stress-test this implementation instead of replacing it.

The confirmed Stage 13B product requirement is now:

- pressing Home must **not** pause playback;
- locking the device must **not** pause playback;
- switching to another app must **not** pause playback;
- playback must expose a persistent media notification while active;
- lock screen / notification controls must support at least play/pause and seek-position presentation, with previous/next omitted unless the product later defines playlist semantics;
- audio focus, Bluetooth/headset route changes and `AUDIO_BECOMING_NOISY` behavior must remain correct;
- returning to the app must attach to the existing playback session rather than creating a second player;
- playback must stop/release deterministically when the user stops it, the source disappears, or a terminal playback error occurs.

Implementation direction for Stage 13B.2:

- retain `StreamingPlaybackController` and its canonical sample-index timing as the playback engine;
- move ownership of the active playback session out of Activity/process-foreground policy and into a dedicated playback service;
- use Android `mediaPlayback` foreground-service semantics;
- expose the existing engine through a MediaSession-compatible control boundary so system UI/lock-screen controls and in-app controls observe one session;
- remove the current `setAppForeground(false) -> pause()` product behavior once the service path is in place;
- do **not** replace canonical sample timing with MediaPlayer/ExoPlayer milliseconds merely to obtain background playback.

Because the app targets API 37, Stage 13B.2 must also validate the current Android 17 background-audio requirements and ensure the playback foreground service is started from a user-initiated/visible-app path before the app goes to background.

## 7. Canonical conversion finding

Canonical conversion is a long-running prerequisite that must be included in the Stage 13B resource and process-death matrix.

The current coordinator already:

- serializes generation;
- persists derivation state in Room;
- uses `.part` output;
- handles cancellation and terminal states;
- reconciles interrupted derivations at startup;
- can recover a valid final canonical file after interruption and register it.

For 30/60/120 minute device and imported recordings, Stage 13B must record canonical-generation wall time, storage growth and memory behavior in addition to ASR/diarization metrics.

## 8. Import/export finding

Large file import and export are currently UI/ViewModel-owned long I/O operations.

Import already has useful safety mechanisms:

- staging `.part` file;
- streaming SHA-256;
- periodic free-space checks;
- source validation;
- duplicate detection;
- final rename/registration only after validation;
- staging cleanup on cancellation/failure.

Export performs cancellable streamed copies and cleans incomplete destinations for supported targets. However, a 1–2 hour recording can make import/export materially long, so Stage 13B must decide whether these operations need durable foreground execution or another lifecycle-safe owner.

## 9. Model-install finding

The low-level model package implementation is already suitable for a durable orchestrator:

- HTTPS-only package download;
- HTTP Range continuation when the server proves valid `Content-Range`;
- restart from zero when continuation is not honored;
- true byte progress;
- expected package size validation;
- package SHA-256 verification;
- storage-space preflight;
- staging-directory extraction;
- bounded 64 KiB extraction/copy buffers;
- extraction limited to manifest-declared files;
- safe relative-path checks;
- separate runtime validation before `confirmGood()` activation.

The lifecycle weakness is that manual install/activate is launched from `ModelManagerCard` using `rememberCoroutineScope`, while `DefaultModelManager` stores operation Jobs and status in process memory. The model page must no longer own minutes-long package download/extract/validation work.

Stage 13B must preserve the distinction:

`DOWNLOADED` → `PACKAGE VERIFIED` → `EXTRACTED/STAGED` → `STATICALLY INSTALLED CANDIDATE` → `RUNTIME VALIDATED` → `ACTIVE/READY`

No intermediate state may be presented as an active usable model.

## 10. Offline ASR, diarization and AI summary

These workflows already persist their business state in Room and already have startup reconciliation. Their primary Stage 13B deficit is the executor lifecycle.

Stage 13B must not replace these repositories with a generic long-task table merely for convenience. The background executor should carry a stable business identifier (recording/transcription/diarization/summary ID), observe/publish progress, enforce cancellation and terminal-state ownership, and let the existing repository remain authoritative.

AI summary must preserve Stage 13A cancellation semantics in particular:

- cancellation is not converted into a normal network failure;
- CANCELLED cannot be revived by late provider callbacks;
- a late response cannot overwrite terminal state;
- an already committed COMPLETED transaction remains authoritative;
- resumable interrupted work must use the frozen input lineage/checkpoint semantics.

## 11. Stage 16 realtime compatibility boundary

`docs/STAGE_16_STREAMING_CONTRACT_V2.md` remains frozen and authoritative for future local realtime ASR.

Stage 13B may prepare only lifecycle-compatible infrastructure, such as:

- a single long-lived audio/device-session owner boundary;
- a way for a future live pipeline to attach to canonical 16 kHz mono PCM epochs;
- discontinuity/cancel/session ownership semantics;
- foreground/background notification ownership that can later host live work without inventing a second incompatible execution framework.

Stage 13B must **not** implement the Stage 16 live microphone/BLE capture product, live stabilizer, live transcript UI, online realtime provider API, or new realtime model routing.

It must also not modify the frozen `StreamingAsrSession` contract or restore removed realtime models/AUTO routing.

## 12. Resource-priority rule

Stage 13B introduces this scheduling requirement for later implementation:

**An active device recording is the highest-priority audio operation.**

Heavy discretionary work must not destabilize recording. Stage 13B.2 must therefore define an explicit concurrency policy for at least:

- model download/extract/runtime validation;
- canonical conversion;
- offline ASR;
- diarization;
- export/import;
- playback;
- BLE file download.

Background playback is a user-visible active media task and must remain responsive, but if resource contention is proven on real devices, active device recording wins over discretionary CPU-heavy processing. The policy may pause, defer, reject or reduce competing work where needed, but must be evidence-driven and must not silently change Stage 13A model behavior.

## 13. P0 gaps to solve after this audit

### P0-A — Activity foreground is currently a BLE execution boundary

`MainActivity.onStop()` leads to `DeviceRepository.setForeground(false)`, which currently:

- cancels an active BLE file transfer with a background reason;
- cancels reconnect work;
- stops recording polling;
- stops scanning.

That is incompatible with the Stage 13B requirement for lock-screen/background download and stable active-recording connectivity.

### P0-B — model page owns manual model install/activation

Manual model download/install and runtime validation are launched from a Compose scope. Leaving/disposal of that UI can own cancellation of work that can take minutes.

### P0-C — ASR/diarization/canonical/summary executors are process-local

Their Room truth/reconciliation is usable, but the active executor does not survive process death and has no system foreground ownership for long-running user work.

### P0-D — import/export are ViewModel-owned

Large user-selected audio can make these operations long enough that a screen/ViewModel scope is an insufficient owner.

### P0-E — playback foreground state is tied to app foreground state

`ProcessLifecycleOwner.onStop()` calls `playbackController.setAppForeground(false)`, and the current controller pauses playback and abandons audio focus. This is incompatible with the confirmed Stage 13B background/lock-screen playback requirement.

Stage 13B.2 must move active playback ownership to a user-started `mediaPlayback` foreground service and keep one shared playback session for in-app, notification and lock-screen controls.

## 14. Stage 13B.2 design inputs

Stage 13B.2 must decide the exact boundary among Foreground Service, WorkManager, process coroutine and isolated process using current Android platform rules.

The design must satisfy all of the following:

1. no UI screen owns a required long task;
2. Room/business repositories remain the authority where they already persist task truth;
3. no unnecessary Room v8 migration is introduced;
4. a service/worker is an executor, not a second business database;
5. notifications expose truthful bytes/phases and one cancellation path;
6. duplicate starts attach/reject deterministically;
7. process death cannot produce false COMPLETED/READY states;
8. terminal state cannot be revived by stale callbacks;
9. model activation remains atomic after runtime validation;
10. device recording continuity has priority over heavy background work;
11. active playback continues through Home/lock-screen/app switching under a `mediaPlayback` foreground service, while preserving the existing canonical AudioTrack/sample-index engine and Audio Focus/noisy-route semantics;
12. in-app controls, notification controls and lock-screen controls must address one playback session and one authoritative position;
13. Stage 16 streaming contract remains frozen.

Before Stage 13B.2 code is written, Android foreground-service/service-type/WorkManager/background-audio rules must be rechecked against the app's current `targetSdk` and official Android documentation.

## 15. Room gate

**Audit result: Stage 13B.1 does not establish a need for Room v8.**

Room remains v7.

If Stage 13B.2 later proves that a durable state cannot be represented safely by existing Room entities plus a narrowly scoped non-Room operation journal, implementation must stop and submit a Room schema-change proposal before any migration is written.

## 16. Stage 13B.1 gate result

**PASS — architecture audit complete, including confirmed background/lock-screen playback scope.**

No runtime behavior was changed in Stage 13B.1.

Next stage after review: **Stage 13B.2 — durable execution boundary / Foreground Service / WorkManager design**, including device recording continuity, background/lock-screen playback, and future Stage 16 realtime reservation.
