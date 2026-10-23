# Stage 16B — Post-Gate Remediation

Status: **P2 REAL-DEVICE PASS / P3 TIMING FIREWALL CI PASS / CTC PORTABILITY GATE IN PROGRESS / FINAL FREEZE BLOCKED**

Date: 2026-10-21

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

## Why this remediation exists

The Stage 16B Gate B product-role decision remains valid, but production-like real-device revalidation after the post-gate polish exposed two separate FunASR Nano product problems:

1. sherpa-onnx FunASR timestamp output had been incorrectly promoted as a precise native token timeline;
2. enabling FunASR hotwords could make a long recording lose an entire span of transcription that was present with hotwords disabled.

P0–P2 addressed the completeness blocker. P3 now blocks the pseudo timeline from the product contract. P4/P5 are reserved for a true official-style CTC forced-alignment path.

## Current real-device state

| Item | State |
| --- | --- |
| release-like QA launcher behavior (normal Voica icon only) | **PASS / EXPECTED** |
| FunASR hotword UI / settings / call path | **PASS** |
| Local Speech product descriptions | **PASS** |
| FunASR ITN switch / runtime use | **PASS** |
| FunASR Hotword ON transcription completeness after P2 | **PASS — no obvious omissions reported** |
| FunASR synthetic timestamp product exposure | **BLOCKED BY P3** |
| FunASR true character/token CTC timeline | **NOT YET IMPLEMENTED** |
| Sentence segmentation | **OPEN — still problematic** |
| Stage 16B Final Freeze / Handoff | **BLOCKED** |

The user's report that no separate Stage 16B QA Hub icon appears in the installed P2 release-like QA APK is consistent with the previously accepted launcher contract. It is not a regression.

## Timestamp correction

Pinned runtime: `sherpa-onnx v1.13.8`.

The current sherpa FunASR recognizer does not perform acoustic forced alignment for generated LLM tokens. It derives effective audio duration/context coverage and distributes generated-token timestamps uniformly. Those timestamps are therefore **synthetic / evenly distributed timing**.

They remain useful to P1 diagnostics because their effective coverage can expose context truncation, but they are not a valid product CTC timeline.

## P1/P2 — Hotword completeness remediation

The fixed 118-second user recording originally showed:

- Hotword OFF: relevant speech span present;
- Hotword ON: an entire ASR span absent.

P1/P2 introduced:

- explicit context-budget diagnostics;
- a token-budget-aware FunASR chunk planner targeting roughly 15–18 seconds rather than treating 25 seconds as a guaranteed safe cut;
- reliable low-energy boundary preference;
- approximately one second overlap when speech is continuous, including low-volume continuous speech;
- normalized exact/fuzzy overlap reconciliation;
- automatic shorter retries for blank, inferred context truncation, inferred generation-capacity exhaustion or strong abnormal-short-output signatures;
- fail-closed behavior for suspicious leaves that remain unresolved.

### P2 real-device result

The user installed the new production-like QA build and reported that testing **passed with no obvious errors or omissions**. The previous obvious Hotword-ON missing-span symptom was not observed.

Therefore P2 is recorded as **REAL-DEVICE PASS** and the work may proceed to P3–P5. This pass is specifically a transcription-completeness result; it does not upgrade timeline or sentence segmentation.

## P3 — synthetic timing firewall

P3 changes only the product-facing FunASR timing contract:

- the underlying sherpa result may still contain generated tokens/timestamps so P1 diagnostics can infer generated-token count and effective audio coverage;
- `FunAsrAdaptiveOfflineAsrEngine.capabilities` overrides that private delegate and reports `supportsTokenTiming=false` / `TimestampCapability.NONE`;
- every final product `AsrHypothesis` returned by the adaptive FunASR wrapper exposes `tokens = emptyList()` until true CTC alignment exists;
- this includes the single-chunk/no-retry case, eliminating the last path by which synthetic timing could leak into canonical token timing;
- High Quality transcription then safely retains FSMN/VAD segment-level timing instead of pseudo token precision.

Automated coverage verifies both sides of the firewall: product timing is empty, while diagnostics can still observe private sherpa generated-token information.

P3 checkpoint `11642818f670788afecb636f1914cdf7c6363559`: Fast PR Gate #2132 **SUCCESS**.

## P4/P5 — true CTC timeline boundary

The official FunASR Nano implementation uses a separate CTC branch fed by the **pre-adaptor audio encoder output**. It applies a CTC decoder/projection, encodes the final LLM text with the **SenseVoice CTC tokenizer**, and uses CTC forced alignment. The official mapping is 60 ms per CTC frame.

The currently installed/staged `funasr-nano-int8@2025-12-30/1` package cannot supply that path correctly because:

- its sherpa package has only the LLM recognition route (`encoder_adaptor`, `embedding`, `llm`, Qwen tokenizer);
- it lacks the CTC decoder/projection and SenseVoice CTC tokenizer;
- the existing public encoder/adaptor result is the LLM-facing post-adaptor embedding, not the pre-adaptor 512-d encoder state the official CTC branch requires.

Accordingly, revision `/1` stays the P2/P3 production identity. No fake or approximate CTC bridge will be accepted.

The source audit is positive enough to continue: a revision `/2` candidate can be designed around a shared audio encoder with two downstream branches, plus Android CTC Viterbi forced alignment. However, the portability gate remains **IN PROGRESS** until ONNX export, official parity and real Android performance/timing proof are complete.

See `docs/STAGE_16B_CTC_PORTABILITY_GATE.md`.

## Sentence segmentation

The user's current report that sentence segmentation is still problematic is expected while only segment-level timing is trusted.

Do not tune CAM++ first. After real CTC timing exists, re-evaluate segmentation using:

`FunASR punctuation + real CTC timing + speaker turns + silence gaps`

with semantic sentence completeness preferred over mechanical one/two-character speaker/timestamp cuts.

## Non-changes / prohibited actions

This remediation does **not**:

- merge Android PR #35;
- merge model-channel PR #21;
- promote model-channel v9;
- stage model-channel v10 yet;
- change production model roles;
- delete historical Qwen3 compatibility/runtime provenance;
- change Room v14;
- change realtime Silero VAD;
- Final Freeze/Handoff Stage 16B;
- start Stage 16C.
