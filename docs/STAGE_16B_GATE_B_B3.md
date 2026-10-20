# Stage 16B Gate B — B3 Punctuation / ITN / Language / Hallucination

Status: **AUTOMATED PASS / REAL-DEVICE NEXT**

Date: 2026-10-20

## Objective

B3 separates capabilities that must not be collapsed into one raw CER score:

1. native punctuation;
2. inverse text normalization (ITN);
3. language auto/forcing behavior;
4. short-utterance robustness;
5. no-speech / noise hallucination behavior at the product-pipeline boundary.

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
- B3 has a separate `Voica Gate B3 QA` launcher only in `qa` / `releaseQa` manifests.

## Challenge profiles

### `PUNCTUATION_ZH`

Reference:

`今天开会讨论三件事：第一，预算；第二，进度；第三，风险。你觉得哪一项最重要？`

Runs one native-output pass per candidate. B3 records raw text plus native comma, sentence-end, question-mark, exclamation-mark and colon/semicolon counts. Punctuation is reviewed separately from punctuation-insensitive content CER.

### `ITN_COMPLEX`

Reference:

`今天是二零二六年十月二十日，会议下午三点二十五分开始，预算是十二万三千四百五十六元，完成率百分之八十七点五，联系电话是一三八零零一三八零零零。`

Variants:

- FunASR `ITN_ON`;
- FunASR `ITN_OFF`;
- SenseVoice `ITN_ON`;
- SenseVoice `ITN_OFF`;
- Qwen3 `NATIVE_NO_ITN_TOGGLE`.

Qwen3 is not assigned a fictional ITN switch because the current runtime integration does not expose one.

B3 records semantic coverage for five groups: date, time, money, percentage and phone number. Spoken-number and digit-form equivalents are accepted as the same semantic evidence; raw formatting remains visible for manual review.

### `LANG_ZH`, `LANG_EN`, `LANG_MIXED`

References:

- ZH: `今天我们只测试中文语音识别，请完整记录这句话。`
- EN: `Today we are testing English speech recognition. Please transcribe this sentence completely.`
- mixed: `今天用 Android 和 OpenAI 测试 offline ASR，版本是 Qwen3。`

Variants:

- FunASR: auto / force Chinese / force English;
- SenseVoice: auto / force `zh` / force `en`;
- Qwen3: default only, explicitly marked `DEFAULT_NO_LANGUAGE_FORCE`.

The FunASR runtime uses its native prompt-language values `中文` / `英文`; SenseVoice continues to use its runtime-native `zh` / `en` values. This avoids pretending two different runtime contracts use the same parameter vocabulary.

### `SHORT_UTTERANCE`

Reference:

`可以。`

Checks whether exact FSMN plus each default ASR preserves a very short answer without blank output or semantic expansion.

### `SILENCE` and `NOISE`

Each uses about five seconds with no speech.

If exact FSMN produces no speech range, B3 records `vadFilteredNoSpeech=true` and `pipelineHallucinationRisk=FILTERED_BEFORE_ASR`. This is a product-pipeline no-speech result, not a claim about intrinsic no-VAD ASR behavior.

If FSMN emits a frozen input, all three default ASRs run on the same chunk and B3 records whether any candidate produces nonblank hallucinated text.

## Evidence fields

For each applicable variant B3 reports:

- model ID and variant label;
- capability note;
- engine-open time and ASR wall time (signals only; B5 owns performance ranking);
- ASR call and blank-call counts;
- detected-language metadata when actually supplied by the runtime;
- punctuation counts;
- punctuation-insensitive content CER where a literal reference is meaningful;
- ITN semantic coverage for `ITN_COMPLEX`;
- hallucination nonblank flag for no-speech profiles;
- complete raw model text.

## Capability integrity rules

- unsupported controls are labeled unsupported/default rather than emulated;
- native punctuation is evaluated without the product external punctuation stage;
- ITN semantic equivalence is not scored as raw-character failure;
- language forcing uses each runtime's actual parameter contract;
- no-speech filtered by FSMN is reported as pipeline-level evidence, not intrinsic ASR superiority.

## Automated checkpoint

B3 code checkpoint: `39df8817586225d5f30e162b8d342ac1af74bec1`.

- Android PR CI #1995 Fast PR Gate: **PASS**;
- repository contracts: **PASS**;
- B3 unit tests + Debug compile: **PASS**;
- runtime-native language-value regression: **PASS**;
- ITN semantic-equivalence regression: **PASS**;
- punctuation evidence regression: **PASS**;
- FSMN runtime boundary: **PASS**;
- Production Gate remains skipped.

Production-like QA artifact checkpoint: `382179fc92c619dac218e053f93e4e6770b1862a`.

- Android PR CI #1997 QA APK Gate: **PASS**;
- `assembleReleaseQa`: **PASS**;
- APK verification / security / model-channel checks: **PASS**;
- artifact upload: **PASS**;
- artifact ID: `12262492806`;
- artifact digest: `sha256:76f5a4e9518222af675315d0dea23dc2aec0ba84cdc038627f9787c8a261d7c9`;
- extracted APK SHA-256: `ec46550bf3c2a8945de87ec6d2fe4ee5675eef0e69197a93651af18412637a6c`.

CI success does not substitute for B3 real-device evidence.

## Hotword / context bias follow-up

Gate B still requires a capability check for hotwords/context bias. Core B3 first establishes punctuation/ITN/language/hallucination. If FunASR and Qwen3 remain viable after those checks, a small B3 follow-up A/B probe will compare their actual hotword controls; SenseVoice will be marked unsupported where the current runtime exposes no equivalent control. Hotwords will not be enabled in the base quality runs.

## Real-device order

Run the controlled profiles in a small sequence rather than as one large matrix:

1. `PUNCTUATION_ZH`;
2. `ITN_COMPLEX`;
3. `LANG_ZH`;
4. `LANG_EN`;
5. `LANG_MIXED`;
6. `SHORT_UTTERANCE`;
7. `SILENCE`;
8. `NOISE`.

Review each checkpoint before expanding the next group if an unexpected runtime failure appears.

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
