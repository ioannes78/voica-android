# Stage 16 Streaming ASR Contract V2

Status: **FROZEN by Stage 13A on 2026-10-05 after real-device acceptance.**

## 1. Scope

This document freezes the local streaming-ASR boundary that Stage 16 must use unless a later explicit versioned contract supersedes it. It does **not** implement Stage 16 live microphone/BLE audio capture, live stabilizer, UI rendering, or live punctuation.

The contract remains based on:

- canonical PCM: 16 kHz / mono / PCM16;
- `StreamingAsrEngine`;
- `StreamingAsrSession`;
- `AsrHypothesis`;
- `RelativeTimedToken`;
- `AsrHypothesisStability`.

Offline-only models such as SenseVoice and Qwen3-ASR must not be presented as true-streaming engines. FireRedASR2 was evaluated during Stage 13A and was removed from the frozen product matrix.

## 2. Session lifecycle

A streaming session follows this lifecycle:

1. `openSession()`
2. zero or more cycles of `acceptSamples(...)` then `decode()` returning a non-final hypothesis
3. `finishInput()` returning a final hypothesis
4. either `reset()` to start a new utterance or `close()`

After `finishInput()`:

- `acceptSamples()` is invalid until `reset()`;
- `decode()` is invalid until `reset()`;
- repeated `finishInput()` may return the current FINAL snapshot and must not reopen the utterance.

`reset()` resets the session-relative sample timeline to zero.

## 3. Event semantics

`AsrHypothesisStability` is frozen as:

- `PARTIAL`: mutable/interim text, `isFinal=false`;
- `STABLE`: non-final text judged stable by a model-native mechanism or the Stage 16 stabilizer, `isFinal=false`;
- `FINAL`: utterance-complete result, `isFinal=true`.

The type invariant is mandatory:

- `FINAL` iff `isFinal=true`;
- `PARTIAL` and `STABLE` iff `isFinal=false`.

Current Stage 13A sherpa streaming adapters emit PARTIAL and FINAL. Stage 16 may add a stabilizer that promotes appropriate successive PARTIAL hypotheses to STABLE. The existence of the STABLE enum does **not** mean the Stage 16 stabilizer has already been implemented.

## 4. Timing contract

Token timing is session-relative in `RelativeTimedToken`.

For persisted/offline transcription, session-relative offsets are converted to the recording canonical absolute sample index.

For Stage 16 live display:

- the live pipeline must retain the accepted canonical sample position;
- stable/final text must preserve monotonic token timing when the model provides timing;
- timing must never be fabricated when the model does not provide trustworthy token timing;
- milliseconds/wall-clock are presentation values only, never the primary transcript timeline truth.

## 5. Capability contract and frozen Stage 13A realtime matrix

A model is eligible for the Stage 16 true-streaming path only when its descriptor/runtime combination truthfully declares and proves:

- `executionMode = TRUE_STREAMING`;
- `supportsStreaming = true`;
- partial-result behavior compatible with `StreamingAsrSession`.

`supportsTokenTiming`, language forcing, hotwords, punctuation and other controls remain capability-driven. UI must not expose a control that is not supported by the selected model/runtime.

The Stage 13A frozen realtime product matrix contains exactly:

- Small Bilingual Zipformer zh-en INT8 — light/default;
- Chinese Large CTC INT8 — high-quality Chinese realtime option.

Chinese Large Transducer was evaluated during Stage 13A and removed from the frozen product matrix. Stage 16 must not silently restore it or an AUTO model-routing choice. Adding a future realtime model requires an explicit product decision plus runtime/capability/real-device validation and a versioned successor to this contract when compatibility changes.

The recording-file offline matrix is separate and contains SenseVoice (fast/default) and Qwen3-ASR 0.6B INT8 (high quality). Offline-model selection must not alter the Stage 16 realtime model selection.

## 6. Punctuation contract

Stage 16 will use the existing two-layer punctuation principle:

- PARTIAL tail: no unstable punctuation churn;
- STABLE clause/segment: punctuation may be added only after the text range is stable;
- FINAL: run the final punctuation/finalization path before persistence.

Native reliable punctuation must not be followed by duplicate external punctuation.

## 7. Reset, cancellation and discontinuity

A discontinuity that invalidates the current utterance boundary must result in a deterministic finalization/reset/cancel decision. Stage 16 must not silently continue one session-relative token timeline across unrelated audio epochs.

Cancellation must close native resources and must not persist a fake FINAL result.

## 8. Compatibility rules

Stage 16 must not:

- introduce a second incompatible streaming-ASR session API;
- call offline Qwen/SenseVoice “streaming” merely by chunking audio;
- restore FireRedASR2 or Chinese Large Transducer as product choices without a new explicit validation decision;
- replace canonical sample timing with wall clock;
- fabricate token timing;
- persist PARTIAL/STABLE text as if it were FINAL;
- bypass Model Manager capability/lineage rules.

After Stage 13A Freeze, any future contract change requires an explicit versioned successor to this document and regression tests.

## 9. Freeze evidence

- Stage 13A functional/QA HEAD: `6543d9f45ed73bb3a815456968518f0d3641b774`
- Android PR CI #693 / run `37264265999`: success
- user real-device acceptance: **“测试通过”**
- formal records: `docs/STAGE_13A_FREEZE.md` and `docs/STAGE_13A_HANDOFF.md`
