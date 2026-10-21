# Stage 16B — FunASR Nano CTC Portability Gate

Status: **SOURCE GATE PARTIAL PASS / ANDROID TOKENIZER + VITERBI CORE CI PASS / RUNTIME A0+A1 COMPILE/ABI PASS / CURRENT REVISION 1 INSUFFICIENT / A2 MODEL EXPORT + PARITY REQUIRED / FINAL FREEZE BLOCKED**

Date: 2026-10-21

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

## Gate purpose

P2 has passed the user real-device completeness check: the post-gate QA build no longer showed the obvious Hotword-ON missing span or other obvious omissions. The remaining product issues are the expected ones for the pre-CTC route: sentence segmentation is still poor and the visible timeline remains coarse segment-level rather than character/token-level.

This gate determines whether the official FunASR Nano CTC forced-alignment path can be brought to Android without reintroducing fake precision or pushing the high-quality path beyond a practical mobile performance/memory envelope.

## P3 prerequisite — synthetic timing firewall

Pinned sherpa-onnx v1.13.8 FunASR generated-token timestamps are synthetic/evenly distributed timing, not acoustic forced alignment.

P3 therefore makes the FunASR product wrapper fail closed:

- private sherpa token/timestamp output is retained only for P1 context-budget diagnostics;
- the product wrapper advertises `supportsTokenTiming=false` and `TimestampCapability.NONE`;
- product `AsrHypothesis.tokens` is empty for FunASR even for a single chunk with no retry;
- High Quality transcription consequently falls back to the already-trusted FSMN/VAD segment boundary instead of persisting pseudo-precise token timing;
- Qwen historical compatibility and SenseVoice timing behavior are unchanged.

The timing-firewall regression test and existing debug/FSMN regression suite passed Fast PR Gate #2132 at Android head `11642818f670788afecb636f1914cdf7c6363559`.

## Official FunASR CTC reference path

Current official FunASR Nano source establishes a separate CTC branch from the audio encoder:

1. `encoder_out, encoder_out_lens = self.encode(...)`;
2. LLM path separately runs `audio_adaptor(encoder_out, ...)`;
3. CTC path runs `ctc_decoder(encoder_out, encoder_out_lens)`;
4. CTC projection produces `ctc_logits` / log probabilities;
5. the final LLM text is encoded with the **SenseVoice CTC tokenizer**;
6. `forced_align(ctc_logits, target_ids, blank_id)` aligns the final text;
7. returned CTC frame indexes are converted at **60 ms per frame** (`6 * 10 ms`);
8. sentence punctuation with no acoustic realization is anchored to the preceding spoken token where applicable.

The current official model configuration identifies:

- audio encoder output dimension: `512`;
- audio adaptor input `512`, LLM output dimension `1024`, 2 adaptor layers;
- CTC decoder input/output working dimension `512`, 5 decoder layers;
- audio encoder downsample rate: `6` over the 10 ms frontend frame shift;
- CTC tokenizer: `SenseVoiceTokenizer` using `multilingual.tiktoken`;
- default CTC vocabulary size in the current implementation: `60515`;
- default blank id therefore resolves to `60514` unless an exported artifact explicitly overrides it.

**Exported model metadata remains authoritative. The Android package must record and verify the actual vocab size and blank id rather than hard-code the default assumption.**

### Official checkpoint provenance to freeze for A2

The official `FunAudioLLM/Fun-ASR-Nano-2512` repository currently resolves the source checkpoint at revision:

`272c57b82523ada6fd87095e955f8e29100979ab`

Official provenance for the later first-party vLLM-native conversion independently records the same source revision and source `model.pt` SHA-256:

`55ae0d2fee369f0f11cce0795f6927934ad17cf11b278a7e56a51272074160bb`

That source model contains 1,261 checkpoint tensors according to the same first-party conversion provenance. The `multilingual.tiktoken` file originated in the earlier checkpoint update and remains present at the pinned revision.

A2 must verify these facts from the downloaded source itself and compute/record the tokenizer and every exported ONNX digest; external provenance is a pinning aid, not a substitute for Voica's own artifact verification.

## Current revision-1 package audit

Current production identity remains:

`funasr-nano-int8@2025-12-30/1`

The pinned sherpa package contains the LLM recognition route:

- `encoder_adaptor.int8.onnx` (~227 MB);
- `embedding.int8.onnx` (~149 MB);
- `llm.int8.onnx` (~573 MB);
- Qwen3-0.6B tokenizer resources (~16 MB);
- test audio / README material.

It does **not** contain a CTC decoder/projection model or the SenseVoice CTC tokenizer required by the official forced-alignment path.

More importantly, sherpa's current `encoder_adaptor` session exposes the fused encoder/adaptor result used as LLM audio embeddings. The official CTC branch consumes the **pre-adaptor 512-dimensional `encoder_out`**. The existing Android/Kotlin API has no supported way to obtain that tensor from the revision-1 runtime.

### Gate finding

**Revision 1 cannot produce a correct official CTC timeline by wiring extra Kotlin logic around its existing public output.**

Feeding the current post-adaptor LLM embedding into the official CTC decoder would be architecturally wrong. Using the Qwen tokenizer as the CTC tokenizer would also be wrong.

## Android forced-alignment core — IMPLEMENTED / CI PASS

A model-independent Android CTC Viterbi core now exists in `FunAsrCtcForcedAligner.kt`.

It deliberately accepts a compact score surface rather than the full CTC vocabulary tensor:

- per-frame blank log probability;
- per-frame log probability for each target-token position;
- exact target token ids.

The state graph is the normal CTC expansion `[blank, y0, blank, y1, ...]`. Adjacent equal targets cannot skip the intervening blank state. Structurally impossible, non-finite or unreachable paths return refusal rather than an interpolated timestamp.

The core also maps aligned frames to canonical PCM sample offsets using the official 60 ms CTC frame period.

Automated tests cover:

- ordinary multi-target alignment;
- repeated target ids requiring a blank transition;
- insufficient frame refusal;
- no-finite-path refusal;
- 60 ms frame -> 16 kHz canonical sample conversion;
- empty target handling.

Android head `2be12664b6586b895947487ea7140af478c0a5f8`: Fast PR Gate #2134 **SUCCESS**.

This validates the dynamic-programming side of Android portability. It does not claim that revision `/1` has CTC scores.

## SenseVoice CTC tokenizer core — IMPLEMENTED / CI PASS

Official FunASR re-encodes the final LLM text with `SenseVoiceTokenizer` before forced alignment. For ordinary text, this path uses the mergeable ranks from `multilingual.tiktoken` with the SenseVoice/tiktoken Unicode split pattern and byte-pair merge rules.

Android now has a minimal `SenseVoiceCtcTokenizer` that:

- loads the model-owned `.tiktoken` base64-byte/rank resource;
- uses the official ordinary-text Unicode split pattern (`letters`, `numbers`, punctuation, whitespace and English contraction cases);
- performs rank-priority byte BPE;
- encodes final text to CTC target ids;
- decodes token sequences from raw byte pieces so split UTF-8 characters round-trip correctly;
- does not embed or guess the production vocabulary and is not activated for revision `/1`.

Tests reproduce the official class of byte-fragment behavior in which one CJK character spans several token ids, plus BPE rank priority and mixed text/space/punctuation round-trip.

Android head `bbfe68299b44a2ea9249469896646ce895993dd1`: Fast PR Gate #2136 **SUCCESS**.

**Actual `/2` acceptance still requires parity against the exact checkpoint `multilingual.tiktoken` digest and representative official target-id fixtures. The deterministic JVM fixture proves the algorithm contract, not the final model artifact.**

## Existing APK / ONNX Runtime packaging finding

The current release-like Voica APK already contains the sherpa native JNI library and sherpa's shared `libonnxruntime.so`. The `engine:sherpa` Gradle module depends on the sherpa-onnx AAR and does not expose the public `ai.onnxruntime` Java API to app code.

This changes the preferred `/2` runtime design:

- **do not blindly add a second ONNX Runtime Android AAR** merely to run the CTC branch;
- doing so risks duplicate native libraries, version skew, larger APK/model runtime footprint and unclear allocator/thread-pool behavior;
- keep one compatible ORT native runtime boundary wherever possible.

The pinned sherpa v1.13.8 line is built around ONNX Runtime 1.28.2. A custom CTC runtime must therefore either share that exact native dependency boundary or deliberately replace the sherpa AAR with a controlled compatible build.

## Native runtime A0 — optional config / arm64 rebuild — PASS

A dedicated isolated workflow now proves that the required sherpa extension can be rebuilt on the exact production runtime line without replacing the app dependency:

- source: sherpa-onnx `v1.13.8`;
- Android ABI: `arm64-v8a` only;
- ONNX Runtime: `1.28.2`;
- NDK: `28.2.13676358`;
- configuration extension: optional `ctcCompact` path;
- native JNI compile: PASS;
- arm64 AAR assemble: PASS;
- Kotlin getter/setter + JNI marker + `libonnxruntime.so` linkage verification: PASS;
- patched AAR upload/production replacement: **not performed**.

Checkpoint `d7040f4a47881afe51102510686ddb19be589930`: FunASR CTC Runtime Gate #8 **SUCCESS**; Android PR CI #2154 **SUCCESS**.

## Native runtime A1 — one encoder + retained CTC state — COMPILE/ABI PASS

A1 extends the isolated patch only far enough to prove the intended shared-encoder lifecycle before any CTC scorer is loaded.

The A1 native shape is:

1. when future `ctcCompact` is configured, run the encoder session once and require exactly three named outputs: `encoder_out`, `ctc_encoder_out`, `ctc_encoder_out_lens`;
2. validate the retained CTC state as `[1, T, 512]` plus exact int64 length `[1]`, with `0 < length <= T`;
3. pass `encoder_out` into the existing LLM generation path;
4. retain `ctc_encoder_out` + `ctc_encoder_out_lens` for that exact `OfflineStream`;
5. protect retained state with a dedicated mutex, bounded state count and monotonic state id so recycled stream pointers cannot consume stale tensors;
6. allow internal `align` / `discard` commands to consume or erase retained state without executing encoder or LLM again;
7. return no placeholder alignment in A1 — no synthetic timing is allowed to escape while the scorer is absent.

Pinned sherpa v1.13.8 already exposes generic per-stream `SetOption/GetOption`, so A1 can use that existing ABI as the internal command/state-id channel rather than adding a second stream handle interface.

Checkpoint `ccfd80d7acbf99bb5c747794da93ae743c7a46a0`:

- FunASR CTC Runtime Gate #11: **SUCCESS**;
- A0 patch: PASS;
- A1 patch: PASS;
- Android arm64 JNI build against ORT 1.28.2: PASS;
- patched arm64 AAR package: PASS;
- AAR ABI / A1 state-boundary verification: PASS;
- Android PR CI #2157: **SUCCESS**.

**A1 evidence boundary:** this is a compile/ABI architecture PASS, not real CTC model execution. No revision-2 dual-output encoder has yet run through `ForwardEncoderAdaptorWithCtc`, so output values, length parity, state lifetime under actual inference and LLM/CTC numerical parity remain A2/A3 empirical requirements.

## Encoder reuse requirement and preferred native architecture

The B6 FunASR baseline leaves limited performance headroom before the important `total RTF < 1.0` target:

- ASR RTF: `0.6323`;
- total RTF: `0.6533`;
- peak PSS: `1,728,929 KB`.

A second complete audio-encoder pass for CTC would duplicate one of the expensive front-end computations and is therefore a fallback, not the target architecture.

The upstream ONNX export used by sherpa currently fuses `audio_encoder + audio_adaptor` and exposes only the post-adaptor LLM embedding. A dual-output ONNX graph by itself is insufficient if sherpa consumes and discards the extra output internally.

### Preferred `/2` runtime topology

Use a minimal controlled sherpa/native extension, built against the same ORT boundary, that:

1. runs the audio encoder once;
2. preserves the pre-adaptor `512-d encoder_out`;
3. feeds that state into the existing LLM audio adaptor path;
4. feeds the same state into the CTC decoder/projection path;
5. gathers only blank + final-target log probabilities needed by forced alignment before crossing JNI where practical;
6. returns compact alignment inputs or final aligned spans to Kotlin.

A practical export can either split encoder/adaptor cleanly or expose both pre-adaptor and post-adaptor outputs, but the native runtime must actually retain the pre-adaptor output for CTC.

### Fallback topology

A separate CTC encoder pass is acceptable only as an empirical fallback if measurements prove:

- total RTF remains within the mobile target envelope;
- peak/end PSS remain acceptable;
- no thermal or completeness regression;
- package growth is justified.

It is not the default design because it duplicates encoder weights/compute.

**Gate target: one audio encoder computation, two downstream branches (LLM ASR + CTC alignment).**

## Required revision-2 candidate architecture

The preferred candidate preserves the product model id/version and changes only revision after the CTC path proves viable:

`funasr-nano-int8@2025-12-30/2`

Revision 2 should add/export all of the following as one coherent package:

1. **Reusable pre-adaptor audio encoder state**
   - exact 512-d state used by the official CTC branch;
   - one encoder computation shared by LLM ASR and CTC alignment.

2. **CTC decoder + projection/log-softmax ONNX**
   - exact graph/weights must match the official checkpoint;
   - output frame count/period must be validated against the official 60 ms mapping.

3. **SenseVoice CTC tokenizer resource**
   - exact checkpoint-compatible `multilingual.tiktoken`;
   - do not reuse the Qwen LLM tokenizer for CTC.

4. **Explicit alignment metadata**
   - CTC vocab size;
   - blank id;
   - frame shift/downsample mapping;
   - encoder/decoder tensor names and dimensions;
   - tokenizer digest;
   - source checkpoint/revision and all artifact digests.

If the LLM must also be re-exported with a larger `max_total_len`, that change should be bundled into the same `/2` candidate rather than immediately creating `/3`.

## A2 — actual checkpoint/export/parity Gate — NEXT

A2 is the first Gate allowed to turn the compile-only runtime skeleton into empirical model evidence. It must use the frozen official source revision, not an unpinned `main` download.

Required A2 output contract:

- dual-output encoder artifact exposes post-adaptor LLM `encoder_out`, pre-adaptor `ctc_encoder_out`, and exact `ctc_encoder_out_lens` using the names required by A1;
- pre-adaptor state is 512-d and the exported length matches the official Python encoder length for every fixture;
- CTC decoder/projection/log-softmax comes from the same source checkpoint and produces the expected vocabulary dimension;
- exact blank id is read from the checkpoint/export metadata and verified rather than assumed;
- `multilingual.tiktoken` is downloaded from the same pinned revision, hashed, and parity-tested against Android `SenseVoiceCtcTokenizer` target ids;
- official Python and ONNX are compared on controlled samples for encoder state/length, CTC greedy text and forced-alignment frame indexes/timestamps;
- all source/export/tokenizer artifacts receive SHA-256 digests and immutable provenance metadata;
- no exported model artifact is promoted to production or model-channel merely because desktop parity succeeds.

Only after this parity layer passes may the actual `ctcCompact` scorer be connected to A1 retained state and measured on Android arm64.

## Product token construction requirements

True CTC output must not create a new kind of pseudo precision:

- CTC BPE token spans are authoritative at their acoustic span resolution;
- byte fragments that together encode one Unicode character must be compacted without losing the first start / last end acoustic span;
- a multi-character BPE token must **not** be artificially divided into per-character timestamps;
- supported sentence punctuation follows official anchoring semantics where it has no acoustic realization;
- unknown/symbol/undecodable-fragment cases fail safe as spoken content rather than collapsing their span;
- impossible alignment falls back to FSMN/VAD segment timing.

The user-facing goal is therefore trustworthy fine-grained timing, usually character-like for Chinese, but never fabricated “one timestamp per character” when the CTC tokenizer itself provides only a wider subword span.

## Remaining empirical Gate items

Source-level portability and native compile/ABI portability are positive, but the CTC gate is **not yet a final technical PASS**. The following still must be demonstrated before the `/2` package is accepted:

1. export the exact pinned checkpoint's reusable pre-adaptor encoder state and CTC decoder/projection to ONNX;
2. verify exported tensor dimensions, vocab size, blank id, 60 ms frame period and tokenizer digest;
3. parity-check Android tokenizer ids against the exact official `multilingual.tiktoken` artifact;
4. parity-check ONNX CTC text/logits/timestamps against official FunASR Python on controlled samples;
5. execute the real dual-output ONNX through the A1 native shared-encoder retained-state path and prove exact length/state behavior;
6. connect the trusted compact native CTC scorer output to the already-tested Android Viterbi core;
7. prove character/subword timing tracks speech rather than uniform interpolation on Android arm64;
8. verify byte-fragment compaction and official punctuation anchoring without fabricating per-character timing;
9. measure CTC wall time/RTF, total RTF, peak/end PSS and thermal behavior;
10. verify Hotword OFF/ON completeness remains P2-clean with CTC enabled;
11. only then stage model-channel manifest `v10` / revision `/2`, still Draft and unpromoted.

## Sentence segmentation dependency

The user's current observation that segmentation remains poor is expected at this checkpoint. Do not tune CAM++ first.

After real CTC timing is available, re-run sentence/speaker segmentation using:

`FunASR punctuation + real CTC timing + speaker turns + silence gaps`

with semantic completeness preferred over mechanical one- or two-character cuts.

## Gate decision

- **P2 completeness: REAL-DEVICE PASS (user reported no obvious missing content).**
- **P3 synthetic timing firewall: IMPLEMENTED / FAST CI PASS.**
- **Android CTC Viterbi core: IMPLEMENTED / FAST CI PASS.**
- **Android SenseVoice CTC tokenizer core: IMPLEMENTED / FAST CI PASS.**
- **CTC source architecture: PORTABLE IN PRINCIPLE.**
- **CTC runtime A0 config/arm64 rebuild: PASS.**
- **CTC runtime A1 shared-encoder retained-state: COMPILE/ABI PASS.**
- **Current `/1` model package: INSUFFICIENT FOR TRUE CTC.**
- **A2 actual model export/parity: NEXT / NOT YET PASS.**
- **Revision `/2`: REQUIRED, but NOT YET BUILT OR STAGED.**
- **CTC portability Gate final decision: IN PROGRESS pending actual export/parity + native model execution + Android empirical proof.**
- **Stage 16B Final Freeze/Handoff: BLOCKED.**

No PR merge, model-channel promotion, Stage 16C work, Room migration or historical Qwen compatibility deletion is authorized by this gate.
