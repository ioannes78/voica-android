# Stage 16B Gate B — B3 Real-Device Report

Status: **IN PROGRESS — PUNCTUATION_ZH PASS / ITN_COMPLEX NEXT**

Date: 2026-10-20

## PUNCTUATION_ZH

Real-device controlled recording:

- recording: `8e6a156083dcfb1c03c7b9ae61c8fbb96e0835b8231124d992fdec357451695d`;
- canonical SHA-256: `32a253bcd225add6ea1025b303a6f94d7c4a650af2a9b109d1f1266d39bd9799`;
- exact VAD: `fsmn-vad-int8@1.0.0/1`;
- VAD wall: `307 ms`;
- speech segments: `4`;
- frozen ASR inputs: `4`;
- maximum frozen ASR input: `25 s`;
- `vadFilteredNoSpeech=false`.

Reference:

`今天开会讨论三件事：第一，预算；第二，进度；第三，风险。你觉得哪一项最重要？`

### FunASR Nano INT8 — DEFAULT_NATIVE

- engine open: `10438 ms`;
- ASR wall: `5016 ms`;
- calls / blank: `4 / 0`;
- content CER: `0.0000`;
- punctuation: comma `1`, sentence-end `1`, question `1`, exclamation `0`, colon/semicolon `0`;
- raw text:

  `今天开会讨论三件事，第一预算。`

  `第二进度`

  `第三风险`

  `你觉得哪一项最重要？`

Interpretation: literal content is exact. Native punctuation is partial: the final question mark is preserved, but the reference colon/semicolon structure is not reproduced and the first item is terminated as a sentence.

### SenseVoice INT8 — DEFAULT_NATIVE

- engine open: `2409 ms`;
- ASR wall: `840 ms`;
- calls / blank: `4 / 0`;
- detected language metadata: `<|zh|>`;
- content CER: `0.0000`;
- punctuation: comma `0`, sentence-end `0`, question `0`, exclamation `0`, colon/semicolon `0`;
- raw text:

  `今天开会讨论三件事第一预算`

  `第二进度`

  `第三风险`

  `你觉得哪一项最重要`

Interpretation: literal content is exact, but with external punctuation disabled the native output contains no punctuation evidence in this profile.

### Qwen3-ASR 0.6B INT8 — DEFAULT_NATIVE

- engine open: `7563 ms`;
- ASR wall: `7855 ms`;
- calls / blank: `4 / 0`;
- content CER: `0.0000`;
- punctuation: comma `3`, sentence-end `3`, question `1`, exclamation `0`, colon/semicolon `1`;
- raw text:

  `今天开会讨论三件事： 第一，预算。`

  `第二，进度。`

  `第三，风险。`

  `你觉得哪一项最重要？`

Interpretation: literal content is exact and Qwen3 supplies the strongest native punctuation of the three on the exact same FSMN-frozen inputs. It preserves the colon and final question mark, while rendering the three enumerated items mainly as comma + sentence-end rather than the exact reference semicolon pattern.

## Punctuation checkpoint decision

All three candidates pass content completeness for this profile (`CER=0.0000`, zero blank calls).

Native punctuation ranking for this controlled profile:

1. **Qwen3-ASR** — strongest native punctuation;
2. **FunASR Nano** — partial native punctuation;
3. **SenseVoice** — no native punctuation under the B3 no-external-punctuation contract.

This does not by itself remove SenseVoice from Gate B because production may use a separate punctuation stage. B3 is intentionally measuring native ASR capability only.

Performance timings above are signals only; B5 owns isolated performance/memory ranking.

## Next

Run `ITN_COMPLEX`. Do not change production model selection, Room, VAD policy or model-channel promotion based on this single B3 profile.
