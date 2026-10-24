# Stage 16X — WENZHI Brand & Theme Consolidation

Status: **IMPLEMENTATION IN PROGRESS / DRAFT PR / NO MERGE**

Stage 16X consolidates the user-visible product brand, Material 3 theme foundation, and transcript reading presentation before Stage 17.

Approved revision plan: **Stage 16X Revision Plan V5**.

Current implementation scope:

- WENZHI / 闻知 brand assets derived from the confirmed final master only;
- paper-white launcher background and black exact-master symbol;
- adaptive, monochrome, notification and splash resource chains;
- WENZHI Ink light/dark theme as the default while retaining legacy palettes;
- semantic and transcript feature color tokens;
- gentle transcript playback reading highlight without changing ASR/CTC/VAD/diarization timing logic;
- user-visible Voica brand cleanup while retaining technical package/application/channel identifiers required for compatibility.

## P6-R1 — QA4 transcript layout stability — CLOSED / REAL-DEVICE PASS

The transcript body keeps a fixed `displayText`, typography, and layout while playback advances. The active EXACT cue no longer rebuilds an `AnnotatedString` or moves SpanStyle boundaries. Current-word emphasis is drawn behind the already-laid-out text using `TextLayoutResult` character geometry, with per-line rectangles for cross-line ranges. The transcript pointer-input identity no longer depends on `activeCueId`.

Real-device QA4 passed: during continuous playback the current-word highlight moves while the transcript text, line wrapping, and unread text remain visually stationary.

Visual hierarchy is intentionally two-level:

- active paragraph background = weak theme-derived emphasis;
- current word background = strong theme-derived emphasis.

No timeline, cue timestamp, seek, ASR, CTC, VAD, diarization, or playback-state mapping logic was changed by P6-R1.

## P6-R2 — Material 3 Surface token consolidation — IMPLEMENTATION IN PROGRESS

The theme foundation now explicitly owns the Material 3 surface family for WENZHI Ink, Mint, Blue, and Purple in both light and dark modes:

- `surfaceDim` / `surfaceBright`;
- `surfaceContainerLowest` / `Low` / base / `High` / `Highest`;
- `surfaceVariant` / `onSurfaceVariant`;
- outline / outlineVariant;
- inverse surface roles;
- surface tint and scrim.

Legacy themes retain their own color personality instead of sharing one neutral ladder: Mint uses cool green-grey surfaces, Blue uses blue-grey surfaces, and Purple uses restrained purple-grey surfaces.

Semantic surface aliases are now tied back to Material 3 roles: `surfaceElevated` maps to `surfaceContainerHigh`, `surfaceVariant` maps to `surfaceContainer`, and divider maps to `outlineVariant`.

### P6-R2.1 — WENZHI Ink warm Surface cleanup

Implemented and awaiting automated gate. WENZHI Ink no longer relies on unspecified Material baseline Surface roles. Light mode uses a warm xuan-paper neutral ladder; dark mode uses warm charcoal neutrals. The primary cyan-ink accent remains separate from the neutral Surface family.

## Remaining V5 theme work

- finish P6-R2 automated Surface contracts / Fast PR Gate;
- P6-R3 — add WENZHI Paper / 宣纸暖白 as a full light/dark preset;
- P6-R4 — visual theme palette UX and real preset preview cards;
- P6-R5 — custom theme generator with controlled neutral/surface tonal palettes;
- P6-R6 — light/dark transcript reading-highlight regression across WENZHI Ink, WENZHI Paper, Mint, Blue, Purple, and Custom;
- P7/P8/P9 — cleanup, full automated regression, and final real-device QA.

Stage 17 remains **NOT STARTED**.

This document does not authorize merging this Stage 16X branch. PR #36 remains draft / NO MERGE until P8 and P9 are complete.
