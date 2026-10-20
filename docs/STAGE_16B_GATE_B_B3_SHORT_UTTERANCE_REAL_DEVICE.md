# Stage 16B Gate B — B3 SHORT_UTTERANCE Real-Device Evidence

Status: **PASS**

Date: 2026-10-20

Profile: `SHORT_UTTERANCE`

Reference: `可以。`

Real-device recording:

- recording: `14eccbc8-be03-4d22-a597-3cb7e76470f0`;
- canonical SHA-256: `99b4c6b3eb4c62dc99f6d9adc1406e601fdf4675b463a74bac5dadfc1c569fde`;
- exact VAD: `fsmn-vad-int8@1.0.0/1`;
- VAD wall: `194 ms`;
- speech segments: `1`;
- frozen ASR inputs: `1`;
- maximum frozen ASR input: `25 s`;
- `vadFilteredNoSpeech=false`.

Results:

- FunASR Nano INT8 / DEFAULT_NATIVE: `1` call, `0` blank, `contentCer=0.0000`, text `可以。`;
- SenseVoice INT8 / DEFAULT_NATIVE: `1` call, `0` blank, detected language `<|zh|>`, `contentCer=0.0000`, text `可以`;
- Qwen3-ASR 0.6B INT8 / DEFAULT_NATIVE: `1` call, `0` blank, `contentCer=0.0000`, text `可以。`.

Decision:

All three candidates preserve the very short utterance without blank output, semantic loss, repetition, or hallucinated expansion. FunASR and Qwen3 include native sentence-end punctuation; SenseVoice remains punctuation-free under the B3 no-external-punctuation contract. This profile is a clean PASS for all three candidates.

Performance timings are evidence-only; B5 owns isolated performance ranking.

Next: run `SILENCE`, then `NOISE`.
