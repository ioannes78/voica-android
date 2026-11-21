# Stage 17.3 R17C-P2-C2 — Medium Transcript CPU/GPU Fidelity Gate

Status: QA CODE COMPLETE / CI AND REAL-DEVICE PASS NOT YET CLAIMED.
Target branch: stage17-fully-offline-intelligence, PR #37 OPEN/DRAFT.
main remains unchanged. Do not merge without explicit approval.

## Prior evidence and objective

P2-C1 on pinned LiteRT-LM 0.18.0, Gemma 4 E2B-IT r1:
- exact same 351-estimated-token prompt, CPU/GPU 4K/512;
- 5 of 5 curated transcript-derived numeric facts retained by both CPU and GPU;
- output strings byte-identical in that single run, no observed known numeric
  corruption or abnormal language-signal patterns;
- CPU generation 33473 ms / GPU 32296 ms, GPU initialization much slower;
- both reported runtime IDLE and non-null PSS after close.

P2-B on full 2705-character authentic ASR input exhibited model output errors:
877% in place of 87%, 标普50500, 20015, 20205, 2223, lower monetary scale,
mixed-language tokens. Existing shorter CPU run had independently corrupted
"三到八成" into "3%到8%". Consequently backend attribution remains unproven.
We must not extrapolate 351-token success to long-transcript stability.

This P2-C2 stage bridges the gap without repeating 8K GPU stress or updating
LiteRT-LM 0.18.0 to 0.18.1.

## Fixed source, exact comparison

Use **Current Effective Transcription** from the on-device recordings, not the
hand-cleaned P2-C1 fixture. Recommended test recording:
EHBZKFZH6GIZG.m4a, 84 units, 2705 chars.
Known original source input digest:
931223800714b8d92ae7bfd3f821cb374058c1e8d6c29d225b6f72a3cedd4a81.
Do not claim it is the same source unless digest and transcript SHA match:
a0308c81751ecbadfa1b637f4278210526b2e64849fb5cb115f8924274bc7364.

On starting C2-A, deterministically take the **largest contiguous, intact
paragraph prefix** of this on-device original whose conservative estimate
of the entire prompt is within 1500..2000 tokens. Do not split within a
paragraph, rewrite ASR errors, fabricate factual examples, or claim that the
partial prompt is the complete recording. If no valid prefix exists, refuse
before native inference. Freeze selectedText/SHA, source lineage, prompt/SHA,
estimated tokens and selected evidence rows before CPU execution. C2-B GPU
must reuse those same immutable bytes, even if UI selection later changes.

Parameters for both cases:
- maxNumTokens=4000, maxOutputTokens=1024;
- P0_BASELINE topK=64, topP=0.95, temperature=1.0;
- responseJsonSchema=null, speculativeDecoding=false;
- explicit CPU for C2-A, explicit GPU for C2-B;
- safety check estimate + 1024 + 192 + 256 <= 4000.
Only the backend policy differs between C2-A and C2-B.

NOTE: temperature 1.0 means these are not deterministic generations. Even if
same prompt and runtime, a one-shot difference does not prove GPU-specific
numerical corruption; repeated safe statistical studies require later design.

## Safety

Previous structured GPU QA included an ANR and a manual lock-state device
reboot. Current product's native-schema AUTO summaries remain CPU-contained.
P2-C2 does not change this containment or Room/model-channel/product routing.

On a new QA screen:
1. Cover-install fresh QA APK, do not clear app data, foreground/screen on.
2. Load and select the authentic current effective transcript under R17C P0/P1.
3. Tap C2-A **CPU 4K/1024** once; inspect source selection and frozen segment
   SHA and prompt SHA. If runtime failure, stop.
4. Only if C2-A succeeds and device looks normal: in original P2 panel
   acknowledge risk, manually perform P2-A GPU 4K SHORT probe once.
5. Only if P2-A passes and Android Activity is foreground/resumed/interactive,
   no keyguard, Runtime IDLE, no prior GPU failure, sufficient available memory
   >= 3 GiB and not lowMemory: optionally tap C2-B **GPU 4K/1024** once.
6. Native GPU experiments must never run with manually locked screen or app
   backgrounded. The actual host Activity sets FLAG_KEEP_SCREEN_ON during GPU
   attempts, clears in finally; it cannot prevent a manual power-button lock,
   nor a native hang that does not return to Kotlin.
7. On ANR, abnormal temperature, process exit, device reboot, native resource
   release uncertainty, STOP. Retrieve R5 crash breadcrumb on next launch,
   never retry P2-C2 GPU in that session.
8. Export WENZHI-Stage17.3-R17C-P2-C2-MediumQA.txt through a local document picker.
   The report includes source lineage, selected prompt and SHA, raw CPU/GPU
   outputs, exact runtime diagnostics, selected transcript/evidence and
   conservative known-corruption checks. No automatic cloud upload.

**No GPU 8K or P2-C1 GPU** after successful C2 GPU in this same QA screen;
the reverse exclusions are also enforced. All cases user-triggered one at a
time; no automatic GPU fallback or engine replacement.

## Evaluation

- Compare fixed source and prompt SHA equality.
- Compare initializeMs, ttfeMs, generationMs, peakPssKb, pssClosedKb, resolved
  backend/fallback and error/cancellation/release behavior.
- Manually inspect facts, numbers, amounts, percentages, Chinese measure words,
  vendor names, omissions, invented claims and mixed-language text.
- Automated warnings recognize observed 877%, 50500, 20015, 20205, 2223,
  100/700万美元 and 3%到8% patterns. They are partial text indicators, not a
  semantic or external truth oracle. An absent flag means *not detected*.
- Presence of Evidence IDs alone never proves citation support.
- QA success does not authorize production GPU or lockscreen use.

## Release gate

- Unit tests for source-prefix selection, refusal of undersized inputs,
  bit-identical frozen prompt except backend, output budget, inherited
  native safeguards, diagnostics and lineage sidecars.
- Android PR CI and dedicated releaseQa APK workflow must pass.
- Actual hardware Gate remains PENDING until the user sends the new report.

After interpreting C2: if reproducible GPU-specific corruption is demonstrated,
a distinct LiteRT-LM 0.18.1 isolated build / A/B plan may be considered. If
not, investigate prompt, ASR normalization, sampling and fact fidelity before
attributing issues to GPU. No direct production runtime upgrade is authorized.
