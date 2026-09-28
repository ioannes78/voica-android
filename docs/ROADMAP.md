# Voica Android V1.0 Roadmap

Status: planning. Feature coding requires explicit approval of the relevant stage plan.

## Stage 1 — Foundation + protocol core

Goal: create a small, testable Android foundation before touching real BLE behavior.

Deliverables:
- Gradle/Kotlin/Compose app shell
- package/application naming finalized
- protocol constants, frame builder and independent AE22/AE23 parsers
- CRC and golden tests
- command encoders for device info/file operations
- CI limited to fast build/unit checks
- Stage 1 debug APK (shell/diagnostics only)

Exit: protocol tests deterministic and app builds cleanly.

## Stage 2 — BLE connection + device status

Deliverables:
- scan/connect/disconnect
- serialized GATT operation queue
- AE20/AE21/AE22/AE23 discovery/subscription
- time sync
- battery/charging/capacity/firmware
- reconnect/error states and diagnostic log

Exit: repeated connect/disconnect and device-info operations pass real-device testing.

## Stage 3 — Device file synchronization

Deliverables:
- file list
- full/usable filename resolution strategy
- download candidates and timeout behavior
- progress/cancel/retry
- duplicate-download protection
- single-file device deletion with confirmation
- raw source persistence

Exit: repeated list/download/delete tests pass without duplicate or corrupt local records.

## Stage 4 — Local library + audio pipeline

Deliverables:
- source Opus validation and conversion
- canonical 16 kHz audio representation
- local Room metadata
- playback/pause/seek/speed
- rename/local delete
- offline local-library use

Exit: downloaded recordings remain playable after reconnect/restart and seek is stable.

## Stage 5 — Local ASR

Deliverables:
- Android ASR engine contract
- benchmark/select first production model
- model download/verification/delete
- long-audio chunking and bounded-memory execution
- transcription progress/cancel/retry
- persisted transcript

Exit: short and long Chinese-first recordings pass agreed accuracy/stability tests.

## Stage 6 — Speaker diarization + synchronized transcript

Deliverables:
- diarization engine
- overlap/zero-length normalization
- timed speaker segments
- display merge behavior
- tap-to-seek
- playback highlight + controlled auto-scroll

Exit: multi-speaker real recordings show stable timing and usable speaker separation.

## Stage 7 — AI meeting notes

Deliverables:
- OpenAI-compatible provider settings
- connectivity/model validation where supported
- meeting-summary prompts/output
- retry/error handling
- secrets storage

Exit: transcript-to-summary workflow is stable and does not require uploading raw audio.

## Stage 8 — V1 hardening + release

Deliverables:
- lifecycle/background interruption audit
- low-storage/network/BLE-disconnect cases
- long-recording soak tests
- database migration/failure checks
- accessibility/basic localization
- release build and final handoff

Exit: V1 acceptance checklist complete.

## Post-V1 candidates

Only after V1 freeze:
- realtime transcription
- cloud ASR
- cloud sync
- cross-recording speaker profiles
- export/share enhancements
- additional recorder models
