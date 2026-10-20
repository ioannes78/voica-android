# Stage 16B Gate B — B4 Timestamp / Timeline Compatibility

Status: **IMPLEMENTED / AUTOMATED CI PENDING / REAL-DEVICE NEXT**

Date: 2026-10-20

## Objective

B4 determines whether each viable offline ASR candidate can participate safely in Voica's canonical timeline without conflating three different capabilities:

1. native token timing;
2. compatibility with an additional timing-reference / alignment pass;
3. the current product integration path.

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

### SenseVoice

`SherpaSenseVoiceEngine` exposes native token timing and maps sherpa timestamps into relative sample offsets. The high-quality pipeline maps those tokens directly to absolute canonical `sampleIndex` values.

### Qwen3-ASR 0.6B

The current large-offline adapter does not provide native token timing for Qwen3. The current product coordinator can attach the active `zipformer-small-bilingual` streaming ASR as a timing reference and uses the production timeline-alignment algorithm to project final Qwen text onto canonical timing.

### Fun-ASR-Nano

The current candidate adapter does not provide native token timing. There is no FunASR production timeline-alignment route today. B4 therefore runs a **candidate compatibility probe** using the same Small Bilingual reference/alignment mechanism. A successful probe means the candidate can be made timeline-compatible; it does not mean the product route has already been promoted or enabled.

## B4 routes

1. FunASR — `NATIVE_ONLY`;
2. FunASR — `FUNASR_ALIGNMENT_PROBE`;
3. SenseVoice — `NATIVE_ONLY`;
4. Qwen3 — `NATIVE_ONLY`;
5. Qwen3 — `QWEN_CURRENT_PRODUCT_ALIGNMENT`.

The Small Bilingual timing reference model is required for the two aligned routes.

## Structural pass criteria

For a route that emits timeline tokens:

- at least one nonblank transcript segment exists;
- every emitted token has a canonical start `sampleIndex`;
- every nonblank transcript segment has timed-token coverage;
- token starts are monotonic within each segment;
- token starts and optional ends stay inside the segment's canonical `[startSampleIndex, endSampleIndexExclusive]` bounds;
- the aligned routes actually emit `TIMELINE_ALIGNMENT` progress activity;
- the production alignment path reports no timeline fallback error.

For `NATIVE_ONLY`, zero tokens are valid evidence that native timing is absent. If native tokens are present, they must satisfy the same structural checks.

## Controlled real-device script

Reference:

`第一段现在开始。第二段用于时间轴测试。第三段包含 OpenAI 和 Qwen3，最后结束。`

Record naturally in about 10–20 seconds. Pause roughly 0.5–1 second between the three clauses. The pauses are intended to give the canonical/VAD timeline observable boundaries; the structural pass does not assume an exact number of FSMN segments.

## Evidence fields

The QA launcher reports:

- canonical SHA-256;
- exact FSMN identity and wall time;
- original FSMN speech-segment count;
- frozen 25-second ASR-input count;
- timing-reference model identity;
- candidate/route identity;
- whether the route is the current product path;
- total pipeline wall time (signal only);
- whether timeline-alignment activity ran;
- approximate alignment callback-span wall time (signal only);
- timeline fallback error;
- transcript/nonblank segment counts;
- total and timed token counts;
- timed-segment coverage;
- monotonic violations;
- out-of-bounds token count;
- structural timeline validity;
- native timing availability;
- raw second-pass text.

## Interpretation rules

- SenseVoice native timing PASS means no extra timing-reference ASR is required for canonical timeline tokens.
- Qwen native-only absence is expected; the product-alignment route must pass to preserve current timeline behavior.
- FunASR native-only absence is expected; a successful alignment probe demonstrates technical timeline compatibility but still requires an explicit product-integration decision before promotion.
- alignment wall/pipeline timings are not final performance rankings;
- no route may replace canonical `sampleIndex` with seconds or model-local coordinates.

## QA-only boundary

`Stage16BGateB4TimelineActivity` is exposed only in `qa` / `releaseQa` manifests. Production release remains unchanged.

## Next

1. Fast PR Gate must pass compile/unit/FSMN-boundary checks.
2. Generate one production-like `releaseQa` APK.
3. Run one controlled B4 real-device recording and inspect all five routes.
4. Use B2/B3/B4 evidence to decide which roles/candidates proceed to isolated B5 performance/memory testing.
