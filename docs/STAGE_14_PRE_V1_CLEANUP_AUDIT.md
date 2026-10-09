# Stage 14.1B — Pre-V1 Compatibility Cleanup Audit

Status: implementation candidate; not a Freeze/Handoff document.

## Policy

Voica V1.0 is the first formally supported install/data baseline. Stage 1–13C builds are development/QA builds and are not a supported upgrade source for V1.0.

The V1.0 database baseline remains Room schema 12. This cleanup does not change the Room schema version.

## Removed from the V1 production runtime path

### Stage 5 properties import at application startup

`VoicaApplication` no longer calls `RecordingLibraryRepository.importLegacyStage5IfNeeded()`.

The old scanner/import implementation is intentionally left in source history for now; it is no longer reachable from normal V1 startup. Stage 14.3 R8 analysis will determine the actual binary contribution of unreachable code before any larger source deletion.

### Pre-V1 display-name repair at application startup

`VoicaApplication` no longer calls `normalizeStandardDeviceDisplayNames()` on every launch. Current device downloads and local imports apply the current display-name policy when they are registered.

### Pre-V1 Room migration registration

`VoicaDatabase.create()` now opens/creates schema 12 directly and no longer registers migrations 1→2 through 11→12 in the V1 production database builder.

Migration implementations, schema JSON files, and historical migration tests remain in the repository as development evidence. They are not part of the V1 compatibility contract.

### Unused Stage 4 protocol source-compatibility aliases

Removed deprecated aliases that had no production callers:

- `LIST_NAME_LENGTH`
- `LIST_ENTRY_LENGTH`
- `Control.BATTERY_CHARGING`
- legacy `Key.RECORD_*` aliases
- deprecated `FileEntry`
- deprecated `DeviceDecoders.decodeFileList()` adapter

The strict `FileListDecoder` / `RawDeviceFileEntry` production path is unchanged.

## Explicitly retained

The following were audited and are not pre-V1 app-version compatibility baggage:

- `LegacyAssetFormat`: still used by the current OPUS/WAV device download and lookup path.
- API 26–30 Bluetooth permissions and old Android Bluetooth callback compatibility: required by current `minSdk 26` support.
- `LocalRecordingDeleteCoordinator` and current delete/recovery paths.
- current canonical-audio, transcription, diarization, AI-summary, search-index and pending-delete startup recovery.
- `SpeechBenchmarkRunner`: retained by the Stage 13C frozen contract and only exposed through debuggable product paths.
- Stage 13C null-only diarization benchmark compatibility plumbing: no constructible production profiler exists; removing the UI/API plumbing is deferred because it gives negligible package benefit and would increase UI churn in the release-freeze stage.
- model IDs/metadata needed to render historical content or future frozen contracts; no production model-channel change is authorized by this cleanup.

## Contract gates

`Stage14V1BaselineContractTest` asserts that:

- V1 startup does not run the Stage 5 import;
- V1 startup does not run the pre-V1 display-name repair;
- Room remains schema 12;
- the V1 production database builder does not register pre-V1 migrations.

## Stage 14.1B R1 — Search deep-link correction

The first Stage 14.1A implementation incorrectly compared the persisted search anchor (the durable source transcript segment id) with the timeline presentation row id. Timeline row ids are `segment:<id>` before diarization and `span:<id>` after diarization, so the two id spaces are intentionally different.

R1 keeps the persisted search index contract unchanged and resolves the anchor through `TranscriptDisplaySegment.sourceSegmentId` instead. When diarization splits one source segment into multiple speaker spans, navigation scrolls to the first matching span and all spans derived from that source segment receive the temporary search highlight.

Revision/edited transcript navigation remains paragraph-id based and requires an exact current `revisionId` match.

Search highlighting is now independent from playback-active state: the temporary search highlight no longer replaces the playback row state or suppresses the active token/cue highlight.

`TranscriptSearchAnchorResolverTest` covers:

- model-original segment navigation;
- diarization one-source-segment-to-multiple-span navigation/highlighting;
- current Revision paragraph navigation;
- mismatched Revision rejection;
- mismatched transcription rejection.

## Notification / prompt / durable-attention regression audit

Stage 13B/13C notification and durable-attention behavior remains a release-freeze invariant. A direct diff from the Stage 14.1B v79 candidate to R1 changes only:

- `app/build.gradle.kts`;
- `RecordingDetailProductScreen.kt`;
- `TranscriptSearchAnchorResolver.kt`;
- `TranscriptionCards.kt`;
- `TranscriptSearchAnchorResolverTest.kt`;
- this audit document.

No notification controller, foreground service, global-attention host, task state ViewModel, model-install state machine, playback service, or durable-attention repository is modified by R1.

The following frozen behavior remains intentionally unchanged:

- transcription and diarization running work keeps its media-processing foreground-service ownership and ongoing notification;
- AI Summary running notification and explicit cancel semantics remain unchanged;
- model download/install durable state and notification behavior remain unchanged;
- playback foreground/MediaSession notification behavior remains unchanged;
- Transcription / AI Summary Candidate notifications remain projections of durable candidate state;
- FAILED / INTERRUPTED / AMBIGUOUS_REMOTE_RESULT attention is not acknowledged merely by opening a page or notification;
- Stage 13C diarization/alignment failure attention remains durable;
- entering the matching Recording detail temporarily hides the same App-internal global attention, but leaving the page restores it while durable state remains unresolved;
- page visibility never writes a Room acknowledgement.

Android PR CI run #1081 (`37957787616`) completed successfully after R1, including the full existing unit-test suite, QA build/signing checks, and Room v1–v12 schema gate.

## Upgrade expectation for QA

For this Stage 14.1B candidate:

- installing over the immediately preceding Stage 14 QA build is expected to work because both use schema 12;
- a fresh install is expected to create schema 12 directly;
- upgrading a database older than schema 12 is intentionally outside the V1.0 compatibility contract.

## Package-size expectation

This cleanup primarily removes runtime compatibility behavior and simplifies the V1 baseline. It is not expected by itself to materially reduce the unshrunk QA APK.

Stage 14.3 will measure the size effect after R8/resource shrinking. The existing APK baseline shows that native libraries and DEX dominate package size, so no size claim should be made from compatibility cleanup alone.
