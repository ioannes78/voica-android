# Stage 17.3 R17C-P2-C1 — CPU/GPU Short Numeric Fidelity Control

Status: QA CODE WRITTEN / AUTOMATED CI AND REAL-DEVICE GATES REQUIRED
Target: ioannes78/voica-android PR #37, still OPEN/DRAFT. main unchanged.

## Why

The already verified real-device R17C-P2-B diagnostic showed:
- Runtime LiteRT-LM 0.18.0 / Gemma 4 E2B-IT revision r1.
- Explicit unconstrained GPU 8K/2048, no CPU fallback, full same-source prompt SHA.
- GPU ttfeMs 28115 / generationMs 316601 / initializeMs 71606 / peakPssKb 2673840.
- Numerical distortion in raw GPU summary: 87%->877%, 标普500->50500,
  2015->20015, 2025->20205, FDA 223->2223, 1000万美元->100万美元,
  7000万美元->700万美元; mixed Japanese/Vietnamese/English fragments.
- GPU kept "三到八成" correct where CPU 8K/2048 had incorrectly produced "3%到8%".
- One run cannot identify whether the cause is stochastic sampling, GPU arithmetic,
  model/runtime behavior, or other confounds. Production GPU remains unqualified.

Source snapshot for historical context:
recording EHBZKFZH6GIZG.m4a, source digest
931223800714b8d92ae7bfd3f821cb374058c1e8d6c29d225b6f72a3cedd4a81.
This new short fixture is curated from source transcript claims. It is *not* proof
that these statistics are externally true.

## P2-C1 controlled experiment

Add an isolated QA panel with one exact 5-fact prompt.
The five facts include:
1. GPT-4.5 87%.
2. 标普500, 2015, 2025, 50%.
3. FDA, 1995, 1, 2023, 223.
4. 15个月, 1000万美元, 7000万美元.
5. 三到八成 (30%到80%).

Two manual variants use the same promptSHA, sampler, model, LiteRT-LM 0.18.0,
context 4000 and max output 512; only backend changes:
- P2-C1-A CPU 4K/512.
- P2-C1-B explicit GPU 4K/512.

The frozen instruction asks for five short numbered summaries with original
numbers/units retained, no JSON and no gratuitous foreign-language text.
No production prompt, Room, model-channel, routing, summary versions, or
responseJsonSchema are changed. No online upload. Existing one application
LLM Runtime owner is reused.

QA numeric assessment deliberately errs toward REVIEW_REQUIRED. It looks for
literal reference values and known corruption signatures, including scripts
and a few previously observed unrequested words. It does not perform general
semantic verification, entity linking, or external statistical fact checking.
Even NO_KNOWN_FLAG requires manual source comparison.

## Safety and sequencing

The prior structured GPU workload caused intermittent ANR, and a lock-screen
reproduction caused a device reboot. The source cause has not been proven.

Test sequence, in a new QA screen session:
1. Cover-install the new QA APK. Foreground, screen on, phone cool, no concurrent
   heavy load. Do not lock, background, or stress the GPU.
2. In P2-C1 panel, tap P2-C1-A CPU 4K/512 once; verify output and report.
3. In the existing P2 panel, acknowledge GPU risk and tap P2-A GPU 4K short
   probe once. It must resolve GPU, output R17C_GPU_OK, avoid fallback and
   return runtime IDLE with after-close PSS.
4. Only if CPU baseline and P2-A succeed and the phone remains responsive,
   manually tap P2-C1-B GPU 4K/512 once.
5. Export WENZHI-Stage17.3-R17C-P2-C1-NumericQA.txt and share it for comparison.

The UI guards foreground/resumed/interactive/unlocked, idle native runtime,
one run per variant, risk acknowledgment, and GPU P2-A success. P2-C1 and P2-B
GPU 8K are mutually excluded after successful completion in the same QA screen.
An error or cancellation blocks P2-C1 further runs; GPU-side failure also
blocks the P2 GPU test path in this session. GPU runs use QA-only
FLAG_KEEP_SCREEN_ON and release the flag in finally. Manual power button
can still lock the phone and is NOT safe to test.

If the process is killed, has ANR, native hang, abnormal thermal behavior,
unconfirmed release, or device restart: STOP testing GPU. Preserve existing
R5 crash breadcrumbs after relaunch. A native crash may bypass Kotlin handlers.

## CI contract

- CPU/GPU requests are exactly prompt-identical, with equal sampler/context/
  output cap and null native JSON Schema.
- Preflight conservative prompt estimate + output cap + 192 + 256 <= 4000.
- Frozen reference matcher recognizes numeric repetitions and magnitude loss.
- Unrecognized numerals or missing numbers are flagged for review, not passed.
- Language-signal detection includes Japanese/Korean script and specific
  anomalous words noted in the prior output.
- Runtime backend identity, no fallback, IDLE and post-close PSS required.
- Full diagnostics and raw text export on explicit user action only.
- Android PR CI and releaseQa APK gate must pass prior to any real-device QA.

## Next gate after results

Use same-configuration CPU/GPU comparison to determine whether corruption
also appears on CPU with the same short prompt. Do not attribute faults to
GPU based on an isolated stochastic output.

If a reproducible backend-specific defect remains, design a separate
0.18.1 isolation build / controlled A/B QA after reviewing dependency/ABI and
model revision compatibility. Upgrading production 0.18.0 is NOT authorized.

PR #37 stays DRAFT. DO NOT MERGE or promote GPU to production.
