# Stage 16B — Post-Gate Remediation

Status: **ACTIVE / FINAL FREEZE BLOCKED**

Date: 2026-10-21

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

## Why this remediation exists

The Stage 16B Gate B product-role decision remains valid, but production-like real-device revalidation after the post-gate polish exposed two product blockers in the FunASR Nano high-quality route:

1. the sherpa-onnx FunASR timestamp output was incorrectly promoted as a precise native token timeline;
2. enabling FunASR hotwords can make a long recording lose an entire span of transcription that is present with hotwords disabled.

This document records the corrected current state. It does not rewrite or invalidate historical B0–B6 benchmark evidence.

## Current real-device state

| Item | State |
| --- | --- |
| Stage 16B QA Hub launcher behavior | **PASS** |
| FunASR hotword UI / settings / call path | **PASS** |
| Local Speech product descriptions | **PASS** |
| FunASR ITN switch / runtime use | **PASS** |
| FunASR precise/native token timeline | **FAIL** |
| FunASR Hotword ON transcription completeness | **FAIL** |
| Same 118-second recording, Hotword OFF completeness | **PASS** |
| Stage 16B Final Freeze / Handoff | **BLOCKED** |

## Timestamp correction

Pinned runtime: `sherpa-onnx v1.13.8`.

The current FunASR Nano recognizer implementation in:

`sherpa-onnx/csrc/offline-recognizer-funasr-nano-impl.cc`

does not perform acoustic forced alignment for the generated LLM tokens. After decoding, it derives an `effective_audio_duration` from the amount of injected audio context and distributes generated-token timestamps uniformly across that duration.

Therefore the returned timestamps are **synthetic / evenly distributed timing**. They are useful as a runtime diagnostic of effective audio coverage, but they are not a valid product CTC/forced-alignment timeline and must not be described as such.

A later remediation phase will fail closed on this pseudo-precision before any true CTC alignment route is promoted. That phase is intentionally deferred until the Hotword completeness blocker is fixed and passes real-device QA.

## Hotword completeness blocker

The same 118-second recording has produced materially different completeness results:

- Hotword OFF: the relevant speech span is present;
- Hotword ON: an entire ASR span is absent.

The strongest current mechanism is FunASR Nano context/KV budget pressure. In sherpa-onnx v1.13.8, source context contains system prompt + user/hotword prompt + audio placeholders + assistant prompt. The runtime obtains `max_total_len` from the model KV capacity. When context exceeds that capacity it preferentially truncates audio placeholders; generation also stops once the valid KV length reaches capacity.

This means hotwords can reduce both available audio context and the remaining generation budget. Gate B had already shown a compatible boundary signal: some 30-second FunASR inputs blanked while the same material at a 25-second cap did not.

## P1 — Context-budget diagnostics

P1 adds per-attempt diagnostics for the FunASR product path. Required fields:

- `maxTotalLen`;
- `promptTokens`;
- `hotwordPromptTokens`;
- `audioTokenLen`;
- `contextLen`;
- `availableGenerationTokens`;
- `requestedMaxNewTokens`;
- `generatedTokenCount`;
- `eosReached`;
- `audioTruncated`;
- `generationHitCapacity`;
- `chunkStart` / `chunkEnd` / `chunkDuration`;
- `outputChars`.

The current sherpa Java/Kotlin API does not expose the internal tokenizer counts, `valid_len`, or the exact EOS/capacity exit reason. Values unavailable at the API boundary must be explicitly marked as **estimated/inferred**, never presented as native exact counters.

The v1.13.8 synthetic timestamp coverage is retained only as a diagnostic signal: its final coverage reflects the effective injected audio span and can therefore expose audio-placeholder truncation. It must not be persisted or displayed as a precise product timeline.

## P2 — Adaptive FunASR Chunk Planner

The fixed 25-second FunASR native-call policy is replaced by a FunASR-specific adaptive planner while preserving the existing model package identity `funasr-nano-int8@2025-12-30/1`.

Rules:

1. **Budget aware** — target audio size contracts as prompt/hotword budget grows and reserves generation capacity plus safety margin.
2. **Target 15–18 seconds** — a target range, not a new fixed hard cut.
3. **Natural boundary first** — near the target, prefer a reliable low-energy/pause boundary inside the already-FSMN-approved speech span.
4. **Overlap on continuous speech** — when no reliable natural boundary exists, retain approximately one second of overlap.
5. **Overlap reconciliation** — normalize text and reconcile suffix/prefix duplication before concatenating results. True timeline-aware reconciliation is deferred to the later CTC phase.
6. **Automatic retry** — a speech-bearing chunk that returns blank, exposes audio truncation, hits/inferably exhausts generation capacity, or otherwise shows a strong early-termination signature is not accepted. It is split shorter and rerun. An unrecoverable suspicious leaf fails closed instead of persisting known-incomplete text.
7. **Scoped change** — SenseVoice and FAST/realtime pipelines keep their existing behavior. Room remains v14.

## P2 real-device gate

Generate a new production-like QA APK after CI is green. The user will rerun the fixed 118-second recording with:

- FunASR Hotword OFF;
- FunASR Hotword ON;
- ITN ON.

The same speech content must be complete in both Hotword modes. At least two additional longer recordings are required to guard against sample-specific tuning.

If Hotword ON still loses content, Stage 16B remains blocked and the work stays in P1/P2. Do not start the true CTC timeline implementation.

## Deferred P3–P5 boundary

Only after explicit P2 real-device PASS:

- stop synthetic FunASR timestamps from entering canonical token timing / speaker alignment;
- run the Android CTC portability gate;
- only if that gate passes, build the real `ctc_logits -> tokenizer -> forced_align/Viterbi -> canonical sampleIndex` path;
- model revision/package changes, if required, are handled later and remain Draft until explicitly authorized.

## Non-changes

This remediation does **not**:

- merge Android PR #35;
- merge model-channel PR #21;
- promote model-channel v9;
- change production model roles;
- delete historical Qwen3 compatibility/runtime provenance;
- change Room v14;
- change realtime Silero VAD;
- Final Freeze/Handoff Stage 16B;
- start Stage 16C.
