# Stage 17.2 QA Revision-1 — Background Execution & Task Visibility

Status: **CONFIRMED / IMPLEMENTING**

Repository: `ioannes78/voica-android`

Branch: `stage17-fully-offline-intelligence`

This revision is a narrow post-QA amendment to the frozen Stage 17.2 Offline Summary V1 contract. It does not change the local LLM model contract, prompt/evidence architecture, Room schema, direct-summary scope, or Stage 17.3 boundary.

## 1. Reason for revision

Real-device Stage 17.2 QA passed the summary product path except task visibility. Follow-up source review found that recording, offline ASR/diarization, and local summary had different Android background-execution guarantees:

- phone microphone capture already used microphone FGS + PARTIAL_WAKE_LOCK;
- recorder-card session already used connectedDevice FGS;
- offline ASR/diarization used mediaProcessing FGS but no dedicated screen-off CPU lease;
- Stage 17.2 local summary was application-scope only and therefore did not provide an Android-level lock-screen execution guarantee;
- summary was absent from the app-global task surface and Android running/completion notifications.

## 2. Frozen superseding rule

The earlier Stage 17.2 restriction against adding an execution FGS solely for local summary is superseded by this revision.

A user-initiated local summary must continue across:

- navigation away from the Summary page;
- Activity recreation;
- Home/background transition while the process remains alive;
- screen-off / lock screen while the process remains alive.

The revision still does **not** promise process-death continuation or automatic resume. If the process dies, existing startup reconciliation remains authoritative and non-terminal summary rows become `INTERRUPTED`.

## 3. Summary execution boundary

Stage 17.2 continues to use exactly one product `OfflineSummaryCoordinator` and exactly one P1 `AppLocalLlmRuntime`.

Android execution protection is presentation/execution ownership only:

`OfflineSummaryCoordinator state`
→ process-level summary execution controller
→ dedicated `LocalAiForegroundService`
→ run-scoped `PARTIAL_WAKE_LOCK`

No second LLM runtime, Engine, Conversation, checkpoint state machine, WorkManager pipeline, or cloud fallback is introduced.

The dedicated service uses Android `specialUse` because local text LLM summarization does not truthfully fit the existing `mediaProcessing`, `dataSync`, `microphone`, `connectedDevice`, or `mediaPlayback` service types. The manifest subtype explicitly describes user-initiated on-device transcript summarization.

## 4. Summary task visibility

While the originating Summary page is visible, no redundant app-global summary bar is shown.

After navigating away, the app-global summary surface shows the same coordinator state:

- `PREPARING` → 本地总结 · 正在准备
- `ANALYZING` → 本地总结 · 正在总结
- `VALIDATING` → 本地总结 · 正在校验
- `COMPLETED` → 本地总结完成
- `FAILED` → 本地总结失败
- `CANCELLED` → 本地总结已取消

Terminal presentation remains until the corresponding Summary page is actually viewed in the current process.

Android system behavior:

- an ongoing low-importance FGS notification is present during local summary;
- the notification opens the corresponding recording Summary page;
- the notification exposes the same user cancellation path;
- successful completion posts a normal result notification;
- failure posts a truthful failure notification;
- no fabricated percentage is shown.

## 5. Speaker/timeline input remains unchanged

This revision does not alter the accepted evidence contract.

Speaker metadata is included in the structured transcript prompt when available. Canonical audio coordinates remain WENZHI-owned and are not delegated to the LLM. The model receives evidence IDs such as `[S00001]`; validated result evidence is mapped back to `startSampleIndex/endSampleIndexExclusive` after inference.

Principle remains:

> LLM 负责语义，WENZHI 负责时间坐标。

## 6. Offline ASR / diarization screen-off hardening

The existing `MediaProcessingForegroundService` remains the Android execution host for offline transcription and diarization.

Revision-1 adds one shared process-local run-scoped `PARTIAL_WAKE_LOCK` while at least one media-processing task is active. The lock is released when the final task releases, on service destruction, and on platform timeout.

Business truth remains in the existing coordinators and Room entities.

## 7. mediaProcessing timeout closure

`MediaProcessingForegroundService.onTimeout(...)` must not merely remove its notification.

On platform timeout it now:

1. identifies active transcription/diarization task ownership;
2. cancels the associated coordinator path;
3. clears process-local execution leases;
4. releases the partial wake lock;
5. stops foreground state and the service.

Existing coordinator cancellation/persistence remains responsible for the terminal business state. A timed-out Android execution lease must never leave invisible inference running indefinitely.

## 8. Non-goals

Revision-1 does not:

- modify Room v16;
- alter Gemma 4 E2B / LiteRT-LM identity;
- alter prompt structure, evidence IDs, speaker mapping, or canonical timeline mapping;
- add long-transcript map/reduce;
- add Stage 17.3 checkpoint/resume;
- add WorkManager for local summary;
- change BLE protocol, recording, playback, ASR model selection, or diarization model selection;
- permit cloud fallback.

## 9. Required validation

Automated gates:

- Android compile/unit tests;
- manifest foreground-service validation;
- existing Stage 17/P1 R8/JNI boundary unchanged;
- existing recording/ASR/diarization tests remain green.

Focused real-device regression after CI:

1. Summary running app-global status after leaving Summary page.
2. Summary Android ongoing notification and notification cancel.
3. Summary Home/background continuation.
4. Summary lock-screen/screen-off continuation.
5. Summary completion notification and return-to-Summary navigation.
6. Summary cancellation leaves no zombie task/candidate.
7. Offline ASR lock-screen continuation.
8. Diarization lock-screen continuation.
9. Recorder-card and phone-microphone background/lock-screen recording regression.
10. Process kill during summary still reconciles to `INTERRUPTED` rather than pretending to resume.

PR #37 remains Draft and must not be merged without separate explicit authorization.
