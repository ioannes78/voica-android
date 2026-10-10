# Stage 14.7B — Real-device 30 / 60 / 120 minute stability

Status: **IN PROGRESS / B1 30-MIN PASS / B2+B3 PENDING / FINAL FREEZE BLOCKED**

Stage 14.7B records the final real-device long-audio stability evidence required before Stage 14 Final Freeze/Handoff.

The tested application is the accepted Stage 14.7A production-signed V1 RC APK.

## Accepted binary identity

- applicationId: `io.github.ioannes78.voica`
- versionCode: `87`
- versionName: `1.0.0-rc3-r2`
- accepted V1 product source: `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`
- production certificate SHA-256: `f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`
- signed APK SHA-256: `dd5be670d39e1d083aac6e2dce1dec651e6fecacfe027a8d0266bb93e0dafd49`
- signed APK size: `30,651,367 bytes`

## B1 — 30-minute real-device run

Result: **PASS**

User-reported real-device evidence on 2026-10-10:

- audio duration: `30:48` (`1848 s`)
- SenseVoice processing time: `4:08` (`248 s`)
- SenseVoice RTF: `0.1342`
- SenseVoice throughput: approximately `7.45x realtime`
- speaker diarization processing time: `13:10` (`790 s`)
- speaker diarization RTF: `0.4275`
- speaker diarization throughput: approximately `2.34x realtime`
- diarization / SenseVoice processing-time ratio: approximately `3.19x`
- sequential SenseVoice + diarization processing time: `17:18` (`1038 s`)
- sequential processing RTF: `0.5617`
- background / screen-off behavior: **PASS / normal**
- crash: **none observed**
- ANR / hang: **none observed**
- playback / search / export: **PASS / normal**
- result persistence: **PASS / normal**
- other anomalies: **none reported**

Interpretation:

- SenseVoice completed substantially faster than realtime and showed no stability defect.
- Speaker diarization remained substantially slower than SenseVoice, consistent with the known performance characteristic, but still completed in less than half realtime (`RTF < 0.5`) for this sample.
- No crash, ANR, task-loss, persistence, playback, search, export, background or screen-off failure was observed.
- Therefore B1 is accepted as a real-device operational-stability PASS.

## Resource telemetry for B1

The B1 run did not use an external ADB telemetry collector, so quantitative system measurements were not captured:

- RAM / PSS peak: `N/A — not instrumented for B1`
- CPU utilization: `N/A — not instrumented for B1`
- thermal status / temperature: `N/A — not instrumented for B1`
- storage delta / free-space telemetry: `N/A — not instrumented for B1`

This does not invalidate the B1 operational-stability result, but Stage 14.7B final acceptance should include quantitative resource observations in the longer B2/B3 runs where practical.

## Remaining runs

### B2 — 60 minutes

Pending.

Minimum required evidence:

- actual audio duration
- SenseVoice processing time / RTF
- speaker diarization processing time / RTF
- background / screen-off recovery
- crash / ANR / OOM observation
- persistence / playback / search / export result
- resource observations (RAM/PSS, CPU, thermal and storage where available)

### B3 — 120 minutes

Pending.

Use the same production-signed APK and record the same evidence categories. B3 is the final long-duration release-stability gate.

## Final acceptance rule

Stage 14.7B remains **IN PROGRESS** until B2 and B3 are completed and accepted. PR #24 must remain Draft/Open. Do not merge and do not create Stage 14 Final Freeze/Handoff yet.
