# Stage 16A.2 — Timeline Annotation Progress

Status: **FINAL FREEZE / HANDOFF SEALED — FINAL CI / MERGE GATE**

Last updated: 2026-10-19

## Approved gates

- Stage 16A.2 revised requirements: APPROVED by user.
- Stage 16A.2 development plan V1: APPROVED by user.
- Pre-Stage 16A.2 Brand Assets Refresh: completed in PR #32 and merged to `main` before Stage 16A.2 development.
- Stage 16A.2 implementation and real-device QA: COMPLETE for the applicable/tested matrix.
- User Final Freeze / Handoff confirmation: **CONFIRMED on 2026-10-19**.
- Final Freeze: `docs/STAGE_16A_2_FREEZE.md`, commit `415810514889fdac3e0750d6ee3cf42a9bc1eacc`.
- Final Handoff: `docs/STAGE_16A_2_HANDOFF.md`, commit `2f2557db47ef47154c4a23d8ea808aa98b039bdd`.
- Merge to `main`: AUTHORIZED after final docs-only CI and mergeability verification.

## Source of truth / identity

- Repository: `ioannes78/voica-android`
- Production branch: `main`
- Stage 16A.2 branch point: `7daa031a20e07f262ef28d55f665d39f6bd6500e`
- `main` at Final Freeze confirmation: `7348def92ea0ccebe67543fd468e06e6cb16fce8`
  - the post-branch `main` delta is the CI-only QA artifact-generation optimization from PR #34;
  - no Stage 16A.2 product behavior is sourced from that maintenance PR.
- Development branch: `stage16a2-timeline-annotation`
- Development PR: #33.
- Final real-device-tested App source: `a86f7cc145e53dcaef714e9f3eeda84147dcc776`
- Pre-Freeze closeout docs source: `28c1ab017526e2bb8b426a6fafbb852efe1c3fdc`
- versionCode: 92
- versionName: `1.1.0-stage16a2`
- Room: v14
- Runtime migrations: `MIGRATION_12_13`, `MIGRATION_13_14`
- Room v14 schema identityHash: `c60995f7ba406f15a3e786add6e70197`
- Production model channel commit: `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- Production manifestVersion: 7
- Production manifestDigest: `8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

All commits after `a86f7cc...` in the Final Freeze/Handoff closeout are documentation-only. They must not be represented as a newly tested App binary.

## Delivered product scope

### Recording-owned Point Annotation

- Annotation hard owner remains Recording.
- Optional note text.
- Zero or more photo attachments.
- Quick marker is an anchor-only annotation.
- Transcript / revision / segment / speaker / realtime lineage values are provenance snapshots, not ownership foreign keys.
- No semantic tags, workflow state, priority taxonomy, Range Annotation, OCR, image AI, global annotation FTS, cloud collaboration, or annotation-aware AI summary were added in Stage 16A.2.

### Durable timeline contract

Primary durable time identity is sample-based.

Supported anchor domains:

- `CANONICAL_RECORDING`
- `REALTIME_MEDIA_SEGMENT`

Active capture may durably stage annotations before a final Recording row exists and later promote them to Recording ownership. This staging mechanism is not a second long-term ownership model.

No Stage 16A.2 code may fabricate a continuous playback timeline across recorder-card disconnect gaps. If an exact mapping is unavailable, the annotation is retained and precise seek is refused.

### Active capture annotation

The unified annotation composer is available for the implemented capture surfaces:

- ordinary phone recording;
- ordinary QS668 / CB08 recorder-card recording;
- phone realtime transcription;
- QS668 / CB08 realtime transcription.

The composer supports quick marker, text note, camera photo, and gallery photo. Realtime/capture controls keep the annotation entry on the far right where applicable. Phone capture anchors are derived from formal media/sample truth rather than wall-clock elapsed time.

### Recording detail / playback

- Fourth detail tab: `标注`.
- Full annotation management works independently of transcript existence.
- Filters: all / note / photo / quick marker.
- Note edit, attachment view/delete, annotation delete, time movement, and playback seek are supported.
- Existing playback-state semantics are preserved when seeking annotations.
- Waveform supports annotation markers and long-press coarse locate + drag fine adjustment before creation.

### Transcript projection

Annotations remain Recording-owned and are dynamically projected into the current transcript by audio sample position.

Timeline view:

- full annotation composer on timed rows;
- compact single-line horizontal annotation chips;
- annotation detail dialog supports note view/edit, photo view/delete, annotation delete, and seek;
- multiple annotations do not each consume a full content row.

Reading view:

- existing annotations can be shown/hidden and inspected;
- no new-annotation action is exposed in reading mode;
- annotation UI remains outside selectable transcript text so continuous reading/copy behavior is preserved.

Speaker UX:

- the transcript document summary exposes the speaker count itself as the speaker-edit entry;
- no separate right-side speaker-edit button is required.

QA-C2.1 keeps `＋标注` inline in the timeline-row header rather than allowing the compact action to inflate into a visually separate row.

## Room v14 durable domain

Implemented and gated:

- `TimelineAnnotationEntity`;
- `TimelineAnnotationAttachmentEntity`;
- `TimelineAnnotationDao`;
- `MIGRATION_13_14`;
- `VoicaDatabase` v14 registration;
- v13→v14 migration test;
- generated Room schema `14.json`;
- migration/schema contract coverage.

Annotation/attachment cleanup remains tied to Recording lifecycle without making transcript/diarization entities annotation owners.

## Frozen Stage 16A.1.6 invariants

Stage 16A.2 does not authorize regressions to Android Audio I/O architecture:

- one `AudioRecord` owner;
- canonical PCM16 / 16 kHz / mono recording truth;
- formal WAV truth before optional realtime ASR fan-out;
- one playback controller / one `AudioTrack` sink at a time;
- existing `ACTION_AUDIO_BECOMING_NOISY` / output-removal safety semantics;
- QS668 / CB08 BLE GATT remains separate from Android Bluetooth Audio.

Historical limitations are not silently upgraded by Stage 16A.2:

- API 26–30 legacy SCO physical path: NOT VERIFIED unless separately evidenced;
- BLE Audio / LE Audio input: prior missing-hardware status is not converted to PASS by a generic Bluetooth-microphone regression test;
- P9 AudioTrack bounded recovery: NOT TRIGGERED / NOT IMPLEMENTED;
- Stage 16A.1 S15 30-minute phone microphone + Small realtime + screen-off: NOT RUN / WAIVED BY USER.

Stage 16A.2 did perform successful regression checks on the user's available Bluetooth microphone path and an available external-input path. The latter test must not be used to claim all wired and USB transport classes were independently verified.

## Real-device QA status

Applicable/tested Stage 16A.2 matrix: **PASS**.

Coverage includes:

- no-transcript annotation creation;
- quick marker, text note, edit and delete;
- persistence across reopen;
- PLAYING / PAUSED / READY seek semantics;
- waveform fine positioning;
- transcript timed creation/projection;
- re-ASR and re-diarization ownership stability;
- gallery, camera, single-photo deletion and multi-photo annotations;
- local filters;
- Recording deletion lifecycle cleanup;
- ordinary phone and QS668 recording annotation;
- phone and QS668 realtime annotation;
- phone pause/resume exact canonical mapping;
- QS668 disconnect/reconnect safe-degradation behavior;
- camera lifecycle while phone/QS668 recording continues;
- transcript compact projection and shared annotation management;
- reading-mode view-only annotation projection;
- speaker-count edit entry;
- QA-C2.1 inline `＋标注` layout.

Issues found during initial QA were not hidden as PASS; they were fixed and retested. User real-device result for QA-C2.1 on 2026-10-19: **PASS**.

## CI / APK evidence

Final real-device-tested App source:

- source SHA: `a86f7cc145e53dcaef714e9f3eeda84147dcc776`
- commit: `fix(stage16a2): keep transcript annotation action inline`

Fast validation:

- Android PR CI #1791 / run `39181093536`: SUCCESS
- Fast PR Gate: SUCCESS
- unit tests and debug compile gate: SUCCESS

Production-like QA APK validation:

- Android PR CI #1792 / run `39181316104`: SUCCESS
- QA APK Gate: SUCCESS
- artifact: `Voica-1.1.0-stage16a2-export-qa-apk`
- artifact id: `12156731284`
- artifact ZIP digest: `sha256:6ba8ca95baa3e08e5d2fe1a92f9030fe5204f14d34ad50dd7b913ed776d5148f`
- extracted/tested QA APK SHA256: `6565ecb9e47280f3c401a3eca0e011430ee62598c7d739c015de719691f46e62`

Pre-Freeze closeout docs validation:

- source: `28c1ab017526e2bb8b426a6fafbb852efe1c3fdc`
- Android PR CI #1794 / run `39185089442`: SUCCESS
- Fast PR Gate: SUCCESS
- QA APK Gate: SKIPPED by CI design, not a failure.

CI success is build/test evidence and does not substitute for the real-device QA above.

## Final Freeze / Handoff gate

Completed:

- [x] approved requirements;
- [x] approved development plan;
- [x] implementation complete for Stage scope;
- [x] Room v14 migration/schema gates;
- [x] automated Fast Gate success;
- [x] production-like QA APK success;
- [x] applicable real-device QA complete;
- [x] QA findings fixed and targeted regression passed;
- [x] final App source identified as `a86f7cc145e53dcaef714e9f3eeda84147dcc776`;
- [x] explicit user confirmation of Final Freeze / Handoff;
- [x] Final Freeze record created;
- [x] Final Handoff record created.

Remaining mechanical merge flow:

- [ ] close approved roadmap state to Stage 16B reserved;
- [ ] verify final branch HEAD delta from tested App source is docs-only;
- [ ] final PR CI on latest branch HEAD;
- [ ] mark PR ready for review;
- [ ] merge PR #33 using expected final head SHA;
- [ ] post-merge verify `main`, PR state, Room v14, and production model-channel pin.

No further production-code change is authorized during closeout unless a newly discovered blocker is explicitly reopened.

## Next stage

Per the approved roadmap, the next reserved stage is:

**Stage 16B — Local File ASR V2**

Status: **RESERVED / NOT STARTED**.

Starting Stage 16B requires a fresh GitHub truth-source verification, revised requirements confirmation, and development-plan confirmation before any coding.