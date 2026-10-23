# Stage 16B — FunASR Nano CTC Portability Gate

Status: **SOURCE GATE PASS / ANDROID TOKENIZER + VITERBI CORE CI PASS / RUNTIME A0+A1+A2 COMPILE/ABI PASS / A3-H1 EXACT CHECKPOINT PASS / CURRENT REVISION 1 INSUFFICIENT / A3-H2 EXPORT + PARITY REQUIRED / FINAL FREEZE BLOCKED**

Date: 2026-10-21

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

## Gate purpose

P2 has passed the user real-device completeness check: the post-gate QA build no longer showed the obvious Hotword-ON missing span or other obvious omissions. The remaining product issues are the expected ones for the pre-CTC route: sentence segmentation is still poor and the visible timeline remains coarse segment-level rather than character/token-level.

This gate determines whether the official FunASR Nano CTC forced-alignment path can be brought to Android without reintroducing fake precision or pushing the high-quality path beyond a practical mobile performance/memory envelope.

## P3 prerequisite — synthetic timing firewall — PASS

Pinned sherpa-onnx v1.13.8 FunASR generated-token timestamps are synthetic/evenly distributed timing, not acoustic forced alignment.

P3 therefore fails closed at both data and capability layers:

- sherpa synthetic token/timestamp output is never accepted as canonical FunASR product timing;
- the FunASR base runtime never advertises native token timing, even if a descriptor accidentally claims it;
- stale FunASR `TimestampCapability.TOKEN` is downgraded to `NONE`;
- `EXTERNAL_ALIGNER_REQUIRED` remains available for the trusted revision-2 wrapper;
- High Quality transcription falls back to trusted FSMN/VAD segment timing until real CTC exists;
- FireRed true native timing, SenseVoice behavior and historical Qwen compatibility remain unchanged.

Fast PR Gate #2153 passed the timing-firewall regression suite. Later A2 stream-protocol changes passed Fast PR Gate #2164 at head `01dea605f6a83341d3426e4b95da91961c166381`.

## Official FunASR CTC reference path

Current official FunASR Nano source establishes a separate CTC branch from the audio encoder:

1. `encoder_out, encoder_out_lens = self.encode(...)`;
2. LLM path separately runs `audio_adaptor(encoder_out, ...)`;
3. CTC path runs `ctc_decoder(encoder_out, encoder_out_lens)`;
4. CTC projection produces full-vocabulary logits/log probabilities;
5. the final LLM text is encoded with the **SenseVoice CTC tokenizer**;
6. `forced_align(ctc_logits, target_ids, blank_id)` aligns that final text;
7. returned CTC frame indexes are converted at **60 ms per frame** (`6 * 10 ms`);
8. punctuation with no acoustic realization is anchored according to the official timestamp post-processing rules.

The current official model implementation identifies:

- audio encoder output dimension: `512`;
- audio adaptor input `512`, LLM output dimension `1024`, 2 adaptor layers;
- CTC decoder input/output working dimension `512`, 5 decoder layers;
- low-frame-rate/downsample factor: `6` over the 10 ms frontend frame shift;
- CTC tokenizer: `SenseVoiceTokenizer` using `multilingual.tiktoken`;
- default CTC vocabulary size: `60515`;
- default blank id: `ctc_vocab_size - 1`, therefore `60514` unless configuration overrides it.

A3-H1 has now independently verified that the accepted checkpoint's actual CTC projection is `[60515,512]`, yielding actual vocabulary `60515` and blank id `60514` for the accepted source.

### Official checkpoint provenance rule for A3

The provenance rule is explicit:

- the older Hugging Face checkpoint at revision `272c57b82523ada6fd87095e955f8e29100979ab`, `model.pt` SHA-256 `55ae0d2fee369f0f11cce0795f6927934ad17cf11b278a7e56a51272074160bb`, is the older text-only artifact and is **not** an acceptable true-CTC export source;
- the accepted CTC-complete source is ModelScope `FunAudioLLM/Fun-ASR-Nano-2512`.

### A3-H1 exact checkpoint audit — PASS

At branch checkpoint `3504e45559e99ee37b43a5c7300ef5c72b9f3af9`, FunASR CTC A3 Heavy Gate #8 independently downloaded, locally rehashed and audited the accepted source before any export:

- requested source revision: `master`;
- `model.pt` size: `2,127,426,538` bytes remote and local;
- `model.pt` SHA-256: `81fec8616083c69377f3ceef36aba3655660ee0ca69a5d4a1e9810cd340ca499` remote and locally recomputed;
- `config.yaml` SHA-256: `daed38ea6484f5650fb32cbd9069b9aa13880acaf2bcb1f0bf4be2712837917c`;
- `multilingual.tiktoken` SHA-256: `747979631e813193436aabcff7c1c235d37de8097b71c563ec8b63b7a515c718`;
- normalized checkpoint tensors: `1347`;
- exact CTC tensor inventory: `86 = 84 ctc_decoder.* + 2 ctc.*`;
- decoder blocks: `[0,1,2,3,4]`;
- decoder FFN: `[2048,512] -> [512,2048]`;
- CTC projection weight `[60515,512]`, bias `[60515]`;
- derived vocab `60515`, blank `60514`, projection input dim `512`;
- all audited CTC tensors finite and required weight tensors non-zero;
- full SHA-256 verification completed before `torch.load(weights_only=true, mmap=true)`;
- all source/model assets remained runner-local and cleanup removed them; nothing was uploaded.

The same evidence point had Android PR CI #2188 **SUCCESS** and A3 Provenance Gate #13 **SUCCESS**. The filtered four-range ModelScope download completed in about `6m41s`.

**H1 evidence boundary:** the accepted source checkpoint is now independently proven CTC-complete. This does not prove ONNX export correctness, numerical parity, revision `/1` versus `/2` LLM parity, Android execution, real acoustic timing, RTF/PSS or real-device segmentation.

Before any candidate can be packaged, the mutable source label `master` must be represented by immutable provenance metadata/digests; H1's exact file digests are the current byte-level source truth.

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

More importantly, sherpa's current `encoder_adaptor` session exposes the fused encoder/adaptor result used as LLM audio embeddings. The official CTC branch consumes the **pre-adaptor 512-dimensional `encoder_out`**. The existing revision-1 Android/Kotlin path cannot obtain that tensor through its public contract.

### Gate finding

**Revision 1 cannot produce a correct official CTC timeline by wiring extra Kotlin logic around its existing public output.**

Feeding the current post-adaptor LLM embedding into the official CTC decoder would be architecturally wrong. Using the Qwen tokenizer as the CTC tokenizer would also be wrong.

## Android forced-alignment core — IMPLEMENTED / CI PASS

A model-independent Android CTC Viterbi core exists in `FunAsrCtcForcedAligner.kt`.

It accepts a compact score surface rather than the full CTC vocabulary tensor:

- per-frame blank score;
- per-frame score for each target-token position;
- exact target token ids.

The state graph is `[blank, y0, blank, y1, ...]`. Adjacent equal targets cannot skip the intervening blank. Structurally impossible, non-finite or unreachable paths return refusal rather than an interpolated timestamp.

The core maps aligned frames to canonical PCM sample offsets using the official 60 ms CTC frame period.

Automated tests cover ordinary alignment, repeated targets, insufficient frames, no-finite-path refusal, 60 ms -> 16 kHz conversion and empty targets.

Android head `2be12664b6586b895947487ea7140af478c0a5f8`: Fast PR Gate #2134 **SUCCESS**.

## Compact raw-logit equivalence — IMPLEMENTED / CI PASS

Forced alignment does not require materializing full-vocabulary log-softmax values. At each frame, log-softmax subtracts one common `logsumexp` term from every vocabulary logit; every valid Viterbi path consumes exactly one emission per frame, so that common per-frame term cannot change the argmax path.

Revision-2 can therefore gather only `[blank + final-text target positions]` from the model-owned CTC projection and align the resulting raw logits. Android tests lock this equivalence.

This substantially reduces CTC memory/JNI pressure compared with a `T × 60515` score tensor.

## SenseVoice CTC tokenizer core — IMPLEMENTED / CI PASS

Official FunASR re-encodes the final LLM text with `SenseVoiceTokenizer` before forced alignment. Android has a minimal `SenseVoiceCtcTokenizer` that:

- loads the model-owned `.tiktoken` base64-byte/rank resource;
- uses the official ordinary-text Unicode split pattern;
- performs rank-priority byte BPE;
- encodes final text to CTC target ids;
- decodes raw byte pieces so split UTF-8 characters round-trip correctly;
- does not embed or guess the production vocabulary and is not activated for revision `/1`.

Tests cover CJK byte fragments, BPE rank priority and mixed text/space/punctuation round-trip.

Android head `bbfe68299b44a2ea9249469896646ce895993dd1`: Fast PR Gate #2136 **SUCCESS**.

A3 Source/Tokenizer Gate has additionally verified the exact accepted `multilingual.tiktoken` against independent Python target-id fixtures, including the real non-canonical Base64 entry accepted by Python's permissive loader.

## Existing APK / ONNX Runtime packaging finding

The current release-like Voica APK already contains sherpa JNI and sherpa's shared `libonnxruntime.so`. `engine:sherpa` depends on the sherpa-onnx AAR and does not expose a separate public `ai.onnxruntime` Java API to app code.

Therefore:

- do not add a second ONNX Runtime Android AAR merely to run CTC;
- avoid duplicate native libraries/version skew and duplicate allocator/thread-pool footprints;
- keep a single ORT native runtime boundary.

The pinned sherpa v1.13.8 Android line uses ONNX Runtime 1.28.2. The revision-2 native extension is therefore built against that exact boundary.

## Native runtime A0 — optional config / arm64 rebuild — PASS

A dedicated isolated workflow proved the required sherpa extension can be rebuilt without replacing the app dependency:

- sherpa-onnx `v1.13.8`;
- Android `arm64-v8a`;
- ONNX Runtime `1.28.2`;
- NDK `28.2.13676358`;
- optional `ctcCompact` config path;
- JNI compile PASS;
- arm64-only AAR assemble PASS;
- Kotlin config ABI, JNI marker and ORT linkage PASS;
- no AAR upload / no production dependency replacement.

Checkpoint `d7040f4a47881afe51102510686ddb19be589930`: FunASR CTC Runtime Gate #8 **SUCCESS**; Android PR CI #2154 **SUCCESS**.

## Native runtime A1 — one encoder + retained CTC state — COMPILE/ABI PASS

A1 proved the shared-encoder lifecycle before scorer execution:

1. when `ctcCompact` is configured, run one encoder session and require exactly `encoder_out`, `ctc_encoder_out`, `ctc_encoder_out_lens`;
2. require CTC state `[1,T,512]` plus exact int64 length `[1]`, with `0 < length <= T`;
3. pass post-adaptor `encoder_out` into the existing LLM path;
4. retain pre-adaptor state + length for that exact `OfflineStream`;
5. protect retained state with a dedicated mutex, bounded storage and monotonic state id;
6. use existing stream options plus a second same-stream `decode()` for internal `align` / `discard` commands;
7. do not rerun encoder or LLM on that second command;
8. return no placeholder timing while the scorer is absent.

Checkpoint `ccfd80d7acbf99bb5c747794da93ae743c7a46a0`: FunASR CTC Runtime Gate #11 **SUCCESS**; Android PR CI #2157 **SUCCESS**.

## Native runtime A2 — compact scorer + native Viterbi — COMPILE/ABI PASS

A2 proves the complete native alignment boundary without yet executing real revision-2 model assets.

The isolated patch adds:

1. **Compact CTC session in the same ORT runtime**
   - optional `ctc_compact` ONNX;
   - exact inputs `ctc_encoder_out`, `ctc_encoder_out_lens`, `candidate_ids`;
   - exact output `candidate_logits`;
   - encoder input float/fp16, lengths/ids int64, output float;
   - strict output `[1,T,K]` validation.

2. **Strict model-owned alignment request**
   - stream options carry final-text target ids, blank id and vocab size;
   - Kotlin validates all IDs against vocab and forbids blank in final-text targets;
   - native repeats those checks and caps target count fail-closed;
   - candidate order is `[blank + target positions]`, preserving duplicate target positions when final text has adjacent equal token ids.

3. **Native Viterbi matching the Android reference contract**
   - CTC expanded states `[blank, y0, blank, y1, ...]`;
   - stay / advance-one / advance-two transitions;
   - adjacent equal targets require a blank transition;
   - impossible or non-finite paths refuse alignment;
   - exact retained encoder length bounds the valid frame range;
   - accepted spans map at 60 ms/frame.

4. **No second encoder / no full-vocabulary JNI tensor**
   - retained pre-adaptor state from A1 is consumed once;
   - the second same-stream decode does only compact CTC + Viterbi;
   - aligned target IDs/start/duration reuse the existing `OfflineRecognitionResult` ABI.

At Android head `01dea605f6a83341d3426e4b95da91961c166381`:

- FunASR CTC Runtime Gate #17: **SUCCESS**;
- A0/A1/A2 patch application: PASS;
- sherpa v1.13.8 + ORT 1.28.2 arm64 JNI build: PASS;
- arm64-only AAR package: PASS;
- final A2 CTC ABI marker verification: PASS;
- Android Fast PR Gate #2164: **SUCCESS**.

**A2 evidence boundary:** this proves source/runtime portability and build/ABI correctness. It does not yet prove real dual-output ONNX execution, numerical CTC parity, real non-uniform timestamps, RTF/PSS or real-device segmentation quality.

The expensive Runtime Gate is now edge-triggered: use a `[CTC]` PR-title transition or `workflow_dispatch` only when the native runtime itself changes and needs re-verification. Ordinary pushes do not rebuild sherpa.

## Encoder reuse requirement and accepted native architecture

The B6 FunASR baseline leaves limited headroom before the important `total RTF < 1.0` target:

- ASR RTF: `0.6323`;
- total RTF: `0.6533`;
- peak PSS: `1,728,929 KB`.

A second complete audio-encoder pass remains a fallback, not the target architecture.

The A1/A2 compile Gate now proves the preferred topology is implementable on the pinned Android runtime:

1. run audio encoder once;
2. preserve pre-adaptor 512-d state + exact length;
3. feed post-adaptor output to existing LLM ASR;
4. retain pre-adaptor state for CTC;
5. after final LLM text is known, encode it with SenseVoice CTC tokenizer;
6. run compact CTC decoder/projection only for blank + target positions;
7. native Viterbi returns trusted acoustic spans;
8. Kotlin converts accepted spans to product tokens/canonical `sampleIndex`.

A second encoder pass remains permitted only as an empirical fallback if total RTF/PSS/thermal/completeness remain acceptable, but it is no longer the preferred implementation.

## Required revision-2 candidate architecture

The preferred candidate preserves model id/version and changes revision only after the CTC path proves viable:

`funasr-nano-int8@2025-12-30/2`

Revision 2 must add/export:

1. **Dual-output encoder/adaptor**
   - output[0] existing post-adaptor `encoder_out` for LLM text parity;
   - output[1] exact pre-adaptor `ctc_encoder_out` with dimension 512;
   - output[2] exact int64 `ctc_encoder_out_lens`.

2. **Compact CTC scorer**
   - contains the official trained CTC Transformer decoder plus model-owned CTC projection/gather;
   - accepts pre-adaptor state, exact lengths and candidate ids;
   - returns raw `candidate_logits` for only blank + final-text target positions;
   - must not replace the trained CTC decoder with a direct projection from raw encoder state.

3. **SenseVoice CTC tokenizer**
   - exact accepted-source `multilingual.tiktoken`;
   - Qwen LLM tokenizer must not be reused for CTC.

4. **Explicit alignment metadata**
   - actual CTC vocab size and blank id;
   - frame period/downsample mapping;
   - exact tensor names/types/dimensions;
   - source checkpoint/revision and every artifact SHA-256;
   - tokenizer digest.

If LLM context capacity is changed, that must be treated as an explicit `/2` model change with separate text-parity evidence, not silently bundled.

## A3-H2 — dual-output export / numerical parity Gate — NEXT

A3-H1 has completed source-byte and CTC-tensor proof. H2 is now the first Gate allowed to create empirical revision-2 ONNX candidates.

Required H2 evidence:

- export a dual-output encoder/adaptor from the accepted source, preserving the existing post-adaptor LLM-facing output and adding exact pre-adaptor `[1,T,512]` CTC state + exact length;
- compare the post-adaptor output against the reference `/1`/official path under identical controlled inputs before changing quantization;
- export `ctc_compact` from the official trained five-block CTC Transformer decoder plus model-owned projection/gather;
- preserve full-vocabulary CTC path-order semantics while materializing only blank + final-text target-position scores outside the graph/runtime boundary;
- cross-check the exported graph's vocab `60515`, blank `60514`, input/output tensor names/types/shapes and 60 ms frame contract;
- use the exact H1 tokenizer digest `747979631e813193436aabcff7c1c235d37de8097b71c563ec8b63b7a515c718`;
- compare official Python full-vocabulary forced alignment with compact raw-logit Viterbi on controlled fixtures;
- establish FP32 export parity first, then separately quantify any INT8 export error before accepting INT8 artifacts;
- record SHA-256 and immutable provenance for every export candidate;
- do not stage or promote model-channel merely because desktop H2 parity succeeds.

Only after H2 and subsequent export/parity checks pass may the actual `/2` assets be connected to the A2 native runtime in the app and measured on Android arm64.

## Product token construction requirements

True CTC output must not create a new kind of pseudo precision:

- CTC BPE token spans are authoritative at their acoustic span resolution;
- byte fragments composing one Unicode character are compacted using first-start / last-end acoustic span;
- a multi-character BPE token is not artificially split into per-character timestamps;
- supported punctuation follows official anchoring semantics where it has no acoustic realization;
- unknown/symbol/undecodable fragments remain safe spoken spans rather than collapsing to zero width;
- impossible alignment falls back to FSMN/VAD segment timing.

The user-facing goal is trustworthy fine-grained timing, usually character-like for Chinese, but never fabricated one-timestamp-per-character precision when the CTC tokenizer provides a wider subword span.

## Remaining empirical Gate items

Source/runtime portability and H1 source completeness are positive, but the CTC Gate is **not yet a final technical PASS**. Remaining evidence:

1. A3-H2 export and hash the real dual-output encoder + compact trained CTC graph;
2. verify dual-output encoder and compact CTC tensor semantics against official Python;
3. verify official full-vocab versus compact raw-logit forced-alignment parity;
4. verify LLM text/numerical parity `/1` versus `/2` and quantify INT8 error separately;
5. execute real `/2` ONNX through the A2 same-stream retained-state path on Android arm64;
6. prove non-uniform character/subword timing tracks speech rather than interpolation;
7. verify byte-fragment compaction and punctuation anchoring;
8. measure CTC wall time, total RTF, peak/end PSS and thermal behavior;
9. verify Hotword OFF/ON completeness remains P2-clean;
10. re-test sentence/speaker segmentation with real CTC;
11. only after those pass may model-channel manifest v10 / revision `/2` be staged as Draft, still unpromoted.

## Sentence segmentation dependency

The user's current observation that segmentation remains poor is expected at this checkpoint. Do not tune CAM++ first.

After real CTC timing is available, re-run sentence/speaker segmentation using:

`FunASR punctuation + real CTC timing + speaker turns + silence gaps`

with semantic completeness preferred over mechanical one- or two-character cuts.

## Gate decision

- **P2 completeness: REAL-DEVICE PASS.**
- **P3 synthetic timing firewall: IMPLEMENTED / CI PASS.**
- **Android CTC Viterbi core: IMPLEMENTED / CI PASS.**
- **Android SenseVoice CTC tokenizer core: IMPLEMENTED / CI PASS.**
- **CTC source architecture: PORTABLE IN PRINCIPLE.**
- **CTC source/tokenizer provenance: PASS.**
- **CTC runtime A0 config/arm64 rebuild: PASS.**
- **CTC runtime A1 shared-encoder retained-state: COMPILE/ABI PASS.**
- **CTC runtime A2 compact scorer/native Viterbi: COMPILE/ABI PASS.**
- **A3-H1 exact CTC-complete checkpoint audit: PASS.**
- **Current `/1` model package: INSUFFICIENT FOR TRUE CTC.**
- **A3-H2 dual-output/CTC export + numerical parity: NEXT / NOT YET PASS.**
- **Revision `/2`: REQUIRED, but NOT YET BUILT OR STAGED.**
- **CTC portability Gate final decision: IN PROGRESS pending H2 export/parity + real Android model execution + real-device proof.**
- **Stage 16B Final Freeze/Handoff: BLOCKED.**

No PR merge, model-channel promotion, Stage 16C work, Room migration or historical Qwen compatibility deletion is authorized by this gate.