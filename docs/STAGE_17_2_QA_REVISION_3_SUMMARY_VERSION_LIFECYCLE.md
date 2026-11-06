# Stage 17.2 Revision-3 — Summary Version Lifecycle & Candidate Notification

Status: IMPLEMENTED / AUTOMATED CI PENDING / REAL-DEVICE QA NOT STARTED

## Scope

Revision-3 completes the local Summary version lifecycle without changing Room schema or the Stage 17.2 temporary CPU-safe inference path.

Product lifecycle:

`Current -> Regenerate -> Candidate -> Preview / Adopt / Ignore -> History`

## Implemented contracts

- First completed Summary remains Current.
- Regeneration with an existing Current produces a Candidate and does not overwrite Current.
- Candidate preview is separate from adoption.
- Historical versions can be previewed without changing Current.
- Any completed non-current version can be explicitly adopted as Current.
- Candidate ignore is durable through the existing `dismissedAiSummaryCandidateId` field.
- Ignored versions remain retained and visible in Summary versions.
- A later Candidate is not suppressed by an older dismissal.
- Summary versions expose Current / New result / History roles with generation time and model label.
- Copy/share/export operate on the currently viewed Summary version.
- Formal search/current selection remains tied to Current until explicit adoption.
- Candidate notifications use the existing task-result notification architecture.
- Swiping away the Android notification does not ignore the in-app Candidate.
- Explicit in-app Ignore removes Candidate attention and its notification without deleting the version.
- Regenerated Candidate completion no longer emits a duplicate process-terminal completion notification; durable Candidate notification owns that result surface.

## Visibility / completion notification fix

The recording detail visibility projection is now lifecycle-aware:

- STARTED / RESUMED: the active detail destination may be visible.
- CREATED / INITIALIZED / DESTROYED: the detail destination is not visible for attention acknowledgement.
- Home / background / lock-screen therefore clear `Stage13B5Qa4PageVisibility`.

This prevents a Summary page that was last visible before Home/lock from immediately acknowledging and cancelling a background completion notification.

## Room / persistence

- Room remains v16.
- No migration is introduced.
- Existing `recording_candidate_attention.dismissedAiSummaryCandidateId` is reused.

## Runtime safety boundary

Revision-3 does not restore GPU product inference.

Current Stage 17.2 product path remains the Revision-2 temporary CPU-safe route.

Validated before Revision-3:

- CPU-only foreground: PASS.
- CPU-only Home/background with vendor "allow full background behavior": PASS.
- CPU-only manual lock-screen with that setting: PASS.
- AUTO/GPU manual lock-screen: remains CRITICAL FAIL / blocked after prior freeze/reboot behavior.

GPU recovery is reserved for a separate Revision-4 investigation.

## Automated regression coverage

Added/extended coverage for:

- durable Summary Candidate projection;
- Summary Candidate dismissal;
- old dismissal not suppressing a new Candidate;
- independent transcription / Summary / stale-summary dismissal state;
- detail lifecycle visibility requiring STARTED or RESUMED state.

## Real-device QA order

Run one QA item per message after CI and QA APK gate pass:

1. First Summary becomes Current.
2. Regenerate -> Candidate; Current remains unchanged.
3. Preview Candidate.
4. Return to Current.
5. Adopt Candidate.
6. Previous Current remains in History.
7. Regenerate and Ignore Candidate.
8. Restart app; ignored Candidate remains suppressed.
9. Ignored version remains accessible in Summary versions.
10. Adopt a historical version.
11. Candidate Android notification opens Summary without adoption.
12. Swiping notification does not ignore Candidate.
13. Summary page -> Home -> background completion notification remains visible.
14. Foreground Summary completion has no redundant duplicate attention.
15. CPU Home/background regression.
16. CPU manual lock-screen regression.
17. Search remains tied to Current.
18. Preview Candidate/History copy/share/export.
19. Candidate/History deletion regression.

## Merge boundary

PR #37 remains Draft / DO NOT MERGE until separate explicit authorization.
