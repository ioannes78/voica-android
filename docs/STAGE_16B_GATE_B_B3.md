# Stage 16B Gate B — B3 Punctuation / ITN / Language / Hallucination

Status: **AUTOMATED PASS / REAL-DEVICE PASS**

Date: 2026-10-20

## Objective

B3 separates capabilities that must not be collapsed into one raw CER score:

1. native punctuation;
2. inverse text normalization (ITN);
3. language auto/forcing behavior;
4. short-utterance robustness;
5. no-speech / noise hallucination behavior at the product-pipeline boundary;
6. supported hotword / context-bias behavior.

Candidates remain:

- `funasr-nano-int8`;
- `sensevoice-2024-int8`;
- `qwen3-asr-0.6b-int8`.

No B3 result changes the production model matrix by itself.

## Frozen fairness contract

- exact active `fsmn-vad-int8` is mandatory;
- Silero fallback invalidates formal Gate B evidence;
- FSMN runs once on canonical PCM;
- canonical `sampleIndex` boundaries are frozen once;
- every ASR variant for a profile receives the same frozen PCM chunks;
- common maximum frozen ASR input duration is **25 seconds**, carried forward from the verified B2 safe comparison boundary;
- B3 recordings are limited to 60 seconds;
- external punctuation is disabled;
- Qwen timeline alignment is disabled;
- benchmark output is not persisted to Room;
- B3 QA launchers exist only in `qa` / `releaseQa` manifests.

## Controlled profiles

### `PUNCTUATION_ZH`

Reference:

`今天开会讨论三件事：第一，预算；第二，进度；第三，风险。你觉得哪一项最重要？`

One native-output pass per candidate. Result: all three preserve literal content (`CER=0.0000`); Qwen3 provides the strongest native punctuation, FunASR partial native punctuation, SenseVoice no native punctuation under the no-external-punctuation contract.

### `ITN_COMPLEX`

Reference:

`今天是二零二六年十月二十日，会议下午三点二十五分开始，预算是十二万三千四百五十六元，完成率百分之八十七点五，联系电话是一三八零零一三八零零零。`

Variants:

- FunASR `ITN_ON` / `ITN_OFF`;
- SenseVoice `ITN_ON` / `ITN_OFF`;
- Qwen3 `NATIVE_NO_ITN_TOGGLE`.

Result: SenseVoice `ITN_ON` gives the strongest compact numeric formatting on the controlled sample; FunASR ITN is behaviorally active and preserves all semantic groups with partial normalization; Qwen3 current integration exposes no ITN switch.

### `LANG_ZH`, `LANG_EN`, `LANG_MIXED`

References:

- ZH: `今天我们只测试中文语音识别，请完整记录这句话。`
- EN: `Today we are testing English speech recognition. Please transcribe this sentence completely.`
- mixed: `今天用 Android 和 OpenAI 测试 offline ASR，版本是 Qwen3。`

Variants:

- FunASR: auto / force Chinese / force English;
- SenseVoice: auto / force `zh` / force `en`;
- Qwen3: default only, explicitly marked `DEFAULT_NO_LANGUAGE_FORCE`.

Result: all normal/default paths are exact on monolingual ZH/EN. On mixed speech FunASR AUTO is the best exact-content result. Language forcing is not verified as a hard language lock for either FunASR or SenseVoice and can degrade mixed or mismatched-language input. Product recommendation is AUTO/default; any forcing control should remain advanced/experimental hint semantics rather than a guaranteed output-language setting.

### `SHORT_UTTERANCE`

Reference: `可以。`

Result: all three candidates produce nonblank, semantically exact output with `contentCer=0.0000`. No repetition or hallucinated expansion.

### `SILENCE` and `NOISE`

Both controlled no-speech samples were rejected by exact FSMN before ASR:

- `speechSegments=0`;
- `frozenAsrInputs=0`;
- `vadFilteredNoSpeech=true`;
- `pipelineHallucinationRisk=FILTERED_BEFORE_ASR`.

This is product-pipeline hallucination-protection evidence for `FSMN -> ASR`, not intrinsic no-VAD hallucination ranking between ASR models.

## Hotword / context-bias follow-up

Reference:

`今天测试 Qwen3、OpenAI 和 GitHub 的语音识别效果。`

Configured hotwords: `Qwen3,OpenAI,GitHub`.

SenseVoice is excluded because the current integration exposes no equivalent hotword control; unsupported capability is not emulated.

Same exact FSMN-frozen PCM OFF/ON results:

| Candidate | OFF CER | ON CER | OFF coverage | ON coverage |
| --- | ---: | ---: | ---: | ---: |
| FunASR Nano | `0.1724` | `0.0345` | `2/3` | `3/3` |
| Qwen3-ASR | `0.3103` | `0.0690` | `0/3` | `2/3` |

Both supported runtimes show material same-input improvement with hotwords enabled. FunASR reaches the stronger controlled-sample ON result; Qwen3 recovers `OpenAI` and `GitHub` but still does not produce an exact `Qwen3` hit. This is capability evidence, not a general ASR-quality ranking.

Detailed evidence: `docs/STAGE_16B_GATE_B_B3_HOTWORD_REAL_DEVICE.md`.

## Evidence documents

- `docs/STAGE_16B_GATE_B_B3_REAL_DEVICE_REPORT.md` — punctuation / ITN checkpoint detail;
- `docs/STAGE_16B_GATE_B_B3_LANG_ZH_EVIDENCE.md` — initial ZH language evidence;
- `docs/STAGE_16B_GATE_B_B3_LANGUAGE_REAL_DEVICE_REPORT.md` — completed language matrix;
- `docs/STAGE_16B_GATE_B_B3_SHORT_UTTERANCE_REAL_DEVICE.md` — short utterance;
- `docs/STAGE_16B_GATE_B_B3_SILENCE_REAL_DEVICE.md` — silence;
- `docs/STAGE_16B_GATE_B_B3_NOISE_REAL_DEVICE.md` — environmental noise;
- `docs/STAGE_16B_GATE_B_B3_HOTWORD_REAL_DEVICE.md` — hotword/context-bias A/B.

## Automated checkpoints

Core B3 code checkpoint: `39df8817586225d5f30e162b8d342ac1af74bec1`.

- Android PR CI #1995 Fast PR Gate: **PASS**;
- B3 unit tests + Debug compile: **PASS**;
- runtime-native language-value regression: **PASS**;
- ITN semantic-equivalence regression: **PASS**;
- punctuation evidence regression: **PASS**;
- FSMN runtime boundary: **PASS**.

Core B3 production-like QA artifact checkpoint: `382179fc92c619dac218e053f93e4e6770b1862a`.

- Android PR CI #1997 QA APK Gate: **PASS**;
- artifact ID `12262492806`;
- artifact digest `sha256:76f5a4e9518222af675315d0dea23dc2aec0ba84cdc038627f9787c8a261d7c9`;
- extracted APK SHA-256 `ec46550bf3c2a8945de87ec6d2fe4ee5675eef0e69197a93651af18412637a6c`.

Hotword follow-up checkpoint: `7e22e2b184c34092c9a6082cf32925600aac8e63`.

- Android PR CI #2012 Fast PR Gate: **PASS**;
- unit tests + Debug compile: **PASS**;
- FSMN runtime boundary: **PASS**;
- Android PR CI #2013 QA APK Gate: **PASS**;
- production-like `releaseQa` build/verification/security: **PASS**;
- artifact ID `12276585259`;
- artifact digest `sha256:bcdbc66828ef138237ec8f6e3e00a8b1bc8ca77109b7801d179d948cef4b26b2`;
- extracted APK SHA-256 `1417ae43a2f091c823aa4299d595dafb322c824b4554db00047e8382200ca635`.

## B3 decision

**B3 REAL-DEVICE PASS.**

The evidence supports the following product-matrix interpretation without yet promoting/removing any ASR model:

- Qwen3: strongest native punctuation and strong long-Mandarin evidence from B1;
- FunASR: strongest mixed-language/proper-noun tendency so far, active ITN, and strongest supported hotword A/B result;
- SenseVoice: strongest controlled ITN formatting and materially lower runtime-cost signals, but weaker complex mixed/proper-noun accuracy and no equivalent hotword control in the current integration;
- AUTO/default language behavior should be the normal product path;
- silence/noise are safely blocked by exact FSMN in the tested local-file pipeline.

Performance signals from B3 are not rankings; B5 owns isolated cold/warm performance and memory comparison.

## Frozen product state

B3 does not:

- merge Android PR #35;
- merge model-channel PR #21;
- modify `main`;
- promote Fun-ASR-Nano;
- remove SenseVoice or Qwen3-ASR;
- change Room v14;
- change Local File `FSMN -> Silero` production policy;
- change realtime Silero;
- change canonical `sampleIndex` ownership;
- modify QS668/CB08, Android Audio I/O, playback or Stage 16A.2 ownership;
- start Stage 16C.

## Next

Proceed to **B4 — Timestamp / Timeline Compatibility**. B4 must preserve canonical `sampleIndex` ownership and separately account for native token timing versus any additional alignment path and its cost.
