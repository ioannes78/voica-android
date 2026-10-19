# Stage 16A.2 — Timeline Annotation Progress

Status: QA PASSED — FREEZE / HANDOFF CANDIDATE

Last updated: 2026-10-19

## Approved gates

- Stage 16A.2 revised requirements: APPROVED by user.
- Stage 16A.2 development plan V1: APPROVED by user.
- Pre-Stage 16A.2 Brand Assets Refresh: completed in PR #32 and merged to `main` before Stage 16A.2 development.
- Stage 16A.2 implementation and real-device QA: COMPLETE for the currently available/tested matrix.
- Final Freeze / Handoff: awaiting explicit user confirmation.
- Merge to `main`: NOT YET AUTHORIZED.

## Source of truth / identity

- Repository: `ioannes78/voica-android`
- Production branch: `main`
- Stage 16A.2 branch point: `7daa031a20e07f262ef28d55f665d39f6bd6500e`
- Current `main` verified during closeout: `7348def92ea0ccebe67543fd468e06e6cb16fce8`
  - the post-branch `main` delta is the CI-only QA artifact-generation optimization from PR #34;
  - no Stage 16A.2 product behavior is sourced from that maintenance PR.
- Development branch: `stage16a2-timeline-annotation`
- Development PR: #33, still draft until Final Freeze / Handoff is explicitly confirmed.
- Final real-device-tested App source candidate: `a86f7cc145e53dcaef714e9f3eeda84147dcc776`
- versionCode: 92
- versionName: `1.1.0-stage16a2`
- Room: v14
- Runtime migrations: `MIGRATION_12_13`, `MIGRATION_13_14`
- Room v14 schema identityHash: `c60995f7ba406f15a3e786add6e70197`
- Production model channel: unchanged by Stage 16A.2.

A later closeout/docs-only HEAD may differ from the real-device-tested App source SHA above. Any such closeout delta must remain documentation-only and must not be represented as a newly tested App binary.

## Delivered product scope

### Recording-owned Point Annotation

- Annotation hard owner remains Recording.
- Optional note text.
- Zero or more photo attachments.
- Quick marker is an anchor-only annotation.
- Transcript / revision / segment / speaker / realtime lineage values are provenance snapshots, not ownership foreign keys.
- No semantic tags, workflow state, priority taxonomy, or Range Annotation were added in Stage 16A.2.

### Durable timeline contract

Primary durable time identity is sample-based.

Supported anchor domains:

- `CANONICAL_RECORDING`
- `REALTIME_MEDIA_SEGMENT`

Active capture may durably stage annotations before a final Recording row exists and later promote them to Recording ownership. This staging mechanism is not a second long-term ownership model.

No Stage 16A.2 code may fabricate a continuous playback timeline across recorder-card disconnect gaps. If an exact mapping is unavailable, the annotation is retained and precise seek is refused.

### Active capture annotation

The unified annotation composer is available for the implemented capture surfaces, including:

- ordinary phone recording;
- ordinary QS668 / CB08 recorder-card recording;
- phone realtime transcription;
- QS668 / CB08 realtime transcription.

The composer supports:

- quick marker;
- text note;
- camera photo;
- gallery photo.

Realtime/capture controls keep the annotation entry on the far right where applicable. Phone capture anchors are derived from formal media/sample truth rather than wall-clock elapsed time.

### Recording detail / playback

- Fourth detail tab: `标注`.
- Full annotation management remains available independently of transcript existence.
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

The final QA-C2.1 UI correction keeps `＋标注` inline in the timeline-row header rather than allowing the compact action to inflate into a visually separate row.

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
- ordinary source policy and Bluetooth compatibility path remain Stage 16A.1.6 responsibilities;
- one playback controller / one `AudioTrack` sink at a time;
- `ACTION_AUDIO_BECOMING_NOISY` / output-removal safety semantics remain unchanged;
- QS668 / CB08 BLE GATT remains separate from Android Bluetooth Audio.

Historical limitations are not silently upgraded by Stage 16A.2:

- API 26–30 legacy SCO physical path: NOT VERIFIED unless separately evidenced;
- BLE Audio / LE Audio input: prior missing-hardware status is not converted to PASS by a generic Bluetooth-microphone regression test;
- P9 AudioTrack bounded recovery: NOT TRIGGERED / NOT IMPLEMENTED;
- Stage 16A.1 S15 30-minute phone microphone + Small realtime + screen-off: NOT RUN / WAIVED BY USER.

Stage 16A.2 did perform successful regression checks on the user's available Bluetooth microphone path and an available wired/USB external-input path. The latter test accepted either wired headset microphone or USB microphone, so it must not be used to claim both transport classes were independently verified.

## Real-device QA status

### Initial Stage 16A.2 matrix

QA1–QA28 were completed for the applicable product paths. Passing coverage included:

- no-transcript annotation creation;
- quick marker, text note, edit and delete;
- persistence across reopen;
- PLAYING / PAUSED / READY seek semantics;
- transcript-timed creation;
- re-ASR and re-diarization ownership stability;
- gallery and camera attachments;
- single-attachment deletion and multi-photo annotations;
- local filters;
- Recording deletion lifecycle cleanup;
- phone realtime and QS668 realtime annotation;
- QS668 disconnect/reconnect safe-degradation behavior;
- camera lifecycle while phone/QS668 recording continues;
- Bluetooth and available external-input regression checks.

Issues discovered during this matrix were retained as QA findings rather than hidden as PASS and were subsequently fixed/retested:

- waveform long-press positioning precision;
- missing ordinary phone-recording annotation entry;
- missing ordinary recorder-card annotation entry;
- realtime `＋` placement / feedback;
- phone pause/resume segment-to-canonical exact mapping;
- transcript projection density and annotation management UX.

### Grouped revision QA

After the consolidated fixes:

- Group A — phone ordinary-recording annotation chain: PASS.
- Group B — QS668 ordinary recording + QS668 realtime annotation: PASS.
- Group D — waveform fine positioning + playback-state regression: PASS.
- Group C exposed transcript UI/UX density / management issues and was intentionally revised rather than marked PASS prematurely.

### QA-C2 / QA-C2.1

QA-C2 delivered:

- compact top controls;
- `标注 N` show/hide chip;
- clickable speaker-count edit entry;
- compact horizontal annotation strips;
- shared annotation detail/photo/edit/delete/seek surface;
- reading-mode view-only annotation projection;
- retained independent Annotation management tab.

QA-C2.1 corrected the remaining timeline-row layout issue so `＋标注` no longer visually occupies a separate row.

User real-device result for QA-C2.1 on 2026-10-19: **PASS**.

Therefore the currently tested Stage 16A.2 App source candidate is accepted for Final Freeze / Handoff preparation.

## CI / APK evidence

Final real-device-tested App source candidate:

- source SHA: `a86f7cc145e53dcaef714e9f3eeda84147dcc776`
- commit: `fix(stage16a2): keep transcript annotation action inline`

Fast validation:

- Android PR CI #1791 / run `39181093536`: SUCCESS
- Fast PR Gate: SUCCESS
- unit tests and debug compile gate: SUCCESS

Production-like QA APK validation:

- Android PR CI #1792 / run `39181316104`: SUCCESS
- QA APK Gate: SUCCESS
- Fast PR Gate in the APK-trigger run: SKIPPED by CI design, not a failure
- artifact: `Voica-1.1.0-stage16a2-export-qa-apk`
- artifact id: `12156731284`
- artifact ZIP digest: `sha256:6ba8ca95baa3e08e5d2fe1a92f9030fe5204f14d34ad50dd7b913ed776d5148f`
- extracted QA APK SHA256: `6565ecb9e47280f3c401a3eca0e011430ee62598c7d739c015de719691f46e62`

CI success is build/test evidence and is not used to substitute for the real-device QA above.

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
- [x] final App source candidate identified as `a86f7cc145e53dcaef714e9f3eeda84147dcc776`.

Still required:

- [ ] explicit user confirmation of Final Freeze / Handoff;
- [ ] create/seal final Freeze and Handoff records following repository convention;
- [ ] verify closeout HEAD contains only authorized docs changes after the tested App source candidate;
- [ ] final CI on the closeout HEAD;
- [ ] mark PR ready / merge only after user authorization;
- [ ] post-merge verification on `main`.

No further production-code change is authorized during closeout unless a newly discovered blocker is explicitly reopened.
