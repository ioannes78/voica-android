# Stage 17.3 R17C P1-R2 — 8K/2048 Direct Transcript Summary & Numeric Fidelity QA

Status: QA-ONLY CHANGE / AUTOMATED GATE REQUIRED / REAL-DEVICE QA PENDING.
Repository: ioannes78/voica-android. PR #37 stays OPEN/DRAFT.
This revision is explicitly part of user-confirmed R17C plan, following P0/P1 real-device report.

## Grounded prior QA evidence

Source recording: EHBZKFZH6GIZG.m4a, 507611 ms, 84 effective transcript units, 2705
transcript chars, source inputDigest:
931223800714b8d92ae7bfd3f821cb374058c1e8d6c29d225b6f72a3cedd4a81.

R17C P0/P1 real-device QA on same input and prompt:
- 8K context / 1024 output / CPU / LiteRT-LM 0.18.0: 3368 conservative prompt tokens,
  TTFE 66434 ms, generation 287449 ms, peak PSS 2629961 KiB, after-close PSS 1345575 KiB.
- 16K context / 2048 output / CPU / LiteRT-LM 0.18.0: same prompt / estimate,
  TTFE 70454 ms, generation 462568 ms, peak PSS 3487853 KiB, after-close
  PSS 2419349 KiB.

16K output includes additional final questions, risk and a DeepMind CEO claim relative to
8K/1024; it is NOT evidence that increasing context *alone* improves summary.
Both outputs had a critical scale defect: source says "三到八成" (30%-80%),
but model wrote "3% 到 8%". No claim of accuracy for external source facts is made.

## Experiment & controls

Add one QA-only manual case:
P1-R2-8K / CPU / 2048 (maxNumTokens=8000, maxOutputTokens=2048).
Keep the current 8K/1024 and 16K/2048 controls available, but do NOT automatically
start multiple generations. The one under test is **8K/2048**. CPU-only,
LiteRT-LM 0.18.0, no native JSON Schema, speculative decoding disabled.
Same full Current Effective Transcription, exact frozen direct prompt text, model
revision and sampler settings as P0/P1. Never mutate source text or prompt to
fit. Conservative budget check: estimate + 2048 + 192 + 256 <= 8000.
Known sample: 3368 + 2048 + 448 = 5864 (within gate).

If the current source is different, report inputDigest / transcriptSHA / promptSHA.
Do NOT claim same-source comparison merely from filename. R17B-1 FINAL replay should
also only be compared against same source digest.

## Numeric fidelity sentinel

QA-only, text-level known-regression detection:
- Source contains exact "三到八成".
- Generated text contains patterns such as "3%到8%", "3% 到 8%" or "3%-8%".
- Mark FLAG_CRITICAL, explain magnitude should be 30%-80% according to the spoken
  source. Never auto-edit model response; preserve raw output for audit.
- If source lacks this expression: NOT_APPLICABLE.
- If no match: NO_KNOWN_PATTERN, explicitly *not* a general semantic/number PASS.

Extend report with this sentinel per run and manual numerical review guidance
for ratios/percent, timespan, money, organization names and sourced claims.
Do not confuse unit-sentinel with a comprehensive truth checker or Evidence QA.

## Real device steps

1. Cover-install R17C P1-R2 QA APK; do not clear app data.
2. Stage 17 P1 → "R17C P0/P1 · 完整逐字稿直读" → "读取本机已完成的逐字稿".
3. Select EHBZKFZH6GIZG.m4a. Confirm source digest prefix 93122380 and
   exact prompt SHA obtained in report; prior R17C prompt SHA:
   e80da37fe54b0b6bd73450113814a058193b19038a022a070ddb4b7fa5822d2f.
4. Click **P1-R2 · 8K / 2048 · CPU 同源对照（优先）** only.
5. Let native inference complete while foreground and screen on. STOP if
   native abort, ANR, OOM, process restart, RELEASE_UNCONFIRMED, or
   unreasonably high resident memory. No poisoned-runtime retry.
6. Save "WENZHI-Stage17.3-R17C-P1-R2-8K2048-DirectSummary.txt" through
   Android document picker. User supplies the report for manual comparison.

## Gate & evaluation

CI:
- same prompt SHA across all tiers; only context and output cap differ;
- 8K/2048 is CPU, 0 native response schema, no speculation;
- preflight gate rejects oversize prompt without truncation;
- number-sentinel flags known 3%-8% defect only when source contains "三到八成";
- report retains full transcript and raw model output, with warning.
- no changes to Room, product model weights/catalog, production summary routing,
  Release JSON schema, GPU safety containment, or storage history.

Manual QA:
- Compare 8K/1024 vs 8K/2048 vs 16K/2048 on same original transcript and prompt.
- Evaluate coverage for 20 independently source-checked key facts:
  numeric/units, specific cases, legal/medical examples, vendor/CEO views,
  risks, end-of-conversation questions.
- New variant should recover more correct facts with bounded latency and
  memory; zero serious number-scale distortion to qualify a quality PASS.
- Generate time is not wall-clock total and metrics from different lengths are
  not directly speed-normalized.
- Even a positive result does not justify switching production default or
  enabling structured GPU. Separate Gate P2 handles GPU in a later step.

## Safety

PR #37 remains DRAFT and must not be merged without explicit user approval.
This revision is QA-only; no automatic model install, no external upload,
no forced P2 launch, no new network/cloud routes.
