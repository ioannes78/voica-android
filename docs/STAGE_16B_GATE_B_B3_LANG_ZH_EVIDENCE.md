# Stage 16B Gate B — B3 LANG_ZH Real-Device Evidence

Status: **PASS / LANG_EN NEXT**

Date: 2026-10-20

## Recording identity

- profile: `LANG_ZH`
- reference: `今天我们只测试中文语音识别，请完整记录这句话。`
- recording: `d69efc356aef28fec04e8e94c2c002ca9b98b532286500b4d5c35f00923bb793`
- canonical SHA-256: `d5dbd985a3f0af861b93b1700ee1db38cfacadbbef44649cc51afc833638b2ed`
- exact VAD: `fsmn-vad-int8@1.0.0/1`
- VAD wall: `222 ms`
- speech segments: `1`
- frozen ASR inputs: `1`
- max ASR input: `25 s`
- `vadFilteredNoSpeech=false`

## FunASR Nano INT8

### LANG_AUTO

- engine open: `13176 ms`
- ASR wall: `10082 ms`
- calls / blank: `1 / 0`
- content CER: `0.0000`
- raw text: `今天我们只测试中文语音识别，请完整记录这句话。`

### LANG_FORCE_ZH

- runtime-native prompt language: `中文`
- engine open: `19743 ms`
- ASR wall: `2719 ms`
- calls / blank: `1 / 0`
- content CER: `0.0000`
- raw text: `今天我们只测试中文语音识别，请完整记录这句话。`

### LANG_FORCE_EN

- runtime-native prompt language: `英文`
- engine open: `20112 ms`
- ASR wall: `2881 ms`
- calls / blank: `1 / 0`
- content CER: `0.0000`
- raw text: `今天我们只测试中文语音识别，请完整记录这句话。`

Interpretation: on this pure-Mandarin sample, FunASR AUTO, force-Chinese and force-English are behaviorally indistinguishable. Therefore this profile does **not** provide evidence that FunASR language forcing has an observable effect. This is not yet classified as a runtime defect; `LANG_EN` and `LANG_MIXED` are required to determine whether the prompt-language control is weak, source-language preserving, or ineffective in the current integration.

## SenseVoice INT8

### LANG_AUTO

- engine open: `2493 ms`
- ASR wall: `590 ms`
- calls / blank: `1 / 0`
- detected language: `<|zh|>`
- content CER: `0.0000`
- raw text: `今天我们只测试中文语音识别请完整记录这句话`

### LANG_FORCE_ZH

- engine open: `2421 ms`
- ASR wall: `575 ms`
- calls / blank: `1 / 0`
- detected language: `<|zh|>`
- content CER: `0.0000`
- raw text: `今天我们只测试中文语音识别请完整记录这句话`

### LANG_FORCE_EN

- engine open: `2274 ms`
- ASR wall: `528 ms`
- calls / blank: `1 / 0`
- detected language: `<|en|>`
- content CER: `0.7619`
- raw text: `今测试识句`

Interpretation: SenseVoice language forcing is behaviorally active and strong on this profile. Correct/auto language yields exact content while incorrect English forcing materially degrades the Mandarin transcription and changes runtime language metadata to `<|en|>`.

## Qwen3-ASR 0.6B INT8

### DEFAULT_NO_LANGUAGE_FORCE

- engine open: `7184 ms`
- ASR wall: `3924 ms`
- calls / blank: `1 / 0`
- content CER: `0.0000`
- raw text: `今天我们只测试中文语音识别，请完整记录这句话。`

Interpretation: Qwen3 default behavior is exact on this profile. Current integration exposes no language-forcing control, so no forced-language comparison is claimed.

## Checkpoint decision

- All default/correct-language paths are nonblank and exact on this controlled Mandarin profile.
- SenseVoice forcing is demonstrably wired and can strongly constrain recognition when the wrong language is forced.
- FunASR forcing remains **unproven / behaviorally non-observable** on `LANG_ZH`; do not advertise strong language forcing from this sample alone.
- Qwen3 remains default-only for language control.
- Performance values are signals only; B5 owns isolated performance/memory ranking.

## Next

Run `LANG_EN` on the fixed B3 English reference, then `LANG_MIXED`. Use those two profiles to determine whether FunASR prompt-language forcing has real behavioral effect before any Product Matrix capability claim.
