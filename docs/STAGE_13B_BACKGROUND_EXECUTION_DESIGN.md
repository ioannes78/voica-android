# Stage 13B.2 — Background execution boundary

Status: **IMPLEMENTING / architecture boundary frozen for Stage 13B**

Repository: `ioannes78/voica-android`

Development branch: `stage13b-stability-background-longrecording`

Starting baseline: Stage 13A merged `main` at `29fbc973698a84408cf99e4213d73996b8147f1d`.

Room remains **v7**. This design does not add a generic long-task database and does not modify the production model channel.

## 1. Design rule

A foreground service, JobScheduler job, Worker or coroutine is an **executor**, not business truth.

Existing Room entities remain authoritative for canonical conversion, transcription, diarization and AI summary. File-system staging/commit metadata remains authoritative for downloadable/installable artifacts where that is already the product design.

No UI screen, Activity, ViewModel or Composable may be the sole lifetime owner of a required minutes-long operation.

## 2. Android platform constraints rechecked for targetSdk 37

The Stage 13B branch targets Android API 37. The implementation follows current Android background-work rules:

- `connectedDevice` foreground service is the specific FGS type for sustained Bluetooth accessory interaction and requires `FOREGROUND_SERVICE_CONNECTED_DEVICE` plus a qualifying Bluetooth permission at runtime.
- `mediaPlayback` is the specific FGS type for lock-screen/background audio playback and requires `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- `mediaProcessing` is the specific FGS type for time-consuming media processing and requires `FOREGROUND_SERVICE_MEDIA_PROCESSING`.
- On Android 15+ / apps targeting API 35+, `mediaProcessing` and `dataSync` each have a shared six-hour foreground-service budget per 24-hour period while backgrounded. Services must stop promptly from `Service.onTimeout(...)`.
- Android 16 job/runtime quota changes mean a long-running WorkManager Worker is not a blanket replacement for direct foreground execution.
- Android 14+ User-Initiated Data Transfer (UIDT) jobs are the preferred boundary for genuinely long, user-started remote file transfers that require immediate progress notification.

Reference documentation:

- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/develop/background-work/background-tasks/data-transfer-options
- https://developer.android.com/develop/background-work/background-tasks/uidt
- https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running

## 3. Frozen Stage 13B execution matrix

| Workload | Stage 13B executor boundary | Durable truth | Notes |
| --- | --- | --- | --- |
| Active QS668/CB08 recording session | `connectedDevice` FGS | recorder device + repository reconciliation | recording continuity has highest priority; no phone `AudioRecord` recorder is introduced |
| BLE reconnect needed by active recording/download | `connectedDevice` FGS | remembered device + device truth after reconnect | foreground/home/lock must not cancel required reconnect solely because Activity stopped |
| BLE recording-file transfer | `connectedDevice` FGS | final registered recording asset; `.part` is never complete | protocol resume only if real device evidence proves safe offsets/identity |
| Local playback | `mediaPlayback` FGS + platform `MediaSession` | source Recording asset; playback session is runtime state | same `AudioTrack` runtime is shared by UI and system controls; no second player |
| Canonical audio generation | `mediaProcessing` FGS | existing Room derivation state | app must stop FGS at terminal state; process death still reconciles via Room |
| Offline ASR | `mediaProcessing` FGS | existing Room Transcription | executor carries business IDs; does not duplicate transcription state machine |
| Speaker diarization/alignment | `mediaProcessing` FGS | existing Room diarization/alignment state | same principle as ASR |
| Model package network download | Android 14+: evaluate/implement UIDT; older Android: user-started durable transfer fallback | package `.part` + manifest identity | do not use long-running WorkManager blindly on Android 16+ |
| Model checksum/extraction/static verify/runtime validation | durable model-install orchestrator; exact transfer/post-processing split finalized in Stage 13B.4 | staging/install metadata + isolated validator result | never mark READY before runtime validation and atomic activation |
| Local large import/export | user-started durable data-transfer boundary; exact API/fallback finalized with long-file implementation | staging/final destination | ViewModel must cease being sole owner |
| AI summary | durable network orchestration; normal WorkManager is acceptable only where work stays bounded/resumable | existing Room summary/checkpoints | preserve Stage 13A cancellation/late-response terminal-state rules |
| Future Stage 16 realtime ASR | reserved compatible session/executor seam only | Stage 16 frozen streaming contract | no realtime product implementation in Stage 13B |

## 4. Playback boundary implemented in Stage 13B.2

The previous behavior tied playback to `ProcessLifecycleOwner`: leaving the foreground called `setAppForeground(false)`, which paused playback and abandoned Audio Focus.

Stage 13B removes that policy.

The playback architecture is now:

`Compose Playback UI`
→ `ServiceBackedPlaybackController`
→ **one** `AndroidPlaybackController`
→ **one** `StreamingPlaybackController / AudioTrack`

and, in parallel:

`PlaybackForegroundService / MediaSession`
→ the **same** `AndroidPlaybackController` runtime.

Therefore:

- Home and lock screen do not create a second player;
- the canonical absolute 16 kHz sample timeline remains the playback truth;
- system play/pause/seek/rewind/fast-forward commands operate on the same session;
- Audio Focus and noisy-output handling remain inside the existing playback engine;
- transient Audio Focus loss may resume even while the Activity is backgrounded;
- device recording interlock still pauses playback because recording has higher priority.

The service deliberately uses the platform `MediaSession` rather than replacing the custom `AudioTrack` engine with ExoPlayer/Media3. Stage 13B is a reliability stage; replacing the already accepted sample-accurate playback core would add unnecessary regression surface.

## 5. Foreground-service hosts created in Stage 13B.2

Three explicit service types now exist:

1. `PlaybackForegroundService` — `mediaPlayback`.
2. `DeviceSessionForegroundService` — `connectedDevice`.
3. `MediaProcessingForegroundService` — `mediaProcessing`.

Only playback is wired to product behavior in Stage 13B.2.

The device and media-processing hosts are deliberately inert until their owning workflows are integrated in the following Stage 13B sub-stages. Merely declaring a service must never change BLE protocol state or start media processing.

`MediaProcessingForegroundService` keeps only process-local execution leases. It does **not** persist a second task state. If the process dies, existing Room startup reconciliation remains responsible for converting stale active business states into interrupted/recoverable states.

## 6. mediaProcessing timeout contract

Android 15+ may call `Service.onTimeout(startId, fgsType)` after the app exhausts its `mediaProcessing` foreground budget.

The foreground host stops itself immediately. Workflow integrations added later in Stage 13B must additionally ensure that the associated coordinator is cancelled/marked interrupted before or as the lease is released. A timeout must never result in a false `COMPLETED` transcription/diarization/canonical state.

The 30/60/120 minute acceptance matrix must include cumulative `mediaProcessing` duration so the six-hour shared budget is observable during stress testing.

## 7. Notification policy

Foreground notifications follow these rules:

- truthful state only;
- exact byte progress only when exact byte totals exist;
- no fabricated ASR/diarization percentage;
- notification cancel action and in-app cancel action must converge on the same workflow cancellation path;
- terminal state cannot be revived by a late callback;
- playback notification exposes system transport controls through MediaSession.

`POST_NOTIFICATIONS` is declared for Android 13+. Stage 13B product integration must still handle runtime permission UX where a normal FGS notification is expected; lack of notification permission must not be treated as task success/failure.

## 8. Concurrency priority

Priority order for Stage 13B resource arbitration:

1. active device recording and its required BLE session;
2. active local playback requested by the user;
3. BLE file transfer requested by the user;
4. user-visible model/data transfer;
5. canonical generation required by a current user action;
6. offline ASR / diarization;
7. background maintenance and automatic model checks.

Heavy work may be deferred, serialized or reduced when evidence shows it destabilizes a higher-priority recording session. Stage 13B does not change model selection merely to achieve this.

## 9. Data transfer decision deferred intentionally

Stage 13B.2 does **not** prematurely add a `dataSync` service or UIDT `JobService` before model/download/import/export code is integrated.

Stage 13B.4 must choose the concrete API using these rules:

- user manually downloads a large model on API 34+: UIDT is preferred;
- short/retryable background network work: WorkManager remains appropriate;
- local post-processing is not mislabeled `mediaProcessing` merely because it is CPU-heavy;
- any `dataSync` FGS fallback must implement Android 15 timeout behavior and be user-started;
- a model package's network transfer and its extraction/runtime validation may use different executors while remaining one product operation.

## 10. Room gate

No Stage 13B.2 requirement establishes a need for Room v8.

Room stays **v7**.

If later process-safe BLE transfer identity or model-install orchestration cannot be represented with the current business/file truth plus a narrowly scoped operation journal, implementation must stop and submit the previously agreed schema-change proposal before any migration is written.

## 11. Stage 13B.2 acceptance gate

Before Stage 13B.2 is considered complete:

- app compiles against targetSdk 37;
- all existing unit tests remain green;
- playback focus policy tests reflect background-resume semantics;
- Manifest foreground-service permissions/types pass Android build validation;
- playback uses exactly one runtime instance;
- no Room schema change;
- no production model-channel change;
- no BLE protocol-core rewrite;
- PR CI succeeds.

Real lock-screen playback and hardware media-control acceptance belongs to the next real-device candidate gate after CI.
