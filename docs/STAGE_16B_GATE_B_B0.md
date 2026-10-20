# Stage 16B Gate B — B0 Candidate Readiness

Status: IMPLEMENTATION LANDED / REAL-DEVICE B0 PENDING

## Scope

Gate B compares the current Local File ASR candidates without changing product selection:

1. `sensevoice-2024-int8`
2. `funasr-nano-int8`
3. `qwen3-asr-0.6b-int8`

The formal comparison baseline is frozen to `fsmn-vad-int8`. A run that resolves or falls back to Silero is invalid for Gate B.

## B0 harness contract

The QA-only B0 harness intentionally bypasses the product `TranscriptionCoordinator` for benchmark execution:

1. Resolve active `fsmn-vad-int8` directly. Do not accept the Local File Silero availability fallback.
2. Run FSMN exactly once against the canonical PCM source.
3. Freeze the returned canonical `sampleIndex` speech boundaries.
4. Read the same canonical PCM and deterministically split only oversized speech ranges into at most 30 s ASR chunks.
5. Reuse those exact frozen PCM chunks for every ASR candidate.
6. Run candidates in bring-up order: Fun-ASR-Nano → SenseVoice → Qwen3-ASR.
7. Do not run external punctuation or Qwen timeline alignment in B0. Those are measured separately in later Gate B phases.
8. Do not persist benchmark transcripts or benchmark state to Room.

B0 is intentionally limited to recordings up to 120 s. Representative long-file work remains B6.

## Evidence captured per candidate

- model id/version/revision
- canonical SHA-256 identity from existing canonical lineage
- FSMN model identity and shared VAD wall time
- frozen speech/ASR-input boundary counts
- engine-open wall time
- ASR call count
- blank-call count
- raw ASR text
- detected languages when the runtime exposes them
- ASR wall time
- end-to-end benchmark wall time / RTF
- starting and peak PSS
- managed runtime exception text

A native process crash remains externally observable as an APK/process termination and therefore fails B0 even if no in-process exception can be recorded.

## QA entry

`Stage16BGateBBenchmarkActivity` is compiled once but declared as a launcher only by the `qa` and `releaseQa` manifest overlays. Production `release` has no Gate B launcher declaration.

The QA activity lists recent local recordings and starts B0 directly. Candidate download, integrity verification, installation and activation stay in the existing Model Manager; the benchmark does not duplicate model-management UI.

## Fun-ASR-Nano readiness requirement

Package/integrity CI is not sufficient for B0. Real-device B0 requires:

candidate manifest → download → integrity verification → install → activate → native recognizer creation → nonblank short-audio transcription.

Until that sequence is completed on device, Fun-ASR-Nano remains candidate-only and B0 remains pending.

## Frozen product state

This work does not:

- merge PR #35 or model-channel PR #21;
- modify `main`;
- add Fun-ASR-Nano to production offline ASR choices;
- remove SenseVoice or Qwen3-ASR;
- change Room v14;
- change realtime Silero VAD;
- change Local File production FSMN → Silero availability fallback;
- change canonical `sampleIndex` ownership;
- change Stage 16A.2 annotation, playback, Android Audio I/O or QS668/CB08 ownership;
- start Stage 16C.

## Next gate

After all three candidates pass real-device B0 on the same short recording, proceed to B1 Same-input Base Quality. No production ASR winner or promotion is selected by B0.
