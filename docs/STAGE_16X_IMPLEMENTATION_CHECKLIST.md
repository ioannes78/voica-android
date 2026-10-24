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
- [ ] P6-R2 — complete Material 3 Surface token consolidation for all preset themes — IMPLEMENTATION IN PROGRESS
- [ ] P6-R2.1 — WENZHI Ink warm Surface cleanup; remove residual cool-purple Surface roles — IMPLEMENTED / GATE PENDING
- [ ] P6-R3 — WENZHI Paper / 宣纸暖白 complete light/dark preset
- [ ] P6-R4 — visual theme palette UX and preset previews
- [ ] P6-R5 — custom theme generator with controlled neutral/surface tonal palettes
- [ ] P6-R6 — all-theme light/dark transcript reading-highlight regression
- [ ] P7 — global user-visible brand/theme hard-code cleanup
- [ ] P8 — automated regression / static brand gates
- [ ] P9 — final real-device QA

Stage 17 remains blocked until Stage 16X closes P9.
