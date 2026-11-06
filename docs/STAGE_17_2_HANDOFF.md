# Stage 17.2 — Offline Summary V1 Handoff

Status: **READY FOR STAGE 17.3 REQUIREMENTS / PLANNING**

Repository: `ioannes78/voica-android`

Production branch: `main`

Development branch: `stage17-fully-offline-intelligence`

Pull request: `#37 — Stage 17 — Fully Offline Intelligence`

PR policy: **DRAFT / OPEN / DO NOT MERGE WITHOUT SEPARATE EXPLICIT USER AUTHORIZATION**

Stage 17.2 product / QA evidence freeze HEAD:

`fa075301adf22ae22803715673cafe9cb26587aa`

Stage 17.2 closure status:

**CLOSED / PASS WITH PRODUCT CONTAINMENT / STRUCTURED GPU REQUALIFICATION DEFERRED**

---

## 1. What Stage 17.3 may assume

Stage 17.3 may treat the following Stage 17.2 capabilities as established dependencies:

- application-scoped Local LLM runtime foundation from Stage 17 P1;
- managed Gemma 4 E2B model lifecycle;
- direct offline summary coordinator and persistence path;
- current effective transcription input resolution;
- structured transcript/evidence-ref input contract;
- strict structured summary codec and evidence mapping;
- bounded one-time local repair behavior;
- existing AiSummary persistence/version/current/candidate lifecycle;
- existing summary UI/search/copy/share/export/evidence playback integration;
- cancellation/interruption/background ownership established by Stage 17.2 QA revisions;
- direct-budget fail-closed behavior;
- no-cloud-fallback product boundary.

Stage 17.3 must reuse these foundations rather than introducing parallel runtime, persistence or playback ownership.

---

## 2. Frozen Stage 17.2 contracts to carry forward

### Runtime/model

- LiteRT-LM Android `0.18.0`
- `litert-community/gemma-4-E2B-it-litert-lm`
- revision `7fa1d78473894f7e736a21d920c3aa80f950c0db`
- artifact `gemma-4-E2B-it.litertlm`
- SHA-256 `ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42`
- Stage 17 P1 R8/JNI keep boundary remains mandatory.

### Structured summary

- prompt version `170204`
- `maxNumTokens=4000`
- `maxOutputTokens=1024`
- `topK=64`
- `topP=0.95`
- `temperature=1.0`
- `speculativeDecoding=false`
- request-specific bounded `responseJsonSchema`
- strict validation/evidence allowlist
- no model-authored authoritative timeline positions

### Direct budget

- maximum estimated prompt tokens: `2528`
- no silent truncation
- over-budget direct summary returns `INPUT_TOO_LARGE_FOR_DIRECT_SUMMARY`

Stage 17.3 may introduce a long-transcript architecture only through its own confirmed requirements and development plan; it must not mutate the Stage 17.2 direct-summary contract implicitly.

---

## 3. Backend handoff rule

The most important runtime constraint carried forward is:

`AUTO + responseJsonSchema -> CPU`

for the current Stage 17.2 structured production path.

Do not interpret this as global GPU removal.

Carry forward these distinctions:

- unconstrained GPU remains part of the Local LLM foundation;
- structured GPU is technically capable of success but is not reliable on the current real device;
- equivalent formal structured-GPU runs have both completed and later ended in foreground `ANR(6)` before first emission;
- explicit structured GPU remains QA-only;
- structured GPU must not be restored to production from a single passing run;
- cross-device requalification is deferred until another device is available.

Detailed evidence and requalification gate:

`docs/STAGE_17_2_R5_CLOSURE.md`

---

## 4. Stage 17.3 allowed scope

The next planned product stage is:

**Stage 17.3 — Long Transcript Intelligence**

It may evaluate an architecture such as:

```text
long transcript
  -> deterministic chunk planning
  -> map
  -> validated intermediate summaries
  -> reduce
  -> checkpoint/recovery
  -> final structured summary
```

This is a planning boundary, not an implementation authorization.

Before Stage 17.3 code changes:

1. re-verify GitHub current state;
2. produce/confirm Stage 17.3 requirements;
3. produce/confirm Stage 17.3 development plan;
4. preserve all Stage 17.2 frozen contracts unless the new requirements explicitly revise them;
5. then implement with automated gates and one-item-at-a-time real-device QA.

---

## 5. Stage 17.3 must not do implicitly

Without an explicit requirements revision, Stage 17.3 must not:

- re-enable structured GPU production execution;
- change LiteRT-LM/model/revision/artifact merely for performance experimentation;
- widen the direct-summary `2528` prompt budget by assumption;
- remove output/safety/estimator reserves;
- silently truncate long transcripts;
- use cloud fallback for local summary failure;
- introduce a second Local LLM Engine owner or runtime coordinator;
- bypass existing summary persistence/version lifecycle;
- invent authoritative evidence timestamps/sample indices in the LLM;
- introduce Room v17;
- promote the candidate Local LLM model to the production model channel;
- merge PR #37.

---

## 6. Structured-GPU deferred work

Structured-GPU requalification is a separate deferred QA line, not a Stage 17.3 prerequisite.

When another device becomes available, start from the frozen model/runtime contract and require:

- B1-equivalent formal GPU/no-schema stability;
- B2-equivalent small-prompt/formal-schema stability;
- B3-equivalent formal-prompt/formal-schema stability;
- repeated same-process structured GPU generation;
- confirmed `ENGINE_CLOSED` after each pass;
- no ANR/restart/release-ownership failure;
- lifecycle/background/lock requalification only after foreground repeat stability.

A device-specific pass does not automatically authorize a global GPU policy change.

---

## 7. Known limitations handed forward

- current-device structured Stage 17.2 production summary remains CPU-contained;
- direct-summary input is bounded and rejects over-budget input;
- long-transcript hierarchy does not exist in Stage 17.2;
- cross-device structured GPU reliability is not yet established;
- SEVERE/CRITICAL thermal behavior is not claimed as real-device covered unless safely reproduced;
- production model-channel promotion remains a separate explicit decision.

---

## 8. Audit references

Primary Stage 17.2 records:

- `docs/STAGE_17_2_OFFLINE_SUMMARY_REQUIREMENTS_V1.md`
- `docs/STAGE_17_2_OFFLINE_SUMMARY_DEVELOPMENT_PLAN_V1.md`
- `docs/STAGE_17_2_QA_REVISION_1_BACKGROUND_EXECUTION.md`
- `docs/STAGE_17_2_QA_REVISION_2_LOCK_SCREEN_SAFE_LOCAL_LLM.md`
- `docs/STAGE_17_2_QA_REVISION_3_SUMMARY_VERSION_LIFECYCLE.md`
- `docs/STAGE_17_2_R5_CLOSURE.md`
- `docs/STAGE_17_2_CLOSURE.md`
- `docs/STAGE_17_2_FREEZE.md`

Important CI evidence:

- product/QA freeze Fast PR Gate: Android PR CI `#2771` / run ID `40522440173` / SUCCESS;
- production-like R5 QA build: Android PR CI `#2768` / run ID `40502230964` / QA APK Gate SUCCESS;
- QA artifact ID `12732879071`, source HEAD `08da6197b8688fa1fcdff97793f038a83acf86c2`, digest `sha256:26296d4f7e273a1111117cf54f541a54c9735c11d2c4dcc92df0883cf23d11a1`.

---

## 9. Handoff decision

Stage 17.2 is handed off as a frozen dependency.

Next formal action:

**Stage 17.3 requirements and development planning only.**

Stage 17.3 implementation is not auto-authorized by this handoff.

PR #37 remains:

**DRAFT / OPEN / DO NOT MERGE**

No merge, production model promotion or structured-GPU restoration is authorized by this handoff.
