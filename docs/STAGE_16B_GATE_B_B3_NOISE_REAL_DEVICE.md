# Stage 16B Gate B — B3 NOISE Real-Device Evidence

Status: **PASS — FILTERED BEFORE ASR**

Date: 2026-10-20

Controlled profile: `NOISE`

- recording: `9d22e9898fc979bdf2e088e442557155ff97ae7f82eaa56cc5440742f468c35b`;
- canonical SHA-256: `27742056a3e9438f5745c5e3cb7c25964a7318c59f50db408a74852044112dfc`;
- exact VAD: `fsmn-vad-int8@1.0.0/1`;
- VAD wall: `137 ms`;
- speech segments: `0`;
- frozen ASR inputs: `0`;
- maximum frozen ASR input: `25 s`;
- `vadFilteredNoSpeech=true`;
- `pipelineHallucinationRisk=FILTERED_BEFORE_ASR`.

## Decision

This is a pipeline-level hallucination-protection PASS. The exact FSMN Local File VAD rejected the non-speech environmental-noise recording before any ASR candidate was called, so FunASR Nano, SenseVoice and Qwen3-ASR had no opportunity to hallucinate text on this input.

This evidence must not be interpreted as an intrinsic no-VAD hallucination ranking between the three ASR models. It only proves that the current formal `FSMN -> ASR` pipeline blocks this controlled environmental-noise sample before ASR.

Together with the prior `SILENCE` result, B3 has now demonstrated pre-ASR filtering for both pure silence and ordinary non-speech room/environment noise.

Next: run the small supported-candidate hotword/context-bias A/B follow-up, then close B3 and proceed to B4 Timestamp / Timeline Compatibility.
