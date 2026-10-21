# Stage 16B — FunASR Nano CTC Portability Gate

Status: **SOURCE GATE PARTIAL PASS / CURRENT REVISION 1 INSUFFICIENT / EXPORT + ANDROID VALIDATION REQUIRED / FINAL FREEZE BLOCKED**

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

The official model configuration identifies:

- audio encoder output dimension: `512`;
- audio adaptor input `512`, LLM output dimension `1024`, 2 adaptor layers;
- CTC decoder input/output working dimension `512`, 5 decoder layers;
- audio encoder downsample rate: `6` over the 10 ms frontend frame shift;
- CTC tokenizer: `SenseVoiceTokenizer` using the multilingual tokenizer resource;
- default CTC vocabulary size in the current implementation: `60515`;
- default blank id therefore resolves to `60514` unless an exported artifact explicitly overrides it.

**Exported model metadata remains authoritative. The Android package must record and verify the actual vocab size and blank id rather than hard-code the default assumption.**

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

## Required revision-2 candidate architecture

The preferred candidate preserves the product model id/version and changes only revision after the CTC path proves viable:

`funasr-nano-int8@2025-12-30/2`

Revision 2 should add/export all of the following as one coherent package:

1. **Reusable pre-adaptor audio encoder state**
   - preferred: export a dual-output encoder/adaptor graph or split encoder + adaptor so the expensive audio encoder runs once;
   - one output feeds the existing LLM adaptor path;
   - the 512-d pre-adaptor encoder state feeds CTC.

2. **CTC decoder + projection/log-softmax ONNX**
   - exact graph must match the official checkpoint;
   - output frame count/period must be validated against the official 60 ms mapping.

3. **SenseVoice CTC tokenizer resource**
   - use the checkpoint-compatible `multilingual.tiktoken`/equivalent exported vocabulary;
   - do not reuse the Qwen LLM tokenizer for CTC.

4. **Explicit alignment metadata**
   - CTC vocab size;
   - blank id;
   - frame shift/downsample mapping;
   - encoder/decoder tensor names and dimensions;
   - source checkpoint/revision and digests.

If the LLM must also be re-exported with a larger `max_total_len`, that change should be bundled into the same `/2` candidate rather than immediately creating `/3`.

## Android forced-alignment design

Android must not depend on Python/torchaudio at runtime.

The official `torchaudio.functional.forced_align` behavior can be reproduced with a standard CTC Viterbi/forced-alignment dynamic program over the final-text CTC target sequence and blank transitions.

Preferred runtime boundary:

- run CTC ONNX in native/ORT code;
- retain or gather only blank + target-symbol log-probabilities needed by the Viterbi path where practical;
- avoid keeping a full `T x 60515` float tensor across JNI longer than necessary;
- perform forced alignment natively or pass a compact score matrix to Kotlin;
- convert frame spans to canonical absolute `sampleIndex` using the verified 60 ms frame period and the source chunk offset;
- punctuation anchoring follows official semantics;
- any impossible/low-integrity alignment **fails closed to VAD segment timing**, never synthetic interpolation.

This design is materially safer for peak PSS than exposing the entire CTC vocabulary tensor to Kotlin.

## Encoder reuse requirement

The B6 FunASR baseline leaves limited performance headroom before the important `total RTF < 1.0` target:

- ASR RTF: `0.6323`;
- total RTF: `0.6533`;
- peak PSS: `1,728,929 KB`.

A second complete audio-encoder pass for CTC would duplicate one of the expensive front-end computations and is therefore a fallback, not the target architecture.

**Gate target: one audio encoder computation, two downstream branches (LLM ASR + CTC alignment).**

## Remaining empirical Gate items

Source-level portability is positive, but the CTC gate is **not yet a final technical PASS**. The following must be demonstrated before the `/2` package is accepted:

1. export the exact checkpoint's pre-adaptor encoder output and CTC decoder/projection to ONNX;
2. verify exported tensor dimensions, vocab size, blank id, frame period and tokenizer digest;
3. parity-check ONNX CTC text/timestamps against official FunASR Python on controlled samples;
4. implement and unit-test Android CTC Viterbi forced alignment, including repeated symbols, blank transitions, punctuation and impossible-target refusal;
5. run the candidate on Android arm64 and prove character/token timing tracks speech rather than uniform interpolation;
6. measure CTC wall time/RTF, total RTF, peak/end PSS and thermal behavior;
7. verify Hotword OFF/ON completeness remains P2-clean with CTC enabled;
8. only then stage model-channel manifest `v10` / revision `/2`, still Draft and unpromoted.

## Sentence segmentation dependency

The user's current observation that segmentation remains poor is expected at this checkpoint. Do not tune CAM++ first.

After real CTC timing is available, re-run sentence/speaker segmentation using:

`FunASR punctuation + real CTC timing + speaker turns + silence gaps`

with semantic completeness preferred over mechanical one- or two-character cuts.

## Gate decision

- **P2 completeness: REAL-DEVICE PASS (user reported no obvious missing content).**
- **P3 synthetic timing firewall: IMPLEMENTED / FAST CI PASS.**
- **CTC source architecture: PORTABLE IN PRINCIPLE.**
- **Current `/1` model package: INSUFFICIENT FOR TRUE CTC.**
- **Revision `/2`: REQUIRED, but NOT YET BUILT OR STAGED.**
- **CTC portability Gate final decision: IN PROGRESS pending export + Android empirical proof.**
- **Stage 16B Final Freeze/Handoff: BLOCKED.**

No PR merge, model-channel promotion, Stage 16C work, Room migration or historical Qwen compatibility deletion is authorized by this gate.
