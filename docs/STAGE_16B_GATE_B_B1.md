# Stage 16B Gate B — B1 Same-input Base Quality

Status: **AUTOMATED PASS / REAL-DEVICE PASS / FIRST SHORTLIST COMPLETE**

## Objective

B1 is the first discriminatory ASR quality gate after B0 readiness. It compares raw ASR output from the same canonical audio under the exact same frozen FSMN segmentation. It does not select the final production model and does not include downstream punctuation or timeline alignment.

Candidates:

1. `sensevoice-2024-int8`
2. `funasr-nano-int8`
3. `qwen3-asr-0.6b-int8`

## Fairness contract

B1 inherits the B0 execution contract:

1. exact active `fsmn-vad-int8` is mandatory;
2. FSMN runs once per canonical recording;
3. canonical `sampleIndex` speech boundaries are frozen once;
4. the exact same frozen PCM chunks are sent to every ASR candidate;
5. no per-model VAD settings or model-specific speech-boundary manipulation;
6. no external punctuation or Qwen timeline alignment in base-quality scoring;
7. no benchmark transcript persistence to Room.

A run that resolves Silero fallback is invalid B1 evidence.

## Frozen reference corpus — first pass

| Profile | Primary target | Reference script |
| --- | --- | --- |
| `ZH_BASE` | normal Mandarin / common words | 今天我们测试离线语音识别，重点比较三个模型在相同录音上的准确率和完整性。 |
| `ZH_LONG` | long-sentence completeness | 如果会议结束以后还有时间，请把今天讨论的产品计划、预算变化、风险事项和下周需要完成的任务整理成一份清晰的记录，然后发给项目组。 |
| `EN_BASE` | English word accuracy | Today we are testing offline speech recognition on the same recording to compare accuracy, completeness, and stability. |
| `ZH_EN_MIXED` | code-switch / English terms | 今天我们测试 Voica 的 offline ASR，重点检查 Android、GitHub、OpenAI 和 Qwen3 这些英文名称能不能正确识别。 |
| `NUMBERS_ITN_RAW` | numbers/date/time/money before B3 ITN deep test | 今天是二零二六年十月二十日，会议下午三点二十五分开始，预算是十二万三千四百五十六元，完成率百分之八十七点五。 |
| `PROPER_NOUNS` | proper nouns / brands | 这次测试会提到 Android、GitHub、OpenAI、Qwen3、SenseVoice 和 FunASR，请不要把这些名称替换成普通中文词。 |
| `BOUNDARY` | short head/tail words / deletion | 好，现在开始测试。中间这一句保持正常语速。最后请完整识别，谢谢。 |
| `PAUSE` | short natural pauses / segmentation robustness | 第一项检查准确率，稍微停一下，第二项检查完整性，再停一下，第三项检查是否出现重复内容。 |

## Metrics

B1 records normalized Chinese CER, English WER where applicable, insertion/deletion/substitution counts, blank calls, whole-profile blank output, head/tail deletion, repeated output evidence, raw candidate output, model identity, shared FSMN identity/boundaries and manual hallucination/repetition review cues.

Punctuation is ignored by normalized CER. English WER uses explicit word tokenization and is case-normalized by the shared scoring layer; it is never substituted for Chinese CER.

### SenseVoice metadata normalization

Real-device B0 evidence showed SenseVoice can emit language metadata such as `<zh>`, `<yue>` and `<ja>` in the raw hypothesis. B1 keeps `rawText` unchanged for audit/manual review but removes only the known SenseVoice language tags `<zh>`, `<en>`, `<ja>`, `<ko>` and `<yue>` from the scoring view before CER/WER, blank, boundary and duplicate-line calculations. Arbitrary angle-bracket text is not stripped.

Regression coverage locks both conditions:

- known language metadata does not increase CER/WER;
- metadata-only output is still treated as a blank B1 hypothesis.

## Automated checkpoint

Code head: `45954e4b13ef6ab69697e53d44109a5fa0044f3f`.

- Android PR CI #1959 / Fast PR Gate: **AUTOMATED PASS**
- unit tests + debug compile: **PASS**
- B1 frozen corpus/scoring contract tests: **PASS**
- SenseVoice language-metadata scoring regression tests: **PASS**
- FSMN runtime boundary: **PASS**
- Android PR CI #1961 / production-like QA APK Gate: **AUTOMATED PASS**
- `assembleReleaseQa`: **PASS**
- QA APK verification/security/model-channel checks: **PASS**
- QA artifact ID: `12250801192`
- QA artifact digest: `sha256:e7916339c769d25e0bac4d381cb0ee6ac298975e25705c4b4c9cad100d7beca5`
- artifact head: `45954e4b13ef6ab69697e53d44109a5fa0044f3f`

## Real-device checkpoint

Real-device first-pass reference corpus completed on 2026-10-20: **REAL-DEVICE PASS**.

Detailed evidence report:

`docs/STAGE_16B_GATE_B_B1_REAL_DEVICE_REPORT.md`

Summary:

| Profile | Fun-ASR-Nano | SenseVoice | Qwen3-ASR |
| --- | ---: | ---: | ---: |
| `ZH_BASE` | CER `0.0000` | CER `0.0000` | CER `0.0000` |
| `ZH_LONG` | CER `0.0345` | CER `0.0862` | CER `0.0000` |
| `EN_BASE` | CER/WER `0.0000 / 0.0000` | CER/WER `0.0000 / 0.0000` | CER/WER `0.0000 / 0.0000` |
| `ZH_EN_MIXED` | CER `0.0313` | CER `0.0938` | CER `0.0469` |
| `NUMBERS_ITN_RAW` | raw CER `0.6000`, semantic content correct with ITN | CER `0.0000` | CER `0.0000` |
| `PROPER_NOUNS` | CER `0.0313` | CER `0.1250` | CER `0.0469` |
| `BOUNDARY` | CER `0.0000`, head/tail deletion false | CER `0.0000`, head/tail deletion false | CER `0.0000`, head/tail deletion false |
| `PAUSE` | CER `0.2368`, `del=9` | CER `0.2368`, `del=9` | CER `0.2368`, `del=9` |

`PAUSE` is explicitly non-discriminatory ASR evidence: the run produced `speechSegments=3` / `frozenAsrInputs=3`, every ASR returned three nonblank calls with no duplicate line, and all three had the identical nine-character deletion corresponding to `稍微停一下` + `再停一下`. This is retained as segmentation/continuity evidence rather than used to rank ASR quality.

`NUMBERS_ITN_RAW` is also not ranked by raw CER alone. FunASR converted spoken numbers/date/time/money/percentage to digits while preserving semantic content, so its `0.6000` raw CER is an ITN-format mismatch, not a 60% semantic recognition failure. ITN is evaluated formally in B3.

## First shortlist decision

No candidate is removed after B1.

- `funasr-nano-int8`: **PROCEED** to B2/B3/B4.
- `qwen3-asr-0.6b-int8`: **PROCEED** to B2/B3/B4.
- `sensevoice-2024-int8`: **PROCEED** to B2/B3/B4 as the current production/lightweight baseline despite weaker complex-content quality.

B1 trend only:

- Qwen3 is strongest on long Mandarin.
- FunASR is strongest on mixed-language and proper-noun material and shows active ITN behavior.
- SenseVoice is exact on simpler/base tasks and appears substantially lighter/faster, but is consistently weaker on the three complex lexical profiles (`ZH_LONG`, `ZH_EN_MIXED`, `PROPER_NOUNS`).

No final production ranking is authorized by B1. B1 PSS/RTF/engine-open values are signals only because models ran sequentially in one process; B5 must use isolated cold/warm measurements before drawing performance conclusions.

## Frozen product state

B1 does not:

- merge PR #35 or model-channel PR #21;
- modify `main`;
- change product ASR choices;
- promote Fun-ASR-Nano;
- remove SenseVoice or Qwen3-ASR;
- change Room v14;
- change Local File `FSMN → Silero` production policy;
- change realtime Silero;
- change canonical `sampleIndex` ownership;
- start Stage 16C.

## Exit

B1 is complete. All three candidates proceed to B2/B3/B4. The final Product Matrix decision remains blocked on real-recording completeness, punctuation/ITN/language/hallucination, timestamp/timeline compatibility and finalist isolated performance/long-file evidence.
