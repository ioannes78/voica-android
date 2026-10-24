# Stage 16X — WENZHI Brand & Theme Consolidation

Status: **IMPLEMENTATION COMPLETE / FINAL AUTOMATED GATE PENDING / DRAFT PR / NO MERGE**

Stage 16X consolidates the user-visible product brand, Material 3 theme foundation, and transcript reading presentation before Stage 17.

Approved revision plan: **Stage 16X Revision Plan V5**.

## P6-R1 — QA4 transcript layout stability — CLOSED / REAL-DEVICE PASS

The transcript body keeps a fixed `displayText`, typography, and layout while playback advances. The active EXACT cue no longer rebuilds an `AnnotatedString` or moves SpanStyle boundaries. Current-word emphasis is drawn behind the already-laid-out text using `TextLayoutResult` character geometry, with per-line rectangles for cross-line ranges. The transcript pointer-input identity no longer depends on `activeCueId`.

Real-device QA4 passed: during continuous playback the current-word highlight moves while transcript text, line wrapping, and unread text remain visually stationary.

Visual hierarchy remains fixed:

- active paragraph background = weak, theme-derived emphasis;
- current word background = strong, theme-derived emphasis.

No timeline, cue timestamp, seek, ASR, CTC, VAD, diarization, or playback-state mapping logic was changed.

## P6-R2 / P6-R2.1 — Material 3 Surface foundation — COMPLETE

All fixed presets now explicitly own a full Material 3 Surface family in both light and dark modes, including `surfaceDim`, `surfaceBright`, `surfaceContainerLowest` through `surfaceContainerHighest`, variant, outline, inverse, tint, and scrim roles.

WENZHI Ink no longer depends on unspecified Material baseline surfaces. Its light Surface ladder is warm xuan-paper neutral; dark mode is warm charcoal neutral. Cold-purple fallback surfaces are removed while cyan-ink remains the accent.

Mint, Blue, and Purple retain separate tonal personalities rather than sharing a neutral ladder. Semantic surface aliases now map back to Material 3 surface-container roles instead of maintaining a second independent surface palette.

## P6-R3 — WENZHI Paper / 宣纸暖白 — COMPLETE

WENZHI Paper is a first-class light/dark preset with warm paper surfaces, ink text, tea-brown accent and a distinct stronger current-word reading highlight. Light mode supports four persisted paper warmth choices:

- 纸白 — `#FAF6E9`;
- 宣纸暖白 — `#F6F0DF` (default);
- 米杏 — `#F1E7CF`;
- 古纸 — `#EFE3C5`.

Dark mode uses warm charcoal/brown neutrals rather than a yellow-filled dark UI.

## P6-R4 — Visual theme palette UX — COMPLETE

Theme settings now present real preview cards instead of text-only palette chips for the five presets:

- 闻知墨韵 (default);
- 宣纸暖白;
- 薄荷;
- 蓝色;
- 紫色.

The WENZHI Paper card exposes the four paper warmth swatches. The Custom section provides eleven visual accent swatches plus advanced HEX and HSL controls. Preview cards follow the selected System/Light/Dark display mode.

## P6-R5 — Custom theme generator — COMPLETE

Custom no longer reuses Mint surfaces. A selected accent generates a controlled low-chroma neutral/surface ladder plus primary, container and supporting roles. Strong accent choices therefore do not flood the whole UI with saturated color.

## P6-R6 — Transcript theme regression — IMPLEMENTED / FINAL GATE PENDING

Automated contracts now cover WENZHI Ink, WENZHI Paper, Mint, Blue, Purple, and Custom in both light and dark modes. They verify readable theme-derived word-highlight roles, visible active accents, distinct paragraph/word emphasis where the palette uses separate container roles, and the frozen QA4 rendering contract: fixed text geometry, weak paragraph paint, strong word paint, no hard-coded transcript color.

## P7 — Brand/theme hard-code cleanup — COMPLETE

User-visible product copy and resources use WENZHI / 闻知. Technical package, application, notification-channel and compatibility identifiers retain `Voica` where changing identity would be unsafe or unnecessary. The transcript reading layer uses semantic theme tokens instead of fixed highlight colors.

## Remaining gates

- P8 — final Fast PR Gate covering unit tests, debug compile, Surface contracts, reading-highlight contracts, brand/static contracts, and FSMN runtime boundary;
- P9 — final real-device QA across the five presets, Light/Dark appearance, paper warmth, custom palette controls, and transcript paragraph/word emphasis.

Stage 17 remains **NOT STARTED**.

This document does not authorize merging this Stage 16X branch. PR #36 remains draft / NO MERGE until P8 and P9 are complete.
