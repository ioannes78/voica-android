# Voica Android V1.0 — Product Requirements

## 1. Product definition

Voica is an Android companion app for QS668 / CB08 recorder-card devices. V1.0 targets a reliable offline-first recording workflow:

device -> Android -> local audio -> local transcription -> speaker-aware transcript -> optional AI meeting notes.

The first release intentionally prioritizes reliability over feature breadth.

## 2. V1.0 core capabilities

### Device
- scan and connect to supported recorder cards
- connection state and reconnect handling
- battery, charging state, storage capacity and firmware information
- device time synchronization
- recording-state query and supported recording controls

### Device files
- request and render device file list
- recover/resolve usable full filenames where the short list entry is insufficient
- order recordings by recorded filename/date when determinable
- download with progress, timeout, cancellation and retry behavior
- prevent accidental duplicate local imports
- delete one device recording with explicit confirmation
- do not expose delete-all in V1.0

### Local audio library
- preserve original downloaded device audio
- create a stable playback/transcription representation
- play, pause, seek and change speed
- rename/delete local recordings
- work without a connected recorder

### Transcription
- on-device ASR
- Chinese-first, with multilingual/code-switching capability when supported by the selected model
- long-audio processing must be bounded in memory
- progress, cancellation and explicit failure states
- transcript persistence independent of model lifecycle

### Speaker diarization
- optional diarization
- stable speaker IDs within one recording
- normalize overlaps/zero-length segments before ASR presentation
- do not claim cross-recording speaker identity in V1.0

### Transcript playback interaction
- tap transcript segment to seek
- highlight the currently playing segment
- auto-scroll without fighting manual user scroll
- preserve real time boundaries; do not invent fake timestamps solely for prettier text wrapping

### AI meeting notes
- configurable OpenAI-compatible endpoint
- base URL, API key and model configuration
- send only transcript/required meeting context, never raw audio unless a later feature explicitly requires it
- clear failure and retry states

### Settings
- device diagnostics
- model management/status
- speaker diarization toggle
- AI provider configuration
- local storage usage and cleanup

## 3. V1.0 non-goals

The initial Kardo-parity line does not include:
- cloud sync
- account system
- team collaboration
- live cloud ASR
- automatic cross-file speaker recognition
- background server dependency
- delete-all device command
- feature parity with the former VoiceCard Stage 30 codebase

Those may be evaluated only after the Kardo-parity baseline is stable.

## 4. Acceptance principle

A feature is not complete because it compiles. Device-facing and long-audio features require real-device/manual acceptance evidence.
