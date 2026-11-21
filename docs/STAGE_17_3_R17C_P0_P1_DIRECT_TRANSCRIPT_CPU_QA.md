# Stage 17.3 R17C P0/P1 — Real Long Transcript Direct Summary (QA only)

Status: IMPLEMENTED IN QA BRANCH / AUTOMATED CI AND REAL-DEVICE GATES REQUIRED.
Target: ioannes78/voica-android PR #37 (Draft); not production-promoted.

## Why

R17B-1 reused a frozen 12-NODE FINAL prompt (estimated 1,546 tokens), with 7/12 important
NODEs appearing in the output and all B1/B2/B3 responses identical. Larger FINAL schema
and context budgets did not repair omissions. Gallery's successful free-form summary
does not yet prove equivalent model, backend, source, sampling or token count; use it only as
an external comparison, not a factual oracle.

R17C P0/P1 isolates two questions:

1. Can the current *full effective transcript* produce materially better coverage without
   the MAP/REDUCE/FINAL compression pipeline?
2. What is the actual cost of native CPU free-form summarization at 4K/8K/16K on the
   already pinned Stage 17 model/runtime?

It does NOT re-enable structured GPU, change production routing, change model weights,
or upgrade LiteRT-LM 0.18.0.

## P0 real transcript lineage

The existing QA-only Stage 17 P1 screen now adds a R17C card.

- Tap **读取本机已完成的逐字稿** to enumerate up to 30 recently updated completed
  Current Effective Transcription snapshots. Source data comes from the same repository
  selectors used by production summary (including current selected transcription revision).
- Select the recording with **上一条/下一条**. The sample under test should be
  **EHBZKFZH6GIZG.m4a** if it appears among the completed recordings.
- Observe transcript chars, unit count, SHA-256 of raw text, and the original snapshot
  inputContentDigest (which uses the source snapshot's original canonical text delimiter).
- If the R17B long-summary diagnostic was imported on the same QA screen, compare the
  **inputDigest** fields; a mismatch means the samples may not be semantically identical.
- Save **P0 原文及 P1 总结对照 TXT**, even before any run. This is an explicit user-initiated
  Android document picker export; the report contains the entire effective text plus
  an Evidence sidecar (unit ID, Sxxxxx/NO_EVIDENCE and corresponding text).
- The sidecar is only a traceability aid. Evidence membership does not prove semantic support.

No user recording/transcript is uploaded or silently logged to analytics or written to Room.
The QA source is in ephemeral UI memory only. It is not a completed production summary.

## P1 direct plain-text experiment

Only explicit CPU is allowed. Each attempt uses exactly the same original full transcript,
wrapped in a minimal, fixed Chinese summary instruction. No MAP, REDUCE, FINAL, response
JSON Schema, cloud fallback, source-tail truncation, or checkpoint mutation.

| Manual button | Context tokens | Output tokens | Backend | Runtime |
| --- | ---: | ---: | --- | --- |
| P1-4K | 4,000 | 1,024 | CPU | pinned 0.18.0 |
| P1-8K | 8,000 | 1,024 | CPU | pinned 0.18.0 |
| P1-16K | 16,000 | 2,048 | CPU | pinned 0.18.0 |

Before any native initialize, require:
conservativePromptEstimate + output cap + 192 template + 256 safety <= context tokens.

If a source does not fit, reject the candidate with a clear reason. **Never truncate**,
silently compress, reuse a shorter snapshot, or auto-attempt the next tier.
16K is a high-resource QA attempt; only try it when adequate memory, cooling and
manual risk acceptance are present. R17A prior single-run CPU 16K success does not
qualify GPU 16K or all phones.

The runtime reuses the application's one AppLocalLlmRuntime (single heavy inference
owner, resource/thermal gate, cancellation/ownership rules, pinned model identity).
One manual attempt at a time; no automatic multi-run chain or GPU try/fallback.
Free-form outputs are held in memory and only exported through the explicit picker.

## Record and review

The R17C P0/P1 export includes full source text, Evidence sidecar, model/runtime diagnostics,
time to first emission, generation ms, native backend, peak and after-close PSS, exact
generated output, and prompt SHA-256. Compare with the original Stage 17.3 MAP/REDUCE
diagnostic and the independently supplied Gallery text.

Human evaluation: build a fixed set of ~20 independently source-confirmed important facts
across original transcript (numbers, proper names, claims, examples, risks, negation,
conclusions, final questions). Score distinct supported facts retained, missing, repeated,
inaccurate, and hallucinated. The provisional promotion threshold is >=18/20 retained,
zero critical invented claims, all critical risks/conclusions retained; numbers are a
quality rubric, **not** automated proof or a CI assertion.

Stop further native runs on OOM, native crash, process restart, ANR,
RELEASE_UNCONFIRMED, or exceptional peak PSS. Do not retry in a possibly poisoned session.

## Automated gate

- Test whole snapshot text, stable lineage and original digest reporting.
- Assert same complete prompt for all three tiers and CPU/no-schema configurations.
- Assert preflight rejection of a prompt that does not fit 4K.
- Assert report contains source text, source identifiers, raw model output and Evidence sidecar.
- Android PR CI and Stage 17.3 QA diagnostic APK workflow must pass before real-device QA.

## Boundaries

- Current production Direct/MAP/REDUCE/FINAL, default 4K/1024 structured CPU path unchanged.
- No Room schema/migrations, no product version overwrite, no transcription editing,
  no model-channel, backend-policy or theme changes.
- QA is available only under the existing ".qa" Stage 17 P1 entry gate.
- PR #37 remains Draft. **DO NOT MERGE** without explicit user authorization.
- P2 0.18.0/0.18.1 GPU comparative tests and P3 formal structured integration are
  separate gates, NOT authorized by passing P0/P1 alone.
