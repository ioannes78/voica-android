# Stage 16B — Local File ASR V2 Progress

Status: **GATE A TECHNICAL PASS / GATE B REAL-DEVICE PASS / PRODUCT MATRIX AUTHORIZED / POST-GATE REMEDIATION ACTIVE / FINAL FREEZE BLOCKED**

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

## Post-Gate real-device revalidation — current truth

The product-role decision above remains authorized, but post-gate production-like QA found two blockers.

| Item | State |
| --- | --- |
| QA Hub launcher behavior | **PASS** |
| FunASR hotword UI / call path | **PASS** |
| Local Speech settings copy | **PASS** |
| FunASR ITN | **PASS** |
| FunASR precise/native token timeline | **FAIL** |
| FunASR Hotword ON transcription completeness | **FAIL** |
| Same 118-second sample, Hotword OFF completeness | **PASS** |
| Final Freeze / Handoff | **BLOCKED** |

### Corrected FunASR timestamp interpretation

Pinned sherpa-onnx v1.13.8 does return generated token strings and timestamp values, but its FunASR implementation derives those timestamp values from effective injected audio duration and distributes them evenly across the generated token sequence. This is **synthetic uniform timing**, not CTC/forced acoustic alignment.

Accordingly, the post-gate claim that sherpa FunASR timestamps can serve as a precise native product timeline is withdrawn. Historical B4 evidence is not rewritten; this correction applies to the later post-gate adapter promotion attempt.

The synthetic timing may be used temporarily as a diagnostic of effective audio coverage during P1/P2, but it must not be represented as a true CTC timeline. Full removal from canonical token timing/speaker alignment is the next phase after the completeness blocker passes real-device QA.

### Hotword completeness blocker

A fixed 118-second user recording has shown an entire ASR span missing with Hotword ON while the same span returns with Hotword OFF. The strongest current mechanism is FunASR Nano KV/context pressure: hotword prompt tokens consume the same fixed `max_total_len` budget as audio placeholders and generated output.

P1/P2 therefore replace guess-based fixed-duration tuning with explicit context-budget diagnostics and a FunASR-specific adaptive chunk planner.

Full remediation contract: `docs/STAGE_16B_POST_GATE_REMEDIATION.md`.

## Production-state boundaries

- Local File VAD: `FSMN primary -> Silero fallback`;
- realtime VAD: Silero unchanged until Stage 16C.1;
- Local File balanced/efficiency ASR: SenseVoice INT8;
- Local File high-quality ASR: FunASR Nano INT8;
- current FunASR package remains `funasr-nano-int8@2025-12-30/1` for P0–P2;
- model-channel staged manifest remains v9 at Draft PR #21 until separately authorized;
- Room remains v14;
- Android PR #35 remains Draft/unmerged;
- model-channel PR #21 remains Draft/unmerged;
- Stage 16C remains `RESERVED / NOT STARTED`.

## Current work order

1. **P0 — documentation/status correction**: record timeline FAIL, Hotword ON completeness FAIL and Final Freeze BLOCKED.
2. **P1 — FunASR context-budget diagnostics**: capture prompt/audio/generation budget evidence; explicitly distinguish exact fields from estimates/inferences unavailable through the current sherpa Java API.
3. **P2 — adaptive FunASR chunking + retry**: budget-aware 15–18-second target, natural-boundary preference, overlap for continuous speech, fuzzy overlap reconciliation and fail-closed retry.
4. Run automated regression for FunASR planner/retry plus SenseVoice and FAST non-regression.
5. Generate production-like QA APK after CI is green.
6. Real-device gate: fixed 118-second recording Hotword OFF/ON with ITN ON, plus at least two additional long recordings.
7. **Only after explicit P2 real-device PASS**, proceed to P3–P5 true CTC timeline work.
8. Do not Final Freeze/Handoff, merge either PR, promote model-channel, or start Stage 16C without explicit user authorization.
