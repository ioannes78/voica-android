# Stage 16X Implementation Checklist

- [x] Gate 0 — Stage 16 merged to main and tested tree verified
- [x] P1 — brand/theme/transcript implementation audit
- [x] P2 — exact WENZHI launcher/adaptive/monochrome/notification/splash resource chain
- [x] P3 — semantic + feature theme token architecture
- [x] P4 — WENZHI Ink default light/dark palette
- [x] P5 — legacy palette persistence compatibility
- [x] P6 — gentle transcript playback reading highlight baseline
- [x] P6-R1 — QA4 transcript layout stability implementation: fixed text geometry + independent word paint layer
- [x] P6-R1 Fast PR Gate — unit tests / debug compile / FSMN boundary passed
- [x] P6-R1 QA4 — real-device PASS: continuous playback keeps transcript text geometry visually stationary while current-word highlight moves
- [x] P6-R2 — complete Material 3 Surface token consolidation for all preset themes
- [x] P6-R2.1 — WENZHI Ink warm Surface cleanup; residual cool-purple Surface fallback removed
- [x] P6-R3 — WENZHI Paper / 宣纸暖白 complete light/dark preset + four paper warmth options
- [x] P6-R4 — visual theme palette UX and real preset previews
- [x] P6-R5 — custom theme generator with controlled neutral/surface tonal palettes + HEX/HSL controls
- [x] P6-R6 — all-theme light/dark transcript reading-highlight regression contracts implemented
- [x] P7 — user-visible brand/theme hard-code cleanup audit; technical compatibility identifiers retained
- [ ] P8 — final automated regression / static brand gates — FINAL FAST GATE PENDING
- [ ] P9 — final real-device QA

Stage 17 remains blocked until Stage 16X closes P9.
