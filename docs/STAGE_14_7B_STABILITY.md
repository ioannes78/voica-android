# Stage 14.7B — Real-device 30 / 60 / 120 minute stability

Status: **IN PROGRESS / B1+B2 PASS / B3 PENDING / FINAL FREEZE BLOCKED**

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

This does not invalidate the B1 operational-stability result.

## B2 — 60-minute real-device run

Result: **PASS**

User-reported real-device evidence on 2026-10-10:

- audio duration: `64:27` (`3867 s`)
- SenseVoice processing time: `8:00` (`480 s`)
- SenseVoice RTF: `0.1241`
- SenseVoice throughput: approximately `8.06x realtime`
- speaker diarization processing time: `27:30` (`1650 s`)
- speaker diarization RTF: `0.4267`
- speaker diarization throughput: approximately `2.34x realtime`
- sequential SenseVoice + diarization processing time: `35:30` (`2130 s`)
- sequential processing RTF: `0.5508`
- background / screen-off behavior: **PASS / normal**
- crash: **none observed**
- ANR / hang: **none observed**
- OOM: **none reported**
- playback / search / export: **PASS / normal**
- result persistence: **PASS / normal**
- other anomalies: **none reported**

B1 → B2 scaling:

- audio-duration ratio: approximately `2.09x`
- SenseVoice processing-time ratio: approximately `1.94x`
- speaker-diarization processing-time ratio: approximately `2.09x`
- normalized SenseVoice RTF improved by approximately `7.5%`
- normalized diarization RTF was effectively unchanged (approximately `0.2%` lower)

Interpretation:

- SenseVoice did not show long-duration performance degradation between B1 and B2; normalized throughput slightly improved.
- Speaker diarization scaled almost exactly with audio duration and retained essentially the same RTF as B1.
- No crash, ANR, hang, task-loss, persistence, playback, search, export, background or screen-off failure was observed.
- Therefore B2 is accepted as a real-device operational-stability PASS.

## Canonical WAV performance observation

Separately from ASR/diarization, the user reported that, for the same source format, generating the standard/canonical WAV for an approximately one-hour audio file took roughly `3x` the time observed for an approximately half-hour file, while source duration was only about `2x` longer.

- exact canonical-generation wall-clock times were not captured for B1/B2, so no formal RTF can be calculated for this stage.
- the observation is treated as a **performance watch item**, not an operational-stability failure, because canonical generation completed and no crash/hang/data-loss behavior was reported.
- current evidence suggests the nonlinear behavior is localized to canonical preprocessing rather than SenseVoice or speaker diarization, because B1→B2 ASR/diarization scaling remained linear or better.
- B3 should continue to observe canonical-generation behavior; do not change the tested binary before B3.

## Resource telemetry for B2

No external ADB telemetry values were supplied for this run:

- RAM / PSS peak: `N/A — not instrumented for B2`
- CPU utilization: `N/A — not instrumented for B2`
- thermal status / temperature: `N/A — not instrumented for B2`
- storage delta / free-space telemetry: `N/A — not instrumented for B2`
- canonical WAV exact generation time: `N/A — qualitative slowdown reported, exact timing not captured`

This does not invalidate the B2 operational-stability result, but B3 should capture resource/canonical timing observations where practical.

## Remaining run

### B3 — 120 minutes

Pending.

Use the same production-signed APK. Minimum evidence:

- actual audio duration
- canonical WAV generation time if applicable
- SenseVoice processing time / RTF
- speaker diarization processing time / RTF
- background / screen-off recovery
- crash / ANR / OOM observation
- persistence / playback / search / export result
- thermal / resource observations where practical

B3 is the final long-duration release-stability gate.

## Final acceptance rule

Stage 14.7B remains **IN PROGRESS** until B3 is completed and accepted. PR #24 must remain Draft/Open. Do not merge and do not create Stage 14 Final Freeze/Handoff yet.
