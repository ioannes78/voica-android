# Stage 16B — Local File ASR V2 Progress

Status: **GATE A TECHNICAL PASS / GATE B REAL-DEVICE PASS / PRODUCT MATRIX AUTHORIZED / P2 REAL-DEVICE PASS / P3 TIMING FIREWALL CI PASS / CTC RUNTIME A0+A1+A2 COMPILE/ABI PASS / A3-H1 EXACT CHECKPOINT PASS / H2 EXPORT + PARITY NEXT / FINAL FREEZE BLOCKED**

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

Base `main`: `988931ebed72a70ba799d909cd36a416a673eef5`

Room: `v14`

## Frozen project boundaries

- `ioannes78/voice-card-android` remains isolated; no copy, migration, cherry-pick, mechanical translation or rewrite;
- Stage 16A.2 annotation ownership remains `Recording`;
- canonical/realtime `sampleIndex` remains the durable primary coordinate;
- no QS668/CB08 protocol, Android Audio I/O, playback or Stage 16A.2 ownership changes;
- CI success never substitutes for real-device QA;
- production model-channel changes require explicit promotion authorization;
- Stage 16C remains `RESERVED / NOT STARTED`.

## Gate A — VAD Matrix — TECHNICAL PASS

Final Local File policy: `FSMN VAD INT8 primary -> Silero VAD INT8 fallback`.
Realtime remains Silero until Stage 16C.1. TEN remains candidate/QA only.

Key evidence: A2 weighted CER FSMN `32.34%`, TEN `51.35%`, Silero `72.50%`; A4 30-minute total RTF FSMN `0.128`, Silero `0.187`; A4 VAD wall FSMN `13.337 s`, Silero `131.736 s`. The 60/120-minute extension was explicitly waived. FSMN product version is `1.0.0`.

Model-channel promotion remains staged only in Draft `ioannes78/voica-model-channel#21`; do not merge without explicit authorization.

## Gate B — completed evidence

Candidates were SenseVoice INT8, FunASR Nano INT8 and Qwen3-ASR 0.6B INT8. Formal Gate B evidence used exact active FSMN.

### B0–B4

- B0 readiness: all three candidates loaded and produced nonblank overall transcription without crash on the same exact FSMN-frozen PCM.
- B1 quality: Qwen3 owned the strongest controlled long-Mandarin result; FunASR owned the strongest mixed/proper-name tendency and active ITN signal; SenseVoice remained the efficiency baseline.
- B2 completeness: an exact 30-second consumer cap exposed some FunASR whole-chunk blanks. The common formal benchmark cap was reduced to **25 seconds**, which removed those observed benchmark blanks.
- B3: FunASR produced the strongest controlled mixed-language and hotword A/B result (`CER 0.1724 -> 0.0345`, exact coverage `2/3 -> 3/3`).
- B4 historical decision remains valid as a Gate result: canonical segment timing is separate from precise token timing. SenseVoice supplied full native timing; FunASR/Qwen routes without trustworthy token timing retained canonical segment boundaries or used safe-refusal alignment probes where applicable.

### B5 — isolated performance / memory

| Candidate | Cold ASR RTF | Warm ASR RTF | Cold peak PSS KB | Warm peak PSS KB |
| --- | ---: | ---: | ---: | ---: |
| SenseVoice | **0.0876** | **0.0871** | 389108 | 713942 |
| FunASR Nano | **0.6547** | **0.6300** | 1753708 | 1745334 |
| Qwen3-ASR | 0.9767 | 0.7543 | 2342673 | 2330256 |

All six passes completed with `blankCalls=0` and no crash/OOM.

### B6 — finalist long-file endurance — REAL-DEVICE PASS

Formal input: recording `badf8d81-4ede-4fb1-b1f7-966d85e2ac78`, canonical SHA `2f611589ba1c48eb0d7f4634340c9e5e4bc81f0c40c635d508464ad797138e878`, exact FSMN `1.0.0/1`, `threads=2`, `frozenInputs=54`, recording `628.3468 s`, frozen speech `597.7900 s`.

| Metric | FunASR Nano | Qwen3-ASR |
| --- | ---: | ---: |
| ASR RTF | **0.6323** | 0.7120 |
| total RTF | **0.6533** | 0.7256 |
| first-quarter ASR RTF | **0.5836** | 0.7407 |
| last-quarter ASR RTF | **0.6304** | 0.6546 |
| thermal ratio | 1.0801 | **0.8838** |
| peak PSS KB | **1728929** | 2396068 |
| end PSS KB | **1543305** | 2390460 |
| calls / blanks | 54 / **0** | 54 / **0** |

Both finalists passed without crash/OOM. The accepted ~10.5-minute run did not leave material endurance ambiguity, so the preferred ~30-minute extension remains waived.

## Authorized Local File ASR V2 product roles

- **SenseVoice INT8 = balanced / efficiency**;
- **FunASR Nano INT8 = high quality**;
- **Qwen3-ASR 0.6B INT8 = removed from the production product matrix and replaced by FunASR Nano**.

Historical Qwen runtime/evidence may remain for prior-transcript compatibility and QA traceability; it is not a production Local File ASR choice.

## Post-Gate P0–P2 — completeness remediation

Pinned sherpa-onnx v1.13.8 FunASR generated-token timestamps were corrected from the earlier post-gate interpretation: they are synthetic/evenly distributed timing, not CTC/forced acoustic alignment.

Separately, a fixed 118-second recording had shown an entire ASR span missing with Hotword ON while the same span was present with Hotword OFF. P1/P2 added context-budget diagnostics and replaced fixed-duration guesswork with a FunASR-specific adaptive planner:

- estimated prompt/hotword/audio/generation budget diagnostics;
- target approximately 15–18 seconds, budget-aware rather than a new fixed cut;
- reliable low-energy boundaries preferred;
- continuous/low-volume speech protected by approximately one second overlap;
- normalized suffix/prefix overlap reconciliation;
- blank/truncation/capacity/abnormal-short-output attempts retried shorter and fail closed if still suspicious;
- SenseVoice and FAST pipelines unchanged;
- Room unchanged at v14.

### P2 real-device result — PASS

The user installed the production-like P2 QA build and reported the test **passed with no obvious missing or incorrect content**. The previous Hotword-ON whole-span omission was not reproduced.

The user also reported:

- no separate Stage 16B QA Hub desktop icon — **expected for this release-like QA packaging**, whose launcher contract keeps only normal Voica;
- sentence segmentation is still problematic — **open / deferred until real CTC timing is available and re-tested**;
- timeline is still coarse large segments and has no character-level timing — **expected pre-CTC state, not a P2 completeness regression**.

P2 completeness is therefore closed as REAL-DEVICE PASS. This does not imply Final Freeze.

## P3 — synthetic timing firewall — PASS

P3 now fails closed at both the token-data and capability layers:

- sherpa v1.13.8 FunASR synthetic timestamps never enter canonical product token timing;
- FunASR base runtime never advertises native token timing, even if a descriptor accidentally declares it;
- a stale FunASR `TimestampCapability.TOKEN` is downgraded to `NONE`;
- `EXTERNAL_ALIGNER_REQUIRED` remains available for the future trusted `/2` wrapper;
- downstream High Quality persistence therefore falls back to canonical FSMN/VAD segment timing until true CTC is connected;
- FireRed real native timing, SenseVoice behavior and historical Qwen compatibility remain unchanged.

Fast PR Gate #2153 passed the timing-firewall regression suite. The later A2 protocol changes also passed Fast PR Gate #2164 at head `01dea605f6a83341d3426e4b95da91961c166381`.

## P4/P5 — true CTC timeline

Current official FunASR source establishes the timestamp path as:

`pre-adaptor audio encoder output -> CTC Transformer decoder -> CTC projection -> final LLM text encoded with SenseVoice CTC tokenizer -> forced alignment -> 60 ms CTC frame mapping -> canonical sampleIndex`.

The current production package `funasr-nano-int8@2025-12-30/1` is insufficient:

- it contains the sherpa LLM path (`encoder_adaptor.int8.onnx`, `embedding.int8.onnx`, `llm.int8.onnx`, Qwen tokenizer);
- it does not contain the trained CTC Transformer decoder/projection or SenseVoice CTC tokenizer;
- its public encoder/adaptor output is post-adaptor LLM embedding, while official CTC consumes the pre-adaptor 512-d encoder output.

Therefore true CTC cannot be implemented correctly by attaching an aligner to revision `/1`.

Full portability findings and the required revision-2 architecture are recorded in `docs/STAGE_16B_CTC_PORTABILITY_GATE.md`.

### CTC runtime A0 — config / ABI rebuild — PASS

A dedicated no-artifact runtime Gate rebuilt exact sherpa-onnx `v1.13.8` for Android `arm64-v8a` against ONNX Runtime `1.28.2` after adding the optional `ctcCompact` configuration ABI.

- pinned sherpa source and ORT version were checked explicitly;
- native JNI compiled successfully;
- the patched arm64 AAR assembled successfully;
- the AAR exposed the expected Kotlin `ctcCompact` getter/setter and retained only the intended arm64 native libraries;
- the patched AAR was **not** uploaded and did **not** replace the production dependency.

Checkpoint `d7040f4a47881afe51102510686ddb19be589930`: FunASR CTC Runtime Gate #8 **SUCCESS** and Android PR CI #2154 **SUCCESS**.

### CTC runtime A1 — shared encoder / retained state — COMPILE/ABI PASS

A1 proved the intended one-encoder lifecycle without executing a CTC scorer:

- when `ctcCompact` is configured, one encoder execution can expose `encoder_out`, `ctc_encoder_out`, and exact `ctc_encoder_out_lens`;
- CTC state is strictly validated as `[1,T,512]` plus `[1]` int64 valid length with `0 < length <= T`;
- the existing LLM branch consumes `encoder_out` from that same execution;
- pre-adaptor state and length are retained per exact `OfflineStream`;
- state-id plus bounded storage fail closed against stale pointer reuse or unbounded native tensor retention;
- second same-stream `align` / `discard` commands consume or clear retained state without rerunning encoder or LLM;
- no placeholder timing is returned while the scorer is absent.

Checkpoint `ccfd80d7acbf99bb5c747794da93ae743c7a46a0`: FunASR CTC Runtime Gate #11 **SUCCESS** and Android PR CI #2157 **SUCCESS**.

### CTC runtime A2 — compact scorer + native Viterbi — COMPILE/ABI PASS

A2 extends the isolated patched runtime with the complete native alignment boundary while still using no real `/2` model asset:

- one optional `ctc_compact` ONNX session shares the same sherpa/ORT `1.28.2` native runtime;
- exact scorer ABI is `ctc_encoder_out + ctc_encoder_out_lens + candidate_ids -> candidate_logits`;
- only `[blank + final-text target positions]` are scored, avoiding a full `T × 60515` JNI tensor;
- raw compact logits are valid for Viterbi because full-vocabulary log-softmax subtracts the same per-frame constant from every path state;
- stream protocol carries exact model-owned `blank_id`, `vocab_size` and final-text `target_ids` with bounds checks in both Kotlin and native code;
- native CTC Viterbi implements stay / +1 / +2 transitions, requires blank separation for adjacent repeated targets, rejects impossible/non-finite paths, and maps accepted frame spans at 60 ms/frame;
- aligned decimal target IDs, acoustic start seconds and durations are returned through the existing `OfflineRecognitionResult` ABI;
- no second encoder pass and no large score tensor crosses JNI.

At Android head `01dea605f6a83341d3426e4b95da91961c166381`:

- FunASR CTC Runtime Gate #17: **SUCCESS**;
- A0/A1/A2 patch application: PASS;
- arm64 JNI build with sherpa `v1.13.8` + ORT `1.28.2`: PASS;
- arm64-only AAR package: PASS;
- A2 CTC ABI/state/option/scorer marker verification: PASS;
- Android Fast PR Gate #2164: **SUCCESS**.

**Evidence boundary:** A2 proves native source portability, ABI shape and buildability only. No actual revision-2 dual-output encoder or compact CTC ONNX has yet executed through this runtime, so it does not yet prove numerical parity, non-uniform real timing, RTF or PSS.

The expensive native Gate is now edge-triggered: prefix PR #35's title with `[CTC]` (or use `workflow_dispatch`) only when a new native runtime checkpoint needs verification. Ordinary pushes do not rebuild sherpa.

### CTC A3-H1 — exact checkpoint audit — PASS

The older Hugging Face checkpoint at revision `272c57b82523ada6fd87095e955f8e29100979ab` / `model.pt` SHA-256 `55ae0d2fee369f0f11cce0795f6927934ad17cf11b278a7e56a51272074160bb` remains a text-only artifact and is **not** the true-CTC export source.

A3-H1 independently audited the current ModelScope source `FunAudioLLM/Fun-ASR-Nano-2512` on runner-local storage before any export. At branch checkpoint `3504e45559e99ee37b43a5c7300ef5c72b9f3af9`, FunASR CTC A3 Heavy Gate #8 **SUCCESS** established:

- `model.pt` remote and locally recomputed size: `2,127,426,538` bytes;
- `model.pt` remote and locally recomputed SHA-256: `81fec8616083c69377f3ceef36aba3655660ee0ca69a5d4a1e9810cd340ca499`;
- `config.yaml` SHA-256: `daed38ea6484f5650fb32cbd9069b9aa13880acaf2bcb1f0bf4be2712837917c`;
- `multilingual.tiktoken` SHA-256: `747979631e813193436aabcff7c1c235d37de8097b71c563ec8b63b7a515c718`;
- checkpoint normalized tensor count: `1347`;
- exact CTC tensor inventory: `86 = 84 ctc_decoder.* + 2 ctc.*`;
- decoder blocks: `0..4`;
- CTC FFN geometry: `[2048,512] -> [512,2048]`;
- projection weight `[60515,512]`, bias `[60515]`;
- derived CTC vocabulary size `60515`, blank id `60514`, projection input dimension `512`;
- all audited CTC tensors finite and required weights non-zero;
- checkpoint was fully SHA-256 verified **before** `torch.load(weights_only=true, mmap=true)`;
- the 2.13 GB source and audit assets remained runner-local and were deleted by cleanup; no model artifact was uploaded.

The filtered four-range ModelScope download completed in about `6m41s`, replacing the earlier single-stream H1 attempt that was killed only by its 35-minute workflow timeout.

The same H1 evidence point also had Android PR CI #2188 **SUCCESS** and A3 Provenance Gate #13 **SUCCESS**.

**H1 evidence boundary:** this independently proves that the accepted source checkpoint is CTC-complete and locks the current source bytes/tensor geometry. It does **not** prove exported ONNX numerical parity, `/1` -> `/2` LLM parity, Android execution, real acoustic timestamps, performance or real-device segmentation.

### CTC A3-H2 — dual-output encoder + compact CTC export/parity — NEXT

H2 is now the next Gate. It must:

- export one encoder/adaptor execution that preserves the existing post-adaptor LLM output while also exposing the exact pre-adaptor `[1,T,512]` state and exact length required by A1/A2;
- prove the dual-output graph preserves `/1` LLM-facing encoder/adaptor numerical behavior under identical inputs;
- export the official trained five-block CTC Transformer decoder plus model-owned CTC projection/gather as `ctc_compact`, rather than projecting raw encoder state directly;
- preserve the official CTC scoring semantics while returning only blank + final-text target-position scores to the runtime;
- validate tensor names/types/shapes, actual vocab `60515`, blank `60514`, tokenizer digest and 60 ms frame contract from the accepted source/export;
- compare official Python full-vocabulary forced alignment with compact Android/native Viterbi on controlled fixtures;
- establish FP32 export parity before accepting any INT8 quantization;
- record SHA-256 and immutable provenance for every exported candidate asset.

No `/2` package is accepted or staged merely because H1 passed.

## Production-state boundaries

- Local File VAD: `FSMN primary -> Silero fallback`;
- realtime VAD: Silero unchanged until Stage 16C.1;
- Local File balanced/efficiency ASR: SenseVoice INT8;
- Local File high-quality ASR: FunASR Nano INT8;
- current production/staged FunASR identity remains `funasr-nano-int8@2025-12-30/1`;
- no revision `/2` package or model-channel v10 has been staged yet;
- model-channel staged manifest remains v9 at Draft PR #21 until separately authorized;
- Room remains v14;
- Android PR #35 remains Draft/unmerged;
- model-channel PR #21 remains Draft/unmerged;
- Stage 16C remains `RESERVED / NOT STARTED`.

## Current work order

1. **P0–P2: COMPLETE / REAL-DEVICE PASS.**
2. **P3: IMPLEMENTED / CI PASS.** Synthetic sherpa timing is fail-closed at both token-data and capability layers.
3. **P4 / CTC A0+A1+A2: COMPILE/ABI PASS.** Keep the patched sherpa runtime isolated; it is not a production dependency.
4. **P4 / CTC A3-H1: EXACT CHECKPOINT PASS.** Accepted ModelScope bytes and the complete 86-tensor CTC branch are independently verified.
5. **P4 / CTC A3-H2: NEXT.** Export the real shared dual-output encoder + compact CTC scorer, prove Python/export/tokenizer/digest parity, then evaluate INT8 only after FP32 parity.
6. **P5 / product integration:** only after A3-H2+ parity, connect the patched AAR and same-stream align/discard protocol to the `/2` candidate; convert trusted CTC spans to product tokens and canonical sample-index timing with fail-closed fallback.
7. Re-test sentence segmentation and speaker alignment only after real CTC is present; do not tune CAM++ first.
8. Measure CTC wall/RTF, total RTF, peak/end PSS, thermal, retries and completeness; preserve the important high-quality target `total RTF < 1.0` where practical.
9. Run real-device acceptance on the 118-second Hotword OFF/ON case plus at least two longer recordings and verify non-uniform real timing, completeness and boundary quality.
10. Only after CTC Gate + real-device timeline/segmentation/performance acceptance may revision `/2` / model-channel v10 remain as the candidate for later explicit promotion authorization.
11. Do not Final Freeze/Handoff, merge either PR, promote model-channel, or start Stage 16C without explicit user authorization.