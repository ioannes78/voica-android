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

## P6-R1 — QA4 transcript layout stability

Implementation is complete and awaiting automated gate + real-device QA4 confirmation.

The transcript body now keeps a fixed `displayText`, typography, and layout while playback advances. The active EXACT cue no longer rebuilds an `AnnotatedString` or moves SpanStyle boundaries. Current-word emphasis is drawn behind the already-laid-out text using `TextLayoutResult` character geometry, with per-line rectangles for cross-line ranges. The transcript pointer-input identity no longer depends on `activeCueId`.

Visual hierarchy is intentionally two-level:

- active paragraph background = weak theme-derived emphasis;
- current word background = strong theme-derived emphasis.

No timeline, cue timestamp, seek, ASR, CTC, VAD, diarization, or playback-state mapping logic is changed by P6-R1.

## Remaining V5 theme work

- P6-R2 — complete Material 3 Surface role mapping for every preset theme;
- P6-R2.1 — remove residual cool-purple Surface roles from WENZHI Ink and use a warm xuan-paper neutral ladder;
- P6-R3 — add WENZHI Paper / 宣纸暖白 as a full light/dark preset;
- P6-R4 — visual theme palette UX and real preset preview cards;
- P6-R5 — custom theme generator with controlled neutral/surface tonal palettes;
- P6-R6 — light/dark transcript reading-highlight regression across WENZHI Ink, WENZHI Paper, Mint, Blue, Purple, and Custom;
- P7/P8/P9 — cleanup, full automated regression, and final real-device QA.

Stage 17 remains **NOT STARTED**.

This document does not authorize merging this Stage 16X branch. PR #36 remains draft / NO MERGE until P8 and P9 are complete.
