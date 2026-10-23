# Stage 16B Gate B — B1 Real-device Report

Status: **REAL-DEVICE PASS / FIRST SHORTLIST COMPLETE**

Date: 2026-10-20

Formal VAD requirement: exact active `fsmn-vad-int8@1.0.0/1`. Every accepted B1 run used the same frozen FSMN output for all three ASR candidates.

Candidates:

- `funasr-nano-int8`
- `sensevoice-2024-int8`
- `qwen3-asr-0.6b-int8`

## Results

| Profile | Fun-ASR-Nano | SenseVoice | Qwen3-ASR | Interpretation |
| --- | ---: | ---: | ---: | --- |
| `ZH_BASE` | CER `0.0000` | CER `0.0000` | CER `0.0000` | all three exact on base Mandarin |
| `ZH_LONG` | CER `0.0345` | CER `0.0862` | CER `0.0000` | Qwen3 strongest long Mandarin; FunASR second; SenseVoice weaker |
| `EN_BASE` | CER/WER `0.0000 / 0.0000` | CER/WER `0.0000 / 0.0000` | CER/WER `0.0000 / 0.0000` | all three exact on base English content |
| `ZH_EN_MIXED` | CER `0.0313` | CER `0.0938` | CER `0.0469` | FunASR strongest; Qwen3 second; SenseVoice weaker on code-switch/proper terms |
| `NUMBERS_ITN_RAW` | raw CER `0.6000`, semantic content correct with active ITN | CER `0.0000` | CER `0.0000` | raw CER is not comparable because FunASR normalized spoken numbers to digits; defer ITN judgment to B3 |
| `PROPER_NOUNS` | CER `0.0313` | CER `0.1250` | CER `0.0469` | FunASR strongest proper-noun completeness; Qwen3 second; SenseVoice materially weaker |
| `BOUNDARY` | CER `0.0000`, head/tail deletion false | CER `0.0000`, head/tail deletion false | CER `0.0000`, head/tail deletion false | no model showed start/end word loss |
| `PAUSE` | CER `0.2368`, `del=9` | CER `0.2368`, `del=9` | CER `0.2368`, `del=9` | non-discriminatory: all three saw the same 3 frozen inputs and omitted the exact same 9 reference characters; use as segmentation evidence, not ASR ranking |

## PAUSE interpretation

`PAUSE` produced `speechSegments=3` and `frozenAsrInputs=3` for all three models. Every model returned three nonblank calls, no duplicate nonblank line, no whole-profile blank, and no cross-segment repetition.

The identical nine-character deletion corresponds exactly to the reference phrases `稍微停一下` + `再停一下`. Because every ASR consumed the same three frozen FSMN chunks and produced the same deletion count, this run cannot distinguish ASR quality. It is retained as useful segmentation/continuity evidence only. A future VAD-focused replay may determine whether the phrases were not spoken as lexical content or were excluded at the shared VAD boundary; B1 does not assign this deletion to any individual ASR.

## Quality trend

B1 does not produce a final production winner.

- **Qwen3-ASR 0.6B INT8**: strongest on long Mandarin and exact on the base/boundary/English profiles; slightly behind FunASR on mixed-language and proper-noun material.
- **Fun-ASR-Nano INT8**: strongest on mixed-language and proper-noun profiles, close to Qwen3 on long Mandarin, and demonstrated active number/date/time ITN behavior that must be scored separately in B3.
- **SenseVoice INT8**: exact on base Mandarin, base English, numbers-as-spoken and boundary tests, but materially worse on long Mandarin, code-switch and proper nouns. It remains relevant because current B1/B0 performance signals show a substantially lighter/faster runtime; those signals are not formal B5 evidence.

The three truly discriminatory spoken-content profiles (`ZH_LONG`, `ZH_EN_MIXED`, `PROPER_NOUNS`) show FunASR and Qwen3 close to each other overall, while SenseVoice is consistently worse on complex lexical content. This is a trend, not a final weighted score.

## Reliability / completeness

Across all eight B1 profiles:

- no candidate crashed;
- no candidate produced a whole-profile blank;
- no deterministic repeat-output failure was observed;
- `BOUNDARY` showed no head/tail deletion for any candidate;
- the formal FSMN baseline remained active;
- exact same frozen PCM chunks were used across the three ASRs per run.

## Performance caveat

B1 was not a formal performance gate. Models were executed sequentially in one app process, so later-model start PSS can include native-runtime residue or allocator retention from earlier candidates. Cold engine-open time also varied strongly between runs. Therefore B1 PSS/RTF/open-time values are evidence signals only and must not be used as the final performance ranking.

B5 should use isolated cold/warm measurements per model (preferably fresh process/app restart per candidate) before drawing memory, load-time or RTF conclusions.

## B1 shortlist decision

No candidate is removed after B1.

- Fun-ASR-Nano: **PROCEED** to B2/B3/B4.
- Qwen3-ASR: **PROCEED** to B2/B3/B4.
- SenseVoice: **PROCEED** to B2/B3/B4 as the current production/lightweight baseline despite weaker complex-content quality; its substantially lower resource cost may justify a differentiated product role and must be evaluated fairly in B5.

This avoids prematurely collapsing the product matrix before real-recording completeness, punctuation/ITN/language/hallucination, timeline compatibility, and isolated performance evidence are available.

## Next

1. B2 — real-recording quality and completeness.
2. B3 — punctuation / ITN / language / hallucination challenge set.
3. B4 — timestamp / canonical timeline compatibility.
4. B5/B6 only after the viable-role matrix is clearer.

No production ASR promotion/removal is authorized by B1. PR #35 and model-channel PR #21 remain Draft; `main`, Room v14, realtime Silero and canonical `sampleIndex` ownership remain unchanged.
