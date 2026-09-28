# Voica Android Architecture — Draft V1

## Layering

```
Compose UI
   |
Feature ViewModels
   |
Use cases / repositories
   |
+----------------+----------------+----------------+
| BLE/device     | Audio/library  | AI/ML          |
|                |                |                |
| recorder       | playback       | ASR            |
| session        | conversion     | diarization    |
| protocol       | persistence    | meeting notes  |
| transport      | raw files      | model manager  |
+----------------+----------------+----------------+
```

## Proposed modules

- `app` — application shell, navigation and dependency wiring
- `core-model` — shared domain models/contracts
- `core-protocol` — QS668/CB08 binary protocol only
- `core-ble` — Android BLE transport/session primitives
- `core-audio` — audio representations and timing contracts
- `core-database` — Room database
- `engine-opus` — device Opus packaging/decoding
- `engine-asr` — local ASR abstraction and implementation
- `engine-speaker` — diarization abstraction and implementation
- `engine-ai` — meeting-note provider client
- `feature-device` — scan/connect/device state
- `feature-recordings` — device/local recording lists and transfer
- `feature-transcript` — transcription and synchronized playback UI
- `feature-settings` — models, provider and diagnostics

Modules may be collapsed during Stage 1 if Gradle/module overhead exceeds the practical benefit. Boundaries matter more than module count.

## State model

BLE connection state, active transfer state and active processing state are explicit state machines. A single mutable singleton is not the application architecture.

## File identity

Device identity and local recording identity must be separate:
- a device file can exist without a local recording
- a downloaded device file maps to one stable local recording
- retrying the same download must not silently create duplicates
- deleting a device copy must not implicitly delete the local copy

## Audio canonicalization

1. Persist source bytes first.
2. Validate source format/packet structure.
3. Create a canonical local audio representation for playback/ML.
4. Keep conversion idempotent.
5. Never require a connected recorder to access the local library.

## Transcript timing

Persist timed segments as first-class data. UI paragraph formatting is derived presentation data and must not destroy original timing.

## Security

API keys must not be committed or logged. Provider credentials are stored using Android-appropriate encrypted storage.
