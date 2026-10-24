# Stage 17 P0 — Runtime / Model Feasibility Gate Closure

Status: **PASS WITH RESTRICTIONS**

Branch: `stage17-fully-offline-intelligence`

Validated implementation before closure doc: `0512c8d59f6c2e8c303066aba9417c0cd44b602d`

## Scope

P0 evaluated whether WENZHI can run a fully local LLM on the target Android device without disturbing the existing offline recording / BLE / playback / ASR stack.

The evaluated runtime is LiteRT-LM Android `0.18.0` with Gemma 4 E2B. P0 is a feasibility gate only; it does not freeze a production model catalog or start Stage 17 P1 implementation.

## Root-cause investigation

### R4 / R5

The Gemma 4 E2B May-4 artifact repeatedly passed `Engine.initialize()` on both CPU and GPU, then terminated the process with native abort / `CRASH_NATIVE` during `CONVERSATION_CREATING`.

R5 aligned speculative-decoding inheritance more closely with Google AI Edge Gallery. The failure remained unchanged.

### R6

The model artifact was changed to the earlier pre-MTP Gallery artifact while LiteRT-LM, context size, sampler and conversation configuration were kept fixed.

The same native abort at `CONVERSATION_CREATING` remained. This rejected the model-artifact revision as the primary cause of the crash.

### R7

Google AI Edge Gallery 1.0.20 uses LiteRT-LM `0.18.0` and Gemma 4 E2B defaults compatible with the WENZHI probe, but Gallery's release build has minification disabled. WENZHI `releaseQa` / production-like builds enable R8 minification and resource shrinking.

Inspection of the R6 APK showed that most high-level `com.google.ai.edge.litertlm` API classes had been renamed/optimized by R8.

R7 kept global production minification enabled and added the narrow JNI/ABI boundary rule:

```proguard
-keep,allowoptimization class com.google.ai.edge.litertlm.** {
    *;
}
```

The resulting production-like QA APK retained the LiteRT-LM API/JNI class names, including `Engine`, `Conversation`, `ConversationConfig`, `Content`, `Contents`, `ExperimentalFlags`, and `LiteRtLmJni`.

With this single-variable change, the same device crossed `CONVERSATION_CREATING` and completed CPU and GPU inference repeatedly. P0 therefore accepts R8/LiteRT-LM JNI ABI preservation as the root-cause fix for the native crash.

## Validated model/runtime configuration

- LiteRT-LM Android: `0.18.0`
- Model: `litert-community/gemma-4-E2B-it-litert-lm`
- Validated artifact revision: `7fa1d78473894f7e736a21d920c3aa80f950c0db`
- File: `gemma-4-E2B-it.litertlm`
- Size: `2,583,085,056` bytes
- SHA-256: `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
- `maxNumTokens=4000`
- `cacheDir=null`
- `topK=64`
- `topP=0.95`
- `temperature=1.0`
- speculative decoding OFF for the probe

The newer May-4 / MTP-capable artifact was tested before the R8 fix and therefore is **not** classified as passed or failed under R7. It must not be treated as production-approved from P0 evidence alone.

## Real-device evidence

### CPU feasibility

Successful R7 CPU probe:

- initialize: `1026 ms`
- TTFE: `3349 ms`
- generation: `4104 ms`
- PSS before: `79,168 KB`
- PSS after initialize: `483,509 KB`
- PSS after generation: `1,371,689 KB`
- PSS after close: `388,161 KB`
- peak PSS: `1,371,689 KB`
- response completed successfully in Chinese

CPU repeat-load gate: **PASS** — 3 consecutive complete probes.

### GPU feasibility

Successful R7 GPU probe:

- initialize: `6181 ms`
- TTFE: `3034 ms`
- generation: `4970 ms`
- PSS before: `91,564 KB`
- PSS after initialize: `1,975,845 KB`
- PSS after generation: `2,064,142 KB`
- PSS after close: `1,873,349 KB`
- peak PSS: `2,095,841 KB`

Third consecutive GPU run:

- initialize: `6379 ms`
- TTFE: `2384 ms`
- generation: `4255 ms`
- PSS before: `139,702 KB`
- PSS after initialize: `2,052,436 KB`
- PSS after generation: `2,162,579 KB`
- PSS after close: `1,426,535 KB`
- peak PSS: `2,162,900 KB`

The next-run starting PSS returning to roughly 140 MB after a prior high post-close reading indicates delayed runtime/driver reclamation rather than monotonic per-run growth in the observed test sequence.

GPU repeat-load gate: **PASS** — 3 consecutive complete probes.

### Cancellation

GPU inference cancellation: **PASS** — cancellation returned the app to a stable operable state without restart, crash or permanent running state.

### Lifecycle

CPU probe background / foreground transition: **PASS** — no process restart, permanent stuck state or unusable recovery.

### Thermal / sustained load

Five consecutive GPU probes under device warming: **PASS** — no crash, automatic restart, permanent stall or progressive inability to launch the next probe was observed.

## Gate decision

**P0 = PASS WITH RESTRICTIONS**

The following are proven feasible on the tested 8 GB Android device:

1. LiteRT-LM `0.18.0` local inference inside WENZHI.
2. CPU and GPU Engine + Conversation creation + generation.
3. Repeated Engine / Conversation load and release.
4. Cancellation.
5. Background / foreground lifecycle recovery.
6. Sustained GPU load over five consecutive probes.
7. Production-like R8 builds, provided the LiteRT-LM JNI/API package is preserved from renaming/removal.

## Restrictions carried into P1

1. Keep the LiteRT-LM R8/JNI preservation rule as a hard release contract; add automated APK/R8 verification before Stage 17 finalization.
2. Treat the pre-MTP artifact above as the only Gemma 4 E2B artifact proven by P0 after the R8 fix.
3. Do not claim the newer MTP artifact is validated until it is explicitly retested under the fixed R8 boundary.
4. GPU memory has a materially higher working set than CPU; capability/resource gating remains mandatory.
5. P1 must keep local LLM work isolated from recording, BLE, playback and ASR critical paths.
6. No cloud fallback is permitted.
7. P0 does not authorize merging PR #37; merge still requires separate explicit user approval.

## Stage transition

P0 is closed. Stage 17 P1 — Offline LLM Runtime Foundation — is now **UNBLOCKED**, subject to the restrictions above and the project's normal requirement/plan confirmation workflow before implementation.
