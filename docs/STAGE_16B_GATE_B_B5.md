# Stage 16B Gate B — B5 Isolated Cold/Warm Performance + Memory

Status: **PLANNED / IMPLEMENTATION NEXT**

Date: 2026-10-20

B4 handoff: **REAL-DEVICE PASS**. Corrected B4 QA interpretation checkpoint: `9e62acbfb179eb09fa5227d640ef0261765ce4c0`; Fast PR Gate #2031 PASS. B4 production alignment behavior was not changed.

## Objective

B5 measures Android-device runtime cost without the cross-model PSS contamination observed in earlier same-process exploratory runs. B5 is not another accuracy matrix: B1–B4 already own quality/capability evidence.

## Candidates retained for B5

All three candidates remain because each still owns a materially different product-role signal:

- **SenseVoice INT8** — low runtime-cost signal, strongest compact ITN formatting, full native token timing;
- **Fun-ASR-Nano INT8** — strongest mixed/proper-name tendency, active ITN, strongest supported hotword A/B, alignment-compatible candidate path;
- **Qwen3-ASR 0.6B INT8** — strongest long-Mandarin evidence and native punctuation, existing high-quality product role.

No additional model is added. B5 is needed before deciding whether any candidate is dominated enough to skip B6.

## Isolation contract

The three candidates must never run sequentially in the same Android process for formal B5 evidence.

The QA implementation will use a **separate Android process per candidate**. Each candidate process performs exactly one formal session containing:

1. a cold engine-open + transcription pass;
2. engine close;
3. a warm engine-open + transcription pass on the exact same frozen PCM inputs.

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

Use one representative 1–3 minute real-speech recording with several frozen ASR inputs. B6 owns long-file endurance.

## Measurements

For each candidate process record process/session identity, model identity, recording/canonical SHA, FSMN identity, effective threads and process baseline PSS.

For both **cold** and **warm** passes record:

- engine-open wall time;
- ASR-only wall time;
- engine-open + ASR total wall time;
- ASR call / blank-call counts;
- frozen speech duration;
- ASR RTF and total candidate RTF;
- start / peak / end process PSS;
- crash/OOM status.

Warm means repeated use in the same candidate-only process after closing the cold engine. It is not a claim that every native/OS cache was reset.

## PSS rules

- use `Debug.getPss()` for the benchmark process;
- sample peak PSS at a fixed short interval during engine open + transcription;
- never compare formal candidate PSS if candidates shared a process;
- do not force GC as a ranking mechanism;
- record start/peak/end rather than peak alone;
- label values as process PSS, not exact model heap ownership.

## Timing rules

- use monotonic elapsed realtime;
- RTF denominator is exact frozen **speech input duration**, not full recording duration;
- VAD preparation is reported separately and excluded from candidate ASR RTF;
- B4 alignment cost is excluded from B5 measured ASR time and is carried separately into the final Product Matrix.

## Formal output

| Candidate | Pass | Engine open | ASR wall | ASR RTF | Total wall | Total RTF | Start PSS | Peak PSS | End PSS | Calls / blank | Result |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |

## Interpretation / B6 reduction

B5 does not select a universal winner by lowest RTF alone. The final decision combines B1/B2 quality, B3 capabilities, B4 timing behavior and B5 runtime cost.

After B5:

- keep candidates that remain materially differentiated by quality/capability/runtime role;
- remove a candidate from expensive B6 if another dominates it on its relevant role without a compensating feature;
- carry close candidates to B6;
- do not automatically run B6 for all three.

## Frozen non-changes

B5 does not merge PR #35 or model-channel PR #21, modify `main`, promote FunASR, remove current production ASR models, change Room v14, change Local File `FSMN -> Silero`, change realtime Silero, change canonical `sampleIndex` ownership, or start Stage 16C.

## Next

Implement the QA-only isolated-process B5 harness, add regression/manifest checks, pass Fast PR CI, then generate one production-like QA APK for the three candidate sessions.