# Stage 16B Gate B — B3 Language Real-Device Report

Status: **LANGUAGE PROFILES COMPLETE / SHORT_UTTERANCE NEXT**

Date: 2026-10-20

This report supplements `STAGE_16B_GATE_B_B3_REAL_DEVICE_REPORT.md` with the controlled language-profile evidence.

## Frozen contract

All formal runs used exact `fsmn-vad-int8@1.0.0/1`, one frozen ASR input per profile, the common 25-second maximum ASR-input boundary, no external punctuation, and no Room persistence.

## LANG_ZH

Reference:

`今天我们只测试中文语音识别，请完整记录这句话。`

Observed results:

| Variant | content CER | Language metadata | Key output behavior |
| --- | ---: | --- | --- |
| FunASR AUTO | 0.0000 | n/a | exact Chinese content |
| FunASR FORCE_ZH | 0.0000 | n/a | same exact Chinese content |
| FunASR FORCE_EN | 0.0000 | n/a | still same exact Chinese content |
| SenseVoice AUTO | 0.0000 | `<|zh|>` | exact Chinese content |
| SenseVoice FORCE_ZH | 0.0000 | `<|zh|>` | exact Chinese content |
| SenseVoice FORCE_EN | 0.7619 | `<|en|>` | strongly degraded; only a short Chinese fragment remained |
| Qwen3 default | 0.0000 | n/a | exact Chinese content |

Interpretation:

- all normal/default paths are exact on this profile;
- SenseVoice `FORCE_EN` is behaviorally observable on Chinese speech and can strongly damage recognition;
- FunASR `FORCE_EN` is not observably different from AUTO on this monolingual Chinese sample.

## LANG_EN

Reference:

`Today we are testing English speech recognition. Please transcribe this sentence completely.`

The first attempted run used an old B1 `EN_BASE` recording with a different sentence and was rejected as formal evidence. The following run used a newly recorded reference-matching sample.

Formal recording: `2fe4370f21913f8f6ccb3ceba00687ea5bec9ebb62a7daf9e0aa5eb829dafc4a`

Canonical SHA-256: `f5dc65fc4858cea2b4e3faddd6cffda60e7a18e4f9019da864600ed76c8409cb`

Observed results:

| Variant | content CER | Language metadata | Key output behavior |
| --- | ---: | --- | --- |
| FunASR AUTO | 0.0000 | n/a | exact English content |
| FunASR FORCE_ZH | 0.0000 | n/a | exact English content; casing changed to uppercase |
| FunASR FORCE_EN | 0.0000 | n/a | exact English content |
| SenseVoice AUTO | 0.0000 | `<|en|>` | exact English content |
| SenseVoice FORCE_ZH | 0.0000 | `<|en|>` | still exact English content and still detected as English |
| SenseVoice FORCE_EN | 0.0000 | `<|en|>` | exact English content |
| Qwen3 default | 0.0000 | n/a | exact English content |

Interpretation:

- all candidates are exact on the monolingual English profile;
- neither FunASR `FORCE_ZH` nor SenseVoice `FORCE_ZH` acts as a reliable hard language lock on English speech;
- together with `LANG_ZH`, SenseVoice forcing is asymmetric: forcing English can damage Chinese speech, while forcing Chinese does not force English speech into Chinese;
- FunASR forcing remains weak/non-locking on monolingual speech.

## LANG_MIXED

Reference:

`今天用 Android 和 OpenAI 测试 offline ASR，版本是 Qwen3。`

Recording: `2999b74fbce4bd7824c195bd45c5d99016f196b514494b1cdaaae4a22119c34f`

Canonical SHA-256: `cf3615edad9ea6d7fb304c43d917d4138c6531d2023194ae82a874facba12997`

Observed results:

| Variant | content CER | Key raw-output behavior |
| --- | ---: | --- |
| FunASR AUTO | **0.2703** | best exact-content score; `Android` rendered as `安卓`, `OpenAI` retained, `offline ASR` retained, `Qwen3` became approximately `queen三` |
| FunASR FORCE_ZH | 0.5405 | worst FunASR variant; lost `offline` and degraded the product-name tail |
| FunASR FORCE_EN | 0.4054 | worse than AUTO; `OpenAI` retained but `offline` was lost and `Qwen3` remained imperfect |
| SenseVoice AUTO | 0.4865 | `OpenAI` degraded, `offline` became an `online...`-like form, `ASR` split, `Qwen3` degraded |
| SenseVoice FORCE_ZH | 0.4865 | effectively no improvement over AUTO |
| SenseVoice FORCE_EN | 0.4595 | slight numeric-CER improvement only; still substantial mixed-language degradation |
| Qwen3 default | 0.4324 | intermediate exact-content score; `offline ASR` was materially distorted and `Qwen3` became approximately `Quan三` |

All variants completed with zero blank calls and no runtime failure.

### Mixed-language decision

For this controlled mixed-language profile, **FunASR AUTO is the best exact-content result**. The ordering by reported content CER is:

1. FunASR AUTO — `0.2703`;
2. FunASR FORCE_EN — `0.4054`;
3. Qwen3 default — `0.4324`;
4. SenseVoice FORCE_EN — `0.4595`;
5. SenseVoice AUTO / FORCE_ZH — `0.4865`;
6. FunASR FORCE_ZH — `0.5405`.

The raw CER is intentionally literal and therefore penalizes semantically reasonable normalization such as `Android` -> `安卓`; manual code-switch review remains necessary. Even with that caveat, forcing does not improve the overall mixed-language result.

## Language-control product conclusion

The three language profiles support the following Gate B interpretation:

- **AUTO/default should remain the primary product behavior.**
- FunASR language forcing is a prompt bias, not a verified hard language lock. It is largely non-observable on monolingual speech and becomes observable mainly by degrading mixed-language transcription.
- SenseVoice forcing is asymmetric and should not be presented as a guaranteed output-language lock. `FORCE_EN` can damage Chinese input, while `FORCE_ZH` does not force English input into Chinese.
- Qwen3 currently exposes no language-forcing control and therefore avoids this ambiguous control surface.
- Do not expose FunASR/SenseVoice language forcing as a normal user-facing guaranteed setting in the Stage 16B product matrix. If retained, treat it as advanced/experimental hint semantics.

No candidate is eliminated solely by these language profiles. Performance timings remain B5 evidence only.

## Next

Run `SHORT_UTTERANCE`, then `SILENCE` and `NOISE`. After the no-speech checks, complete the small hotword/context-bias follow-up before closing B3.
