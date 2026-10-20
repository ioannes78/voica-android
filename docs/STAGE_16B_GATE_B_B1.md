# Stage 16B Gate B — B1 Same-input Base Quality

Status: **AUTOMATED PASS / REAL-DEVICE NEXT**

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

Keep B1 intentionally small and discriminatory. Use separate short recordings with a known reference transcript so failures remain attributable.

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

Silence/noise hallucination probes belong to the B1/B3 boundary and may be run after the spoken profiles without expanding the initial spoken corpus.

## Metrics

B1 records both per-profile and aggregate evidence:

- normalized Chinese CER;
- English WER for English-dominant material;
- insertion / deletion / substitution counts;
- blank ASR calls;
- whole-profile blank output;
- head-word and tail-word deletion flags;
- repeated phrase/output flags;
- obvious hallucination flags requiring manual evidence review;
- ASR call count;
- raw candidate output;
- model identity and shared FSMN identity/boundaries.

Punctuation is ignored by normalized CER. English WER uses explicit word tokenization and is case-normalized by the shared scoring layer; it is never substituted for Chinese CER.

### SenseVoice metadata normalization

Real-device B0 evidence showed SenseVoice can emit language metadata such as `<zh>`, `<yue>` and `<ja>` in the raw hypothesis. These are model metadata rather than spoken lexical content.

B1 therefore keeps `rawText` unchanged for audit/manual review but removes only the known SenseVoice language tags `<zh>`, `<en>`, `<ja>`, `<ko>` and `<yue>` from the scoring view before CER/WER, blank, boundary and duplicate-line calculations. Arbitrary angle-bracket text is not stripped.

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

B1 real-device reference-corpus execution is **NOT RUN / NEXT**. CI success does not substitute for real-device evidence.

## First shortlist rule

B1 is a discriminatory gate, not a final weighted score. A candidate may be removed from expensive B5/B6 work when evidence shows a material, repeatable base-quality failure such as:

- frequent deterministic blank output;
- severe head/tail deletion;
- material sentence loss;
- repeated/hallucinated text that changes meaning;
- clearly worse reference error across multiple profiles rather than one isolated utterance.

Close candidates remain in the matrix. Performance/memory alone does not eliminate a candidate in B1; that evidence belongs to B5.

## Human recording procedure

- Record the reference script exactly as written when practical.
- Use a natural speaking rate and normal phone/recorder distance.
- Do not intentionally over-enunciate unless the profile says so.
- Make one recording per profile so the reference remains unambiguous.
- If the speaker makes a reading mistake, discard and rerecord that profile rather than editing the reference after seeing ASR output.

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

After the first-pass reference corpus is complete, summarize per-model error/completeness evidence and decide which candidates proceed to B2/B3/B4. Do not make the final Product Matrix decision until the later gates are complete.
