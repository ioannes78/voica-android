# Stage 16B Gate B — B4 Timestamp / Timeline Compatibility

Status: **AUTOMATED PASS / REAL-DEVICE PASS — SAFE PARTIAL TOKEN ALIGNMENT**

Date: 2026-10-20

## Objective

B4 determines whether each viable offline ASR candidate can participate safely in Voica's canonical timeline without conflating four different concepts:

1. canonical segment timeline ownership;
2. native token timing;
3. compatibility with an additional timing-reference / alignment pass;
4. the current product integration path.

B4 does not rank generic ASR accuracy or performance. B5 owns isolated performance/memory ranking.

## Frozen fairness contract

- exact active `fsmn-vad-int8@1.0.0/1` is mandatory;
- Silero fallback invalidates formal B4 evidence;
- FSMN runs exactly once on canonical PCM;
- canonical `sampleIndex` speech boundaries are frozen once;
- frozen inputs use the common verified **25-second** maximum from B2;
- the same frozen boundaries are reused across all B4 routes;
- no benchmark output is persisted to Room;
- canonical `sampleIndex` remains the authoritative coordinate;
- B4 does not change the production model matrix.

## Current product reality

### Canonical segment timeline

`TranscriptSegment.startSampleIndex/endSampleIndexExclusive` remains the durable timeline coordinate. `TranscriptTimeline` builds each transcript row from those segment boundaries even if a row has no token-level cues. When token timing is unavailable or safely refused, playback can still map to the transcript row; only word/character-level cue precision is absent.

This is intentionally different from claiming every displayed character has a precise timestamp.

### SenseVoice

`SherpaSenseVoiceEngine` exposes native token timing and maps sherpa timestamps into relative sample offsets. The high-quality pipeline maps those tokens directly to absolute canonical `sampleIndex` values.

Because native timing is an advertised/current capability, SenseVoice `NATIVE_ONLY` is strict: native timed tokens must exist for every nonblank segment and their coordinates must be valid.

### Qwen3-ASR 0.6B

The current large-offline adapter does not provide native token timing for Qwen3. The current product coordinator can attach the active `zipformer-small-bilingual` streaming ASR as a timing reference and uses the production timeline-alignment algorithm to project final Qwen text onto canonical timing.

The alignment algorithm intentionally refuses token-level projection when final/reference text similarity falls below its safety threshold. It must not invent a precise token timeline for low-similarity text. Such a segment retains its canonical segment timeline but has no token cues.

### Fun-ASR-Nano

The current candidate adapter does not provide native token timing. There is no FunASR production timeline-alignment route today. B4 therefore runs a **candidate compatibility probe** using the same Small Bilingual reference/alignment mechanism. A successful probe means the candidate can be made timeline-compatible; it does not mean the product route has already been promoted or enabled.

## B4 routes

1. FunASR — `NATIVE_ONLY`;
2. FunASR — `FUNASR_ALIGNMENT_PROBE`;
3. SenseVoice — `NATIVE_ONLY`;
4. Qwen3 — `NATIVE_ONLY`;
5. Qwen3 — `QWEN_CURRENT_PRODUCT_ALIGNMENT`.

The Small Bilingual timing reference model is required for the two aligned routes.

## Corrected structural pass criteria

B4 has two layers and they must not be collapsed.

### Layer 1 — canonical segment timeline

All routes require:

- transcript segment boundaries remain the frozen canonical FSMN boundaries;
- no boundary is replaced by model-local seconds or another coordinate;
- segment timeline ordering/bounds remain valid.

A route with no token timing can still be timeline-compatible at this layer.

### Layer 2 — token timing

For any emitted token timing:

- every emitted token has a canonical start `sampleIndex`;
- token starts are monotonic within each segment;
- token starts and optional ends stay inside the segment's canonical `[startSampleIndex, endSampleIndexExclusive]` bounds.

SenseVoice native timing requires **full timed coverage of all nonblank segments**.

For aligned FunASR/Qwen3 routes:

- `TIMELINE_ALIGNMENT` must actually execute;
- no pipeline-level timeline fallback error may occur;
- at least some aligned token timing must be produced;
- every emitted token coordinate must be structurally valid;
- low-similarity segments may intentionally have no token cues. This is `PARTIAL_SAFE_REFUSAL`, not a failed canonical segment timeline.

The previous QA-only B4 harness incorrectly required every nonblank aligned segment to have token timing and therefore labeled a safe-refusal result as `INCOMPLETE`. That QA criterion was corrected; the production alignment algorithm was not weakened or changed.

## Controlled real-device script

Reference:

`第一段现在开始。第二段用于时间轴测试。第三段包含 OpenAI 和 Qwen3，最后结束。`

Record naturally in about 10–20 seconds. Pause roughly 0.5–1 second between the three clauses. The structural pass does not assume an exact FSMN segment count.

## Automated checkpoints

Initial B4 QA checkpoint:

`76b53232ae37c4a9252706a8346d5338e7af39a4`

- Android PR CI #2025 Fast PR Gate: **PASS**;
- Android PR CI #2026 production-like QA APK Gate: **PASS**;
- artifact ID: `12277747780`;
- artifact digest: `sha256:f24b2aaf8c6bcc6cc96b143dee71832c915ddcf07a5ac6fbc99248dcc8b465cc`;
- extracted APK SHA-256: `38a2c1f4a0ec0ba8b1950519c6dad4f95cb3e903204d29545ce71be94f89f56f`.

Post-real-device QA-interpretation correction checkpoint:

`9e62acbfb179eb09fa5227d640ef0261765ce4c0`

- Android PR CI #2031 Fast PR Gate: **PASS**;
- unit tests + Debug compile: **PASS**;
- canonical segment-boundary drift regression: **PASS**;
- emitted-token monotonic / in-bounds regression: **PASS**;
- partial aligned timing safe-refusal regression: **PASS**;
- SenseVoice strict native-timing regression: **PASS**;
- FSMN runtime boundary: **PASS**.

No second QA APK is required because the correction changes only QA interpretation/diagnostics; production `TimelineAlignment`, `HighQualityTranscriptionPipeline`, model selection and persistence behavior are unchanged.

## Real-device evidence

Recording:

- recording: `9a364bc2cd72b731fa8c0bc7ce2f63ec3d6dc416bd74ec6edc852a030b8c0670`;
- canonical SHA-256: `21e58ba02c582b1c636d325063645e250768f486330b4952fb3720160098c140`;
- VAD: `fsmn-vad-int8@1.0.0/1`;
- VAD wall: `254 ms`;
- original speech segments: `5`;
- frozen ASR inputs: `5`;
- max ASR input: `25 s`;
- timing reference: `zipformer-small-bilingual@2023-02-16/1`.

### FunASR — native only

- pipeline wall: `30298 ms`;
- nonblank segments: `5/5`;
- tokens: `0`;
- native timing: absent, as expected;
- monotonic violations: `0`;
- out-of-bounds tokens: `0`.

Decision: **PASS — canonical segment timeline / no native token timing**.

### FunASR — Small-Bilingual alignment compatibility probe

- pipeline wall: `18158 ms`;
- alignment activity: `true`;
- alignment callback span: `1812 ms`;
- timeline fallback error: none;
- nonblank segments: `5/5`;
- timed tokens: `38/38`;
- timed segment coverage: `3/5`;
- monotonic violations: `0`;
- out-of-bounds tokens: `0`.

Decision: **PASS — `PARTIAL_SAFE_REFUSAL`**. Three segments receive valid canonical token timing. Two low-similarity segments intentionally receive no token cues rather than fabricated precision. FunASR is technically compatible with the existing alignment mechanism, but this remains a candidate probe and is not a production route.

### SenseVoice — native only

- pipeline wall: `3747 ms`;
- transcript segments: `5`;
- nonblank segments: `4`;
- native timed tokens: `34/34`;
- timed segment coverage: `4/4` nonblank segments;
- monotonic violations: `0`;
- out-of-bounds tokens: `0`;
- native timing available: `true`.

Decision: **PASS — FULL native token timing**. SenseVoice does not require an additional timing-reference model for this recording.

### Qwen3 — native only

- pipeline wall: `17441 ms`;
- nonblank segments: `5/5`;
- tokens: `0`;
- native timing: absent, as expected;
- monotonic violations: `0`;
- out-of-bounds tokens: `0`.

Decision: **PASS — canonical segment timeline / no native token timing**.

### Qwen3 — current product alignment route

- pipeline wall: `19916 ms`;
- alignment activity: `true`;
- alignment callback span: `1889 ms`;
- timeline fallback error: none;
- nonblank segments: `5/5`;
- timed tokens: `33/33`;
- timed segment coverage: `3/5`;
- monotonic violations: `0`;
- out-of-bounds tokens: `0`.

Decision: **PASS — current product route with `PARTIAL_SAFE_REFUSAL`**. The two unaligned segments retain canonical segment-level timing while token-level projection is intentionally withheld because reference/final text similarity is insufficient.

## B4 decision

**REAL-DEVICE PASS.**

Capability matrix from B4:

| Candidate | Native token timing | Extra alignment required for token cues | Real-device token result | Canonical segment timeline |
| --- | --- | --- | --- | --- |
| SenseVoice | Yes | No | FULL `4/4` nonblank segments | PASS |
| FunASR Nano | No | Yes, candidate probe | PARTIAL `3/5`, safe refusal on 2 | PASS |
| Qwen3-ASR | No | Yes, current product path | PARTIAL `3/5`, safe refusal on 2 | PASS |

B4 does **not** justify inventing token timestamps for failed alignments. Segment-level canonical timing and token-level timing precision remain separate product concepts.

## Frozen non-changes

B4 does not:

- merge Android PR #35;
- merge model-channel PR #21;
- modify `main`;
- promote FunASR;
- remove SenseVoice or Qwen3;
- change Room v14;
- change Local File `FSMN -> Silero` policy;
- change realtime Silero;
- change canonical `sampleIndex` ownership;
- start Stage 16C.

## Next

Use B1–B4 evidence to define the reduced **B5 isolated cold/warm performance + memory matrix**. B5 must use isolated candidate runs rather than sequential same-process PSS so model-memory carryover cannot contaminate the comparison. B6 long-file testing should be limited to finalists/roles that remain materially competitive after B5.
