# Stage 16B Gate B — B3 Real-Device Report

Status: **PASS**

Date: 2026-10-20

This file is the consolidated B3 real-device closure. Detailed per-profile evidence is kept in the dedicated reports linked below.

## Frozen contract

All formal B3 runs used:

- exact `fsmn-vad-int8@1.0.0/1`;
- FSMN once on canonical PCM;
- frozen canonical `sampleIndex` boundaries reused by all applicable ASR variants;
- common maximum ASR input duration `25 s`;
- no external punctuation;
- no Qwen timeline alignment;
- no Room benchmark persistence.

Unsupported model controls were not emulated.

## Controlled-profile summary

### PUNCTUATION_ZH — PASS

Reference:

`今天开会讨论三件事：第一，预算；第二，进度；第三，风险。你觉得哪一项最重要？`

All three candidates preserve literal content with `CER=0.0000` and zero blank calls.

Native punctuation ordering on this controlled profile:

1. Qwen3-ASR — strongest native punctuation;
2. FunASR Nano — partial native punctuation;
3. SenseVoice — no native punctuation under the B3 no-external-punctuation contract.

This does not eliminate SenseVoice because production may use a separate punctuation stage.

### ITN_COMPLEX — PASS

Reference includes date, time, money, percentage and phone-number groups.

Observed behavior:

- SenseVoice `ITN_ON`: strongest compact digit-form normalization on the controlled sample, semantic coverage `1.00`;
- FunASR `ITN_ON`: semantic coverage `1.00`; ITN is clearly active but normalization is partial across categories;
- Qwen3: current integration exposes no ITN switch and keeps mostly spoken-number forms; phone-number group failed in this sample;
- FunASR and SenseVoice ON/OFF pairs prove their ITN controls are behaviorally wired.

Raw formatting and lexical errors remain separate from semantic-group coverage.

### LANG_ZH / LANG_EN / LANG_MIXED — PASS

Monolingual default/AUTO paths are exact on the controlled Chinese and English profiles.

Mixed-language profile literal CER:

- FunASR AUTO: `0.2703` — best controlled exact-content result;
- Qwen3 default: `0.4324`;
- SenseVoice AUTO: `0.4865`.

Language-forcing controls are not verified hard locks. FunASR forcing is weak/non-locking on monolingual speech and can degrade mixed speech. SenseVoice forcing is asymmetric: forcing English can damage Chinese input, while forcing Chinese does not force English input into Chinese.

Product recommendation: normal product behavior should remain AUTO/default. Any forcing control should be treated as advanced/experimental hint semantics, not as a guaranteed output-language lock.

Detailed matrix: `docs/STAGE_16B_GATE_B_B3_LANGUAGE_REAL_DEVICE_REPORT.md`.

### SHORT_UTTERANCE — PASS

Reference: `可以。`

All three candidates produce nonblank, semantically exact output with `contentCer=0.0000`; no repetition or hallucinated expansion.

Detailed evidence: `docs/STAGE_16B_GATE_B_B3_SHORT_UTTERANCE_REAL_DEVICE.md`.

### SILENCE — PASS

Exact FSMN produces:

- `speechSegments=0`;
- `frozenAsrInputs=0`;
- `vadFilteredNoSpeech=true`;
- `pipelineHallucinationRisk=FILTERED_BEFORE_ASR`.

ASR is not invoked. This is product-pipeline hallucination-protection evidence, not intrinsic no-VAD ASR ranking.

Detailed evidence: `docs/STAGE_16B_GATE_B_B3_SILENCE_REAL_DEVICE.md`.

### NOISE — PASS

Exact FSMN again produces zero speech ranges / frozen ASR inputs and filters the environmental-noise sample before ASR.

Detailed evidence: `docs/STAGE_16B_GATE_B_B3_NOISE_REAL_DEVICE.md`.

## Hotword / context-bias A/B — PASS

Reference:

`今天测试 Qwen3、OpenAI 和 GitHub 的语音识别效果。`

Configured hotwords: `Qwen3,OpenAI,GitHub`.

Same exact FSMN-frozen PCM results:

| Candidate | OFF CER | ON CER | OFF exact coverage | ON exact coverage |
| --- | ---: | ---: | ---: | ---: |
| FunASR Nano | `0.1724` | `0.0345` | `2/3` | `3/3` |
| Qwen3-ASR | `0.3103` | `0.0690` | `0/3` | `2/3` |

Both supported runtimes show material OFF -> ON improvement with zero blank calls. FunASR reaches the stronger ON result on this one controlled sample. Qwen3 recovers `OpenAI` and `GitHub` but its self-name remains imperfect (`Qwen三力`).

SenseVoice is excluded because the current integration exposes no equivalent hotword control; unsupported capability is not emulated.

Detailed evidence: `docs/STAGE_16B_GATE_B_B3_HOTWORD_REAL_DEVICE.md`.

## B3 product-matrix interpretation

B3 supports the following evidence-based role signals without yet changing production selection:

- Qwen3 — strongest native punctuation;
- FunASR — active ITN, best controlled mixed-language AUTO result, and strongest supported hotword A/B result;
- SenseVoice — strongest compact ITN formatting, but no equivalent hotword control in the current integration;
- all three preserve short utterances;
- exact FSMN blocks the tested silence/noise samples before ASR;
- AUTO/default language behavior is the correct normal product path.

Performance timings observed in B3 are signals only. B5 owns isolated cold/warm performance and memory ranking.

## Decision

**B3 REAL-DEVICE PASS.**

No ASR production promotion/removal is authorized by B3 alone. Android PR #35 and model-channel PR #21 remain Draft/unmerged. Room stays v14; Local File VAD remains `FSMN primary -> Silero fallback`; realtime VAD remains Silero until Stage 16C.1; canonical `sampleIndex` ownership is unchanged.

## Next

Proceed to **B4 — Timestamp / Timeline Compatibility**. B4 must distinguish native token timing from additional alignment paths and preserve canonical `sampleIndex` ownership and timeline semantics.
