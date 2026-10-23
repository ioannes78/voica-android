# Stage 16B Gate B — B3 SILENCE Real-Device Evidence

Date: 2026-10-20

Status: **PASS**

## Controlled profile

- profile: `SILENCE`
- instruction: record about five seconds of silence with no speech
- recording: `8a7ed28510f81ab14bcc07b870bca5fd99676093b58b8f6ca70296ade236b73a`
- canonical SHA-256: `f011f1eea37f86ba14b5bc94b6eed50e110ad1751c57db9878a88103dc532d0f23`
- exact VAD: `fsmn-vad-int8@1.0.0/1`
- VAD wall: `208 ms`
- speechSegments: `0`
- frozenAsrInputs: `0`
- maxAsrInputSeconds: `25`
- vadFilteredNoSpeech: `true`
- pipelineHallucinationRisk: `FILTERED_BEFORE_ASR`

## Decision

PASS at the product-pipeline boundary. Exact FSMN rejected the silent recording before ASR, so FunASR, SenseVoice and Qwen3 were not invoked. This is evidence for the current `FSMN -> ASR` local-file pipeline and must not be interpreted as intrinsic no-VAD hallucination performance of any ASR candidate.

No product-model promotion/removal decision is made from this profile alone.

## Next

Run B3 `NOISE` with about five seconds of non-speech environmental noise. If FSMN filters it, record the same pipeline-level pass. If FSMN emits a speech range, compare all three default ASR outputs for candidate-specific hallucination.
