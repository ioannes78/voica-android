# Stage 16 Streaming ASR Contract V2

Status: **frozen by Stage 13A; Stage 16 implementation must consume this contract without creating an incompatible parallel API.**

## 1. Scope

This document freezes the local streaming-ASR boundary that Stage 16 will use. It does **not** implement Stage 16 live microphone/BLE audio capture, live stabilizer, UI rendering, or live punctuation.

The contract remains based on:

- canonical PCM: 16 kHz / mono / PCM16;
- `StreamingAsrEngine`;
- `StreamingAsrSession`;
- `AsrHypothesis`;
- `RelativeTimedToken`;
- `AsrHypothesisStability`.

Offline-only models such as SenseVoice, FireRedASR2 CTC and Qwen3-ASR must not be presented as true-streaming engines.

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

## 5. Capability contract

A model is eligible for the Stage 16 true-streaming path only when its descriptor/runtime combination truthfully declares and proves:

- `executionMode = TRUE_STREAMING`;
- `supportsStreaming = true`;
- partial-result behavior compatible with `StreamingAsrSession`.

`supportsTokenTiming`, language forcing, hotwords, punctuation and other controls remain capability-driven. UI must not expose a control that is not supported by the selected model/runtime.

Stage 13A retained real-time candidates:

- Small Bilingual Zipformer zh-en;
- Chinese Large Transducer INT8;
- Chinese Large CTC INT8.

The eventual Stage 16 default must follow Stage 13A real-device benchmark/acceptance results rather than model-name hardcoding.

## 6. Punctuation contract

Stage 16 will use the existing two-layer punctuation principle:

- PARTIAL tail: no unstable punctuation churn;
- STABLE clause/segment: punctuation may be added only after the text range is stable;
- FINAL: run the final punctuation/finalization path before persistence.

Native reliable punctuation must not be followed by duplicate external punctuation.

## 7. Reset, cancellation and discontinuity

A discontinuity that invalidates the current utterance boundary must result in a deterministic finalization/reset/cancel decision. Stage 16 must not silently continue one session-relative token timeline across unrelated audio epochs.

Cancellation must close native resources and must not persist a fake FINAL result.

## 8. Frozen compatibility rules

Stage 16 must not:

- introduce a second incompatible streaming-ASR session API;
- call offline Qwen/FireRed/SenseVoice “streaming” merely by chunking audio;
- replace canonical sample timing with wall clock;
- fabricate token timing;
- persist PARTIAL/STABLE text as if it were FINAL;
- bypass Model Manager capability/lineage rules.

Any future contract change requires an explicit versioned successor to this document and regression tests.
