# Stage 16B Gate B — B4 Real-Device Timeline Report

Status: **PASS — FULL NATIVE SENSEVOICE / PARTIAL SAFE-REFUSAL ALIGNMENT FOR FUNASR + QWEN3**

Date: 2026-10-20

## Recording identity

- recording: `9a364bc2cd72b731fa8c0bc7ce2f63ec3d6dc416bd74ec6edc852a030b8c0670`;
- canonical SHA-256: `21e58ba02c582b1c636d325063645e250768f486330b4952fb3720160098c140`;
- exact VAD: `fsmn-vad-int8@1.0.0/1`;
- VAD wall: `254 ms`;
- speech segments: `5`;
- frozen ASR inputs: `5`;
- maximum ASR input: `25 s`;
- timing reference: `zipformer-small-bilingual@2023-02-16/1`.

Reference:

`第一段现在开始。第二段用于时间轴测试。第三段包含 OpenAI 和 Qwen3，最后结束。`

## Raw route evidence

### FunASR — `NATIVE_ONLY`

- pipeline wall: `30298 ms`;
- alignment activity: `false`;
- transcript segments: `5`;
- nonblank segments: `5`;
- token count: `0`;
- timed token count: `0`;
- timed segment coverage: `0/5`;
- monotonic violations: `0`;
- out-of-bounds tokens: `0`;
- native timing available: `false`.

Decision: **PASS** for canonical segment timeline with expected absence of native token timing.

### FunASR — `FUNASR_ALIGNMENT_PROBE`

- pipeline wall: `18158 ms`;
- alignment activity: `true`;
- alignment callback span: `1812 ms`;
- timeline fallback error: none;
- transcript segments: `5`;
- nonblank segments: `5`;
- token count: `38`;
- timed token count: `38`;
- timed segment coverage: `3/5`;
- monotonic violations: `0`;
- out-of-bounds tokens: `0`.

Decision: **PASS — `PARTIAL_SAFE_REFUSAL`**. Valid canonical token timing is produced for three segments. Two low-similarity segments intentionally receive no token cues rather than fabricated precision. This proves technical compatibility with the existing Small-Bilingual alignment mechanism; it does not enable a production FunASR alignment route.

### SenseVoice — `NATIVE_ONLY`

- pipeline wall: `3747 ms`;
- transcript segments: `5`;
- nonblank segments: `4`;
- token count: `34`;
- timed token count: `34`;
- timed segment coverage: `4/4` nonblank segments;
- monotonic violations: `0`;
- out-of-bounds tokens: `0`;
- native timing available: `true`.

Decision: **PASS — FULL native token timing**.

### Qwen3 — `NATIVE_ONLY`

- pipeline wall: `17441 ms`;
- transcript segments: `5`;
- nonblank segments: `5`;
- token count: `0`;
- timed token count: `0`;
- timed segment coverage: `0/5`;
- monotonic violations: `0`;
- out-of-bounds tokens: `0`;
- native timing available: `false`.

Decision: **PASS** for canonical segment timeline with expected absence of native token timing.

### Qwen3 — `QWEN_CURRENT_PRODUCT_ALIGNMENT`

- pipeline wall: `19916 ms`;
- alignment activity: `true`;
- alignment callback span: `1889 ms`;
- timeline fallback error: none;
- transcript segments: `5`;
- nonblank segments: `5`;
- token count: `33`;
- timed token count: `33`;
- timed segment coverage: `3/5`;
- monotonic violations: `0`;
- out-of-bounds tokens: `0`.

Decision: **PASS — current product route with `PARTIAL_SAFE_REFUSAL`**. Two low-similarity segments retain canonical segment-level timing but intentionally receive no token-level cue projection.

## Why the first QA screen said `B4 INCOMPLETE`

The initial QA harness treated `timedSegmentCount == nonBlankSegmentCount` as a universal timeline requirement. That was too strict for the existing production safety design.

The production alignment code intentionally returns no token projection when final text and timing-reference text are too dissimilar. Existing regression coverage explicitly rejects inventing a precise timeline for low-similarity text. Separately, `TranscriptTimeline` builds its row from `TranscriptSegment.startSampleIndex/endSampleIndexExclusive`; a row remains active even when its token cue list is empty.

Therefore B4 must distinguish:

- **canonical segment timeline validity** — mandatory for all routes;
- **token-level timing coverage** — native/full for SenseVoice, optional precision with safe refusal for alignment routes.

The QA-only interpretation was corrected at checkpoint `9e62acbfb179eb09fa5227d640ef0261765ce4c0`. Production `TimelineAlignment`, product model selection and persistence behavior were not changed.

## Corrected automated validation

Android PR CI #2031 Fast PR Gate: **PASS**.

Validated regressions include:

- canonical segment-boundary drift must fail;
- emitted token timestamps must remain monotonic and in canonical bounds;
- partial aligned timing with safe refusal may pass when canonical segment timing remains valid;
- SenseVoice native route still requires full native timed coverage;
- exact FSMN runtime boundary remains intact.

The corrected QA harness exposes explicit `segmentBoundaryMismatches`, `canonicalSegmentTimelineValid`, `tokenTimingStatus`, `emittedTokenCoordinatesValid` and `fullTimedCoverage` fields for any future rerun. A rerun is not required for the B4 decision because no production runtime behavior changed and the original real-device run already supplied the raw token/alignment evidence used here.

## Final B4 matrix

| Candidate | Native token timing | Extra alignment for token cues | Real-device timing result | Canonical segment timeline |
| --- | --- | --- | --- | --- |
| SenseVoice | Yes | No | FULL `4/4` nonblank segments | PASS |
| FunASR Nano | No | Candidate probe | PARTIAL `3/5`, safe refusal on 2 | PASS |
| Qwen3-ASR | No | Current product path | PARTIAL `3/5`, safe refusal on 2 | PASS |

## Final decision

**B4 REAL-DEVICE PASS.**

No production model is promoted or removed by B4 alone. FunASR remains candidate-only. Qwen3 retains its existing Small-Bilingual alignment path. SenseVoice retains its native timing advantage.

Next: **B5 isolated cold/warm performance + memory**.