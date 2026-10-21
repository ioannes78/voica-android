# Stage 16B — Local File ASR V2 Progress

Status: **GATE A TECHNICAL PASS / GATE B REAL-DEVICE PASS / PRODUCT MATRIX AUTHORIZED / P2 REAL-DEVICE PASS / P3 TIMING FIREWALL CI PASS / CTC RUNTIME A0+A1 COMPILE/ABI PASS / CTC PORTABILITY GATE IN PROGRESS / FINAL FREEZE BLOCKED**

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

## P3 — synthetic timing firewall

P3 is implemented on the FunASR product wrapper:

- sherpa synthetic token/timestamp values remain private diagnostic input only;
- FunASR product capabilities now advertise no token timing;
- FunASR product hypotheses always expose an empty token timeline until real CTC exists;
- downstream High Quality persistence therefore falls back to canonical FSMN/VAD segment timing;
- pseudo-precision can no longer enter normal FunASR speaker alignment through the wrapper.

Regression coverage explicitly verifies that even a single, non-retried FunASR chunk cannot leak synthetic tokens while the private diagnostic generated-token signal remains available.

Checkpoint: Android head `11642818f670788afecb636f1914cdf7c6363559` — Fast PR Gate #2132 **SUCCESS** (unit tests + debug compile + FSMN runtime boundary).

## P4/P5 — true CTC timeline

Source audit confirms the official FunASR Nano timestamp path is:

`pre-adaptor audio encoder output -> CTC decoder/projection -> CTC log probabilities -> SenseVoice CTC tokenizer(final text) -> forced alignment -> 60 ms CTC frame mapping -> canonical sampleIndex`.

The current production package `funasr-nano-int8@2025-12-30/1` is insufficient:

- it contains the sherpa LLM path (`encoder_adaptor.int8.onnx`, `embedding.int8.onnx`, `llm.int8.onnx`, Qwen tokenizer);
- it does not contain the official CTC decoder/projection or SenseVoice CTC tokenizer;
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

The next isolated native Gate adds the revision-2 runtime shape without a CTC scorer yet:

- a single encoder session can expose `encoder_out`, `ctc_encoder_out`, and exact `ctc_encoder_out_lens`;
- the LLM branch consumes the post-adaptor embedding from that same encoder execution;
- the pre-adaptor 512-d state + exact length can be retained per `OfflineStream`;
- state IDs protect against stale stream-pointer reuse;
- retained native state is bounded and fail-closed;
- `align` / `discard` commands consume or clear retained state without rerunning encoder or LLM;
- revision `/1` remains inactive because no `ctcCompact` model is configured.

Exact sherpa `v1.13.8` + ORT `1.28.2` arm64 JNI compilation, AAR packaging, ABI inspection and A1 state-boundary marker verification all passed at head `ccfd80d7acbf99bb5c747794da93ae743c7a46a0`:

- FunASR CTC Runtime Gate #11: **SUCCESS**;
- Android PR CI #2157: **SUCCESS**.

**Evidence boundary:** A1 is a native compile/ABI architecture PASS only. No real revision-2 dual-output ONNX has executed through this path yet, so tensor values, exact length parity and retained-state model execution remain an A2 empirical requirement.

### A2 next — exact checkpoint / export / parity

The next Gate must freeze an exact official Fun-ASR-Nano-2512 checkpoint and prove actual exported model assets before product integration. Current official provenance independently identifies source revision `272c57b82523ada6fd87095e955f8e29100979ab` and source `model.pt` SHA-256 `55ae0d2fee369f0f11cce0795f6927934ad17cf11b278a7e56a51272074160bb`; Voica must still verify all downloaded/exported artifacts itself before accepting them.

A2 acceptance requires:

- real dual-output encoder ONNX with exact output names/shapes and `ctc_encoder_out_lens`;
- CTC decoder/projection/log-softmax export from the same checkpoint;
- exact `multilingual.tiktoken` artifact + digest;
- exported vocab size, blank id, frame/downsample metadata and all artifact digests;
- controlled official-Python parity for tokenizer target ids, encoder lengths, CTC score behavior and timestamps;
- no model-channel `/2` staging or production replacement merely because export succeeds.

Current CTC Gate status: **IN PROGRESS — A0/A1 compile/ABI architecture proven; real model export + parity + Android empirical proof still required before final PASS.**

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
2. **P3: IMPLEMENTED / FAST CI PASS.** Keep sherpa synthetic timing diagnostic-only and product timing fail-closed.
3. **P4 / CTC A0+A1: COMPILE/ABI PASS.** Keep the patched sherpa runtime isolated; it is not a production dependency.
4. **P4 / CTC A2: NEXT.** Freeze the exact official checkpoint, export the real shared encoder + CTC scorer/tokenizer assets, verify digests/metadata and official-Python parity, then execute the retained-state path empirically.
5. **P5:** connect trusted compact CTC scores to the existing Android forced/Viterbi alignment, canonical sample-index mapping, punctuation anchoring and fail-closed fallback; prove actual non-uniform character/token timing on device.
6. Re-test sentence segmentation and speaker alignment only after real CTC is present; do not tune CAM++ first.
7. Measure CTC wall/RTF, total RTF, peak/end PSS, thermal, retries and completeness; preserve the important high-quality target `total RTF < 1.0` where practical.
8. Only after CTC Gate + real-device timeline/segmentation/performance acceptance may revision `/2` / model-channel v10 remain as the candidate for later explicit promotion authorization.
9. Do not Final Freeze/Handoff, merge either PR, promote model-channel, or start Stage 16C without explicit user authorization.
