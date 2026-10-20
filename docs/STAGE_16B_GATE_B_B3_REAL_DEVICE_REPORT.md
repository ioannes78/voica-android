# Stage 16B Gate B — B3 Real-Device Report

Status: **IN PROGRESS — PUNCTUATION_ZH PASS / ITN_COMPLEX PASS / LANG_ZH NEXT**

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

## ITN_COMPLEX

Real-device controlled recording:

- recording: `e823e7d55d6d9c21e3cf139bd560700c0a9f1d6314572e5923667ddefe93020b`;
- canonical SHA-256: `ef63deaafadcc817b251d432a8d505fccfe2b7a5dabadeedfbf857e67eae6515`;
- exact VAD: `fsmn-vad-int8@1.0.0/1`;
- VAD wall: `388 ms`;
- speech segments: `6`;
- frozen ASR inputs: `6`;
- maximum frozen ASR input: `25 s`;
- `vadFilteredNoSpeech=false`.

Reference:

`今天是二零二六年十月二十日，会议下午三点二十五分开始，预算是十二万三千四百五十六元，完成率百分之八十七点五，联系电话是一三八零零一三八零零零。`

The semantic-coverage score has five groups: date, time, money, percentage and phone number. Spoken-number and digit-form equivalents count as the same semantic evidence. Formatting behavior and lexical errors are reviewed separately.

### FunASR Nano INT8 — ITN_ON

- engine open: `10433 ms`;
- ASR wall: `42731 ms`;
- calls / blank: `6 / 0`;
- ITN semantic coverage: `1.00`;
- raw text:

  `今天是2026年10月20日。`

  `会议下午三点二十五分开始。`

  `预算是十二万三千四百五十六元。`

  `完成率百分之八十七点五。`

  `联系电话是`

  `13800138000`

Interpretation: all five semantic groups are preserved. ITN is demonstrably active, converting the date and phone number to digit form, but it does not fully normalize time, money and percentage in this sample. Therefore FunASR ITN is effective but partial rather than a comprehensive formatter.

### FunASR Nano INT8 — ITN_OFF

- engine open: `9253 ms`;
- ASR wall: `25860 ms`;
- calls / blank: `6 / 0`;
- ITN semantic coverage: `0.80`;
- raw text:

  `今天是二零二六年十月二十日`

  `会议下午三点二十五分开始`

  `预算是十二万三千四百五十六元`

  `完成率百分之八十七点五`

  `联系电话是`

  `么三八零零么三八零零零`

Interpretation: disabling ITN changes the output as expected. Date/time/money/percentage remain in spoken-number form, while the phone-number group fails because the spoken `一` digits are misrecognized as `么` in this recording.

### SenseVoice INT8 — ITN_ON

- engine open: `2561 ms`;
- ASR wall: `1440 ms`;
- calls / blank: `6 / 0`;
- detected language metadata: `<|zh|>`;
- ITN semantic coverage: `1.00`;
- raw text:

  `今天是2026年10月20日。`

  `会议下午3点25分开始。`

  `预算是123456元。`

  `完成率87.5%。`

  `联系电话时。`

  `13800138000。`

Interpretation: this is the strongest ITN formatting behavior in the controlled sample: date, time, money, percentage and phone number are all normalized to compact digit form. The raw transcript still contains a local lexical error, `联系电话是` -> `联系电话时`, so full semantic-group coverage must not be confused with perfect literal transcription.

### SenseVoice INT8 — ITN_OFF

- engine open: `2324 ms`;
- ASR wall: `8380 ms`;
- calls / blank: `6 / 0`;
- detected language metadata: `<|zh|>`;
- ITN semantic coverage: `0.80`;
- raw text:

  `今天是二零二六年十月二十日`

  `会议下午三点二十五分开始`

  `预算是十二万三千四百五十六元`

  `完成率百分之八十七点五`

  `联系电话时`

  `么三八零零么三八零零零`

Interpretation: disabling ITN again behaves as expected and keeps spoken-number form, but the phone-number group fails and the same `是` -> `时` lexical error remains.

### Qwen3-ASR 0.6B INT8 — NATIVE_NO_ITN_TOGGLE

- engine open: `30853 ms`;
- ASR wall: `24296 ms`;
- calls / blank: `6 / 0`;
- ITN semantic coverage: `0.80`;
- raw text:

  `今天是二零二六年十月二十日。`

  `会议下午三点二十五分开始。`

  `预算是十二万三千四百五十六元。`

  `完成率百分之八十七点五。`

  `联系电话是。`

  `么三八零零，么三八零零零。`

Interpretation: current integration exposes no ITN toggle and Qwen3 keeps spoken-number forms in this sample. The phone-number group fails because the leading `一` digits are misrecognized as `么`; an inserted comma also splits the number.

## ITN checkpoint decision

All five variants completed with zero blank calls under the same exact FSMN-frozen inputs.

Controlled-sample conclusions:

1. **SenseVoice ITN_ON** — strongest end-user numeric formatting; all five semantic groups covered and all five categories normalized to digit form, with one unrelated local lexical substitution (`是` -> `时`).
2. **FunASR ITN_ON** — all five semantic groups covered and ITN is clearly functional, but formatting is partial in this sample: date/phone normalize while time/money/percentage remain spoken-number forms.
3. **Qwen3 default** — no exposed ITN switch, keeps spoken-number forms and misses the phone-number semantic group in this sample.
4. Both FunASR and SenseVoice ON/OFF pairs provide real evidence that their ITN controls are wired and behaviorally active.

The `0.80` OFF/default scores are not interpreted as generic ASR quality rankings; they are driven by the phone-number group in this specific recording. Punctuation counts on this ITN profile are not used for punctuation ranking because decimal points/digit formatting can affect the raw punctuation counter. `PUNCTUATION_ZH` remains the punctuation benchmark.

Performance timings above are signals only; B5 owns isolated performance/memory ranking.

## Next

Run `LANG_ZH`, followed by `LANG_EN` and `LANG_MIXED`. Do not change production model selection, Room, VAD policy or model-channel promotion based on these partial B3 checkpoints.
