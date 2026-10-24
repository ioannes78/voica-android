# Stage 16X — WENZHI Brand & Theme Consolidation

Status: **CLOSED / P8 PASS / P9 REAL-DEVICE PASS / READY TO MERGE**

Stage 16X consolidates the user-visible WENZHI brand, Material 3 theme foundation, and transcript reading presentation before Stage 17.

Approved revision plan: **Stage 16X Revision Plan V5**, followed by **P9 Revision-1 Preset Theme Visual Hierarchy Refinement**.

## Final implementation state

- WENZHI brand resources are consolidated across launcher, adaptive icon, monochrome icon, notification icon, splash, and user-visible product copy.
- WENZHI Ink is the default preset and uses warm xuan-paper / warm charcoal Surface ladders without cold-purple fallback surfaces.
- WENZHI Paper / 宣纸暖白 is a first-class light/dark preset with four persisted paper warmth options.
- Mint, Blue, Purple, and Custom preserve distinct palette personalities while sharing the same Material 3 role architecture.
- Custom generates controlled low-chroma neutral/surface palettes from the selected accent and supports visual swatches plus HEX/HSL controls.
- Theme settings use visual preset previews and all presets inherit the same Settings / Transcript / Chip / Mini Player hierarchy rules.

## Transcript reading contract — CLOSED / REAL-DEVICE PASS

Playback keeps transcript text geometry fixed while the current-word paint moves independently. No dynamic font weight, font size, letter spacing, baseline shift, or moving text span boundaries are used.

Final hierarchy:

- page = `background`;
- normal transcript segment = `surfaceContainerLow`;
- active paragraph = weak theme-derived emphasis layered over the stable segment surface;
- active line = medium theme accent;
- current word = strongest theme-derived fill/outline emphasis;
- body text geometry remains stable during playback.

No timeline, cue timestamp, seek, ASR, CTC, VAD, diarization, or playback-state mapping logic was changed by the visual refinement.

## P8 — automated regression — PASS

Final Fast PR Gate passed on the Stage 16X branch, including:

- repository/static contracts;
- unit tests;
- Debug compilation;
- Material 3 Surface contracts;
- theme personality contracts;
- transcript reading-highlight contracts;
- transcript layout-stability contract;
- FSMN candidate runtime boundary.

## P9 Revision-1 — real-device QA — PASS

Real-device QA passed for:

1. 闻知墨韵 — Light;
2. 闻知墨韵 — Dark;
3. 宣纸暖白 — Light/Dark;
4. 薄荷 — Light/Dark;
5. 蓝色 — Light/Dark;
6. 紫色 — Light/Dark;
7. Custom — Light/Dark;
8. all-theme word-playback regression.

The final playback regression confirms the same behavior across all presets: active paragraph remains weak, current word remains clearly stronger, and transcript characters / line wrapping stay visually stationary while playback advances.

## Freeze / Handoff

Stage 16X implementation, automated regression, and final real-device QA are complete. PR #36 may be merged to `main` after this closure commit passes the normal Fast PR Gate.

Stage 17 remains **NOT STARTED** and is unblocked only after PR #36 is merged.
