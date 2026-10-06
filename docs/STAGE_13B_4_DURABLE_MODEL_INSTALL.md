# Stage 13B.4 — Durable Model Install

Status: IMPLEMENTED / QA PENDING

## Purpose

Stage 13B.4 moves managed model installation out of UI/process-owned coroutines and into a durable, restart-safe pipeline while preserving the existing model-storage truth and isolated runtime validation path.

The production model channel remains read-only. Room remains schema v7.

## Frozen baseline

This sub-stage must not change the accepted Stage 13B B1-QA2-R1 BLE, playback, Mini Player, task-status, or notification deep-link behavior.

## Durable state machine

The installation lifecycle is:

`DOWNLOAD -> VERIFY -> EXTRACT -> FILE_VERIFY -> RUNTIME_VALIDATE -> ATOMIC_ACTIVATE -> READY`

Durable terminal/recovery states additionally include:

- `INTERRUPTED`
- `FAILED_RECOVERABLE`
- `FAILED_INTEGRITY`
- `FAILED_RUNTIME`
- `FAILED_CONFIGURATION`
- `CANCELLED`

`ModelAvailability` remains model truth. `ModelInstallJournalRecord` remains operation truth.

## Candidate identity

Every operation freezes an exact `ModelDescriptorSnapshot` and manifest digest. Recovery never silently resolves a different revision from a newer remote manifest.

The effective candidate identity is:

`modelId + version + revision + manifestDigest`

## Persistent journal

Journal location:

`noBackupFilesDir/model-install-journal/`

The journal uses atomic file replacement and stores:

- operation id
- exact descriptor snapshot
- manifest digest
- origin (`MANUAL` / `AUTO_SMALL`)
- phase
- progress bytes
- executor kind
- executor generation
- retry/attempt information
- cancellation flag
- user-resume requirement
- last failure code/message
- creation/update timestamps

No Room migration is required.

## Execution boundary

### Android 14+ manual model download

Manual downloads use a user-initiated `JobScheduler` transfer (`UIDT` semantics):

- user initiated
- network required
- storage-not-low required
- persisted across reboot
- estimated network bytes supplied
- system-required job notification supplied

### Android 8–13 manual download

Manual download falls back to foreground WorkManager execution.

### Automatic Silero update

Automatic small-model updates use WorkManager and never pretend to be user-initiated jobs.

### Post-download processing

Verification, extraction, file verification, runtime validation, and activation use a unique WorkManager finalizer.

WorkManager and JobScheduler are executors only. They are not business truth.

## Existing model safety retained

Stage 13B.4 reuses the existing implementation for:

- HTTPS-only download
- HTTP Range continuation
- safe restart when Range is not honored
- package size verification
- package SHA-256 verification
- safe archive extraction
- manifest-declared files only
- file size/SHA-256 verification
- staging directory
- atomic staging promotion
- isolated runtime validator process
- confirmed-good marker
- atomic active/previous model state
- rollback protection

The old active model remains usable until the exact candidate successfully passes runtime validation and atomic activation.

## Recovery rules

### Download interruption

A recoverable `.part` remains available for Range resume. Lifecycle/process cancellation is not treated as a user cancellation.

### Extract interruption

Incomplete staging is deleted and extraction restarts. Half-extracted content is never treated as installed.

### Static candidate already promoted

Recovery can skip download/extraction and resume at runtime validation.

### Activation boundary

If the candidate is already confirmed-good and active, reconciliation converges the journal to `READY` even if the previous process died before persisting READY.

### Missing manual executor

If the durable record exists but no valid executor owns it after restart, manual installation becomes `INTERRUPTED` and requires an explicit user resume.

### User cancellation

Explicit cancellation invalidates the current executor generation, cancels scheduled execution, removes untrusted partial/staging state, and never deletes an active/previous/in-use model.

If atomic activation already completed before cancellation wins the race, `READY` wins; cancellation must not roll back a successfully activated model.

## Transient cleanup

Transient cleanup is journal-aware. A `.part` or staging directory owned by a non-terminal durable operation is protected even when no process-local coroutine exists.

This prevents startup/storage cleanup from deleting a resumable package solely because the previous process died.

## Notifications

The dedicated model-install notification exposes truthful states:

- waiting
- downloading with byte progress
- package verification
- extraction
- model-file verification
- runtime validation
- activation
- interrupted/recoverable error

Manual tasks expose an explicit Cancel action. Indeterminate phases do not fabricate percentage progress.

## Automatic update integration

`ModelUpdateController` routes eligible Silero automatic updates through the durable controller. Existing non-durable fake/test ModelManager implementations retain the legacy fallback path for compatibility.

## QA gate

Stage 13B.4 is not complete until real-device QA validates at least:

1. normal small-model and large-model install
2. Home/background/lock-screen continuation
3. process kill during download
4. network interruption and Range resume
5. process kill during verify/extract
6. runtime validator interruption/failure
7. user cancellation
8. force stop followed by explicit app reopen/resume
9. reboot behavior
10. low-storage behavior
11. old active model remains usable for every failed candidate path
12. no regression to the accepted B1-QA2-R1 baseline

Formal 30/60/120-minute stress testing remains Stage 13B.7.

## Explicit non-scope

Stage 13B.4 does not change:

- BLE protocol/GATT
- R3 scan/connect behavior
- R5 reconnect ownership
- playback/Mini Player
- transcription/diarization execution policy
- AI Summary execution policy
- Paragraph Organizer V2
- Stage 13A model selection/defaults
- Room schema
- production model-channel contents
