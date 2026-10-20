# Stage 16B Gate B — B5 Isolated Cold/Warm Performance + Memory

Status: **PLANNED / IMPLEMENTATION NEXT**

Date: 2026-10-20

B4 handoff: **REAL-DEVICE PASS**. Corrected B4 QA interpretation checkpoint: `9e62acbfb179eb09fa5227d640ef0261765ce4c0`; Fast PR Gate #2031 PASS. B4 production alignment behavior was not changed.

## Objective

B5 measures Android-device runtime cost without the cross-model PSS contamination observed in earlier same-process exploratory runs.

B5 is not another accuracy matrix. B1–B4 already own quality/capability evidence. B5 answers whether each remaining product role is practical in cold and warm use.

## Candidates retained for B5

All three candidates remain because each still owns a materially different product-role signal:

- **SenseVoice INT8** — low runtime-cost signal, strongest compact ITN formatting, full native token timing;
- **Fun-ASR-Nano INT8** — strongest mixed/proper-name tendency, active ITN, strongest supported hotword A/B, alignment-compatible candidate path;
- **Qwen3-ASR 0.6B INT8** — strongest long-Mandarin evidence and native punctuation, existing high-quality product role.

No additional model is added to B5. This is already the reduced role-aware matrix; B5 is needed before deciding whether any candidate is dominated enough to skip B6.

## Isolation contract

The three candidates must never run sequentially in the same Android process for formal B5 evidence.

The QA implementation will use a **separate Android process per candidate**. Each candidate process performs exactly one formal session containing:

1. a cold engine-open + transcription pass;
2. engine close;
3. a warm engine-open + transcription pass on the exact same frozen PCM inputs.

This isolates candidate memory while still allowing a cold-vs-warm comparison inside one candidate process.

A candidate process must not run a second formal session without process restart. The QA UI must make this explicit rather than silently treating a reused process as cold.

## Frozen input contract

- exact active `fsmn-vad-int8@1.0.0/1` is mandatory;
- Silero fallback invalidates formal B5 evidence;
- same recording / canonical SHA must be used for all three candidates;
- common maximum ASR input remains **25 seconds**;
- canonical sample-index boundaries remain authoritative;
- no external punctuation;
- no Qwen/FunASR timeline-alignment pass inside the measured ASR section;
- no Room writes;
- no diarization;
- no product model promotion/removal.

B5 should use one representative real-speech recording already proven valid for Gate B, preferably long enough to contain several frozen ASR inputs but short enough to keep the three isolated cold/warm sessions practical. A 1–3 minute mixed-content recording is sufficient; B6 owns representative long-file endurance.

## Measurement phases

Each candidate process records:

### Process baseline

- process/session identity;
- candidate/model identity;
- canonical recording identity;
- exact FSMN identity;
- thread count / effective performance settings;
- process start PSS before candidate engine open.

### Cold pass

- engine-open wall time;
- ASR-only wall time;
- engine-open + ASR total wall time;
- ASR call count / blank call count;
- speech duration represented by frozen inputs;
- ASR RTF;
- total candidate RTF (`engine open + ASR`) against frozen speech duration;
- PSS before open;
- peak PSS during open/transcription;
- PSS immediately after engine close;
- no crash/OOM.

### Warm pass

On the exact same process and frozen PCM inputs after closing the cold engine:

- engine-open wall time;
- ASR-only wall time;
- total wall time;
- ASR/total RTF;
- start/peak/end PSS;
- call/blank counts;
- no crash/OOM.

The warm pass is a practical repeated-use signal, not a claim that all native/runtime caches were fully reset between passes.

## PSS rules

- use `Debug.getPss()` for the benchmark process;
- sample peak PSS at a fixed short interval during engine open + transcription;
- do not compare PSS from different candidates if they shared a process;
- do not force GC as a ranking mechanism;
- record start/peak/end rather than reporting peak alone;
- label all values as process PSS, not model file size or exact heap ownership.

## Timing rules

- use monotonic elapsed realtime;
- separate engine-open from ASR wall time;
- RTF denominator is the exact frozen **speech input duration**, not full recording duration;
- VAD preparation time is reported separately and excluded from candidate ASR RTF because B5's formal comparison starts from the common frozen ASR inputs;
- B4 alignment cost is not included in B5 candidate ASR timing. Timeline-role overhead has already been characterized separately and can be added qualitatively in the final Product Matrix.

## Formal output

For each candidate B5 reports one cold row and one warm row:

| Candidate | Pass | Engine open | ASR wall | ASR RTF | Total wall | Total RTF | Start PSS | Peak PSS | End PSS | Calls / blank | Result |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |

B5 also records the exact common recording/canonical SHA, FSMN identity, frozen input count and frozen speech duration so rows cannot be accidentally compared across different inputs.

## Interpretation

B5 does not select a single universal winner by the lowest RTF alone.

The final Product Matrix must combine:

- B1/B2 quality/completeness;
- B3 punctuation / ITN / language / hallucination / hotword capability;
- B4 timeline capability and alignment overhead/precision mode;
- B5 cold/warm runtime and memory cost.

A model can remain valuable as an efficiency/balanced role even if another model has higher recognition quality. Conversely, a quality leader can be rejected for a phone product role if cold/warm memory or latency is impractical.

## B5 stop / reduction rule

After B5, reduce the matrix before B6:

- keep candidates that remain materially differentiated by quality/capability/runtime role;
- remove a candidate from expensive B6 if another candidate dominates it on the role-relevant dimensions without a compensating feature;
- if two candidates remain close, carry both to B6;
- B6 is not automatically run for all three.

## Frozen non-changes

B5 does not:

- merge Android PR #35;
- merge model-channel PR #21;
- modify `main`;
- promote FunASR;
- remove SenseVoice or Qwen3;
- change Room v14;
- change Local File `FSMN -> Silero` policy;
- change realtime Silero;
- change canonical `sampleIndex` ownership;
- start Stage 16C.

## Next

Implement the QA-only isolated-process B5 harness, add regression/manifest checks, pass Fast PR CI, then generate one production-like QA APK for the three candidate sessions.