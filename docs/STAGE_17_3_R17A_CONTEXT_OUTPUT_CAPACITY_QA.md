# Stage 17.3 — R17A Runtime Context & Output Capacity Feasibility QA

Status: IMPLEMENTED AS QA-ONLY PROBES / REAL-DEVICE NOT YET VERIFIED  
Repository: `ioannes78/voica-android`, branch `stage17-fully-offline-intelligence`  
Production: unchanged. PR #37 remains Draft. No Room migration, no model promotion.

## Why

R16.6 can finish a long local summary, but the user's 8:27 transcript still loses many specific
facts. The question is whether (a) increasing the LiteRT-LM native KV-cache envelope,
(b) allowing a larger output-token budget, and (c) expanding the bounded output schema
will improve actual coverage *on the user's phone* without crashes or unacceptable memory.

The bundled Gemma 4 E2B-IT model architecture supports longer context, but the deployed .litertlm
artifact and Android LiteRT-LM backend may limit real capacity. The application's validated
baseline remains a 4,000-token engine config and 1,024-token output, 2,528-estimated-token
prompt hard budget, and a 4 sections × 2 items × 80 chars production FINAL schema.
Neither Google model specs nor CI establish that 8K/16K works on this device.

Important safety: the production structured path has AUTO+response-format→CPU containment due
to earlier GPU process-restart incidents. These QA probes always use explicit CPU and the
existing one-runtime ownership/resource gate. Do not change the production backend policy.

## Candidate-only runtime matrix

The existing **Stage 17 P1 · Local LLM Runtime** screen is visible only in the .qa application.

Run one test at a time manually, keeping the app in the foreground:

| Button | Engine context | Output cap | Conservative prompt estimate | Purpose |
| --- | ---: | ---: | ---: | --- |
| ① baseline | 4,000 | 1,024 | ~2,200 | Existing native feasibility baseline |
| ② KV same input | 8,000 | 1,024 | Same text as ① | Isolate KV-cache configuration overhead |
| ③ 8K longer input | 8,000 | 1,024 | ~5,000 | Test use of context beyond 4K |
| ④ 16K longer input | 16,000 | 1,024 | ~12,500 | High-risk only if both 8K tests pass |

These are **synthetic texts**, not audio/transcripts, built deterministically in the QA
screen; no user recording is sent to this harness. Every prompt estimate plus 1,024 output
and 192 + 256 reserves must fit the candidate's context. A short model answer must contain
the *last* synthetic record ID; engine initialization alone is not enough to claim PASS.

Capture: model/backend identity, initialize time, conversation-create time, time to first
emission, full generation time, process PSS before/after/peak/close, requested context,
output cap, conservative input estimate, and tail-record recall. Run each candidate at
least twice if feasible to detect nondeterministic native failures.

**Stop conditions:** process restart, memory kill, RELEASE_UNCONFIRMED, model/backend mismatch,
native error, or strongly excessive memory use. After any 8K failure, **do not run 16K**.
The existing R5 Crash Breadcrumb records native phases across process restart.

## Candidate-only output/schema matrix

Use the same sixteen synthetic *distinct* facts in each request.

| Button | Engine context | Max output | QA JSON array cap | Purpose |
| --- | ---: | ---: | ---: | --- |
| ⑤ output baseline | 4,000 | 1,024 | 8 items | Current-level output envelope |
| ⑥ schema only | 4,000 | 1,024 | 16 items | Isolate JSON item-cap impact |
| ⑦ output tokens only | 4,000 | 2,048 | 16 items | Isolate larger decode budget; bundle may reject it |
| ⑧ context only | 8,000 | 2,048 | 16 items | Same prompt/schema/output as ⑦, isolate cache allocation |

The QA-only schema is intentionally simpler than production JSON; this isolates response
capacity before modifying a shared product schema. Only one dimension changes in each
adjacent pair (⑤→⑥, ⑥→⑦, ⑦→⑧). Measure native success, JSON parse,
actual item count, distinct fact IDs / 16, duplicates, unknown IDs and memory/performance.
This measures *identifier retention* only, NOT semantic truth. Check sampled item text
against source facts manually; don't infer data quality from fabricated/ref-labeled ids.

No output schema changes are promoted to the product until the native envelope and content
coverage are established. In particular, a 2,048-token output configured on a model
artifact does not prove its compiled decode settings permit this value.

## Follow-up: real-transcript A/B gate (NOT yet executed)

After 8K and expanded output have passed real-device resource and schema checks, plan a
separately reviewed product experiment on frozen `EHBZKFZH6GIZG.m4a`:

- Hold model revision, ASR/transcript revision, backend, source snapshot, and prompts constant.
- Compare 4K/8K long-summary MAP count and REDUCE depth, then compare 8 vs 16 final items
  without weakening provenance. Do not accidentally conflate both changes in one A/B trial.
- Score the fixed 20-fact rubric (numbers, names, cases, physical-world examples, risk).
- Record exact input characters, conservative tokens, generated items, evidence validity,
  duplicate fact rate, runtime time, peak PSS and crashes.
- Any upgraded production schema requires compatible codec, persistence and export tests;
  a prompt-only change is not sufficient.
- If native 8K fails, retain 4K and evaluate only modular multi-part output at 4K.

## Decision rule

- **Native supported** requires completed generation, correct last-record recall, acceptable
  memory and no crash across repeated manual runs, not just a successful Engine.initialize.
- **Output expansion useful** requires more distinct *correctly expressed* facts, not merely
  more items or repeated references.
- **Product promotion** requires separate PR review, new budget/protocol identity as
  appropriate, complete CI, and real-device long-recording Gate PASS.

Official references:
- https://developers.google.com/edge/litert-lm/android
- https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/Config.kt
- https://ai.google.dev/gemma/docs/core/model_card_4
