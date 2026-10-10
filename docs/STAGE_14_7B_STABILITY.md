# Stage 14.7B — Real-device 30 / 60 / 120 minute stability

Status: **ACCEPTED / B1+B2+B3 PASS / STAGE 14.7B COMPLETE / FINAL FREEZE PREPARATION READY**

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

## B3 — 120-minute real-device run

Result: **PASS**

User-reported real-device evidence on 2026-10-10:

- audio duration: `121:07` (`7267 s`)
- SenseVoice processing time: `16:20` (`980 s`)
- SenseVoice RTF: `0.1349`
- SenseVoice throughput: approximately `7.42x realtime`
- speaker diarization processing time: `55:30` (`3330 s`)
- speaker diarization RTF: `0.4582`
- speaker diarization throughput: approximately `2.18x realtime`
- diarization / SenseVoice processing-time ratio: approximately `3.40x`
- sequential SenseVoice + diarization processing time: `71:50` (`4310 s`)
- sequential processing RTF: `0.5931`
- background / screen-off behavior: **PASS / normal**
- crash: **none observed**
- ANR / hang: **none observed**
- OOM: **none reported**
- playback / search / export: **PASS / normal**
- result persistence: **PASS / normal**
- other anomalies: **none reported**

B2 → B3 scaling:

- audio-duration ratio: approximately `1.88x`
- SenseVoice processing-time ratio: approximately `2.04x`
- speaker-diarization processing-time ratio: approximately `2.02x`
- SenseVoice RTF changed from `0.1241` to `0.1349`
- speaker-diarization RTF changed from `0.4267` to `0.4582`

B1 → B3 scaling:

- audio-duration ratio: approximately `3.93x`
- SenseVoice processing-time ratio: approximately `3.95x`
- speaker-diarization processing-time ratio: approximately `4.22x`

Interpretation:

- SenseVoice remained strongly faster than realtime at approximately `7.42x` realtime and showed no operational failure at the 121-minute duration.
- Speaker diarization remained faster than realtime and completed successfully; normalized RTF rose modestly versus B1/B2 but did not show runaway nonlinear degradation.
- Across B1/B2/B3, both ASR and diarization remained broadly proportional to source duration, with no crash, ANR, hang, task-loss, persistence, playback, search, export, background or screen-off failure.
- Therefore B3 is accepted as the final real-device long-duration operational-stability PASS.

## Canonical WAV performance observation

Separately from ASR/diarization, the user reported that, for the same source format, generating the standard/canonical WAV for an approximately one-hour audio file took roughly `3x` the time observed for an approximately half-hour file, while source duration was only about `2x` longer.

- exact canonical-generation wall-clock times were not captured for B1/B2/B3, so no formal canonical-generation RTF can be calculated for Stage 14.7B.
- the observation remains a **known non-blocking performance watch item** because canonical generation completed and no crash/hang/data-loss behavior was reported.
- current evidence indicates the observed nonlinearity is not reproduced in SenseVoice or speaker diarization processing, whose B1/B2/B3 scaling remained broadly proportional to source duration.
- optimize canonical preprocessing in a later performance stage rather than changing the accepted Stage 14 release binary after successful 30/60/120-minute stability validation.

## Resource telemetry

No external ADB telemetry collector was used for these user-run acceptance tests, so quantitative system-resource values are unavailable.

### B2

- RAM / PSS peak: `N/A — not instrumented`
- CPU utilization: `N/A — not instrumented`
- thermal status / temperature: `N/A — not instrumented`
- storage delta / free-space telemetry: `N/A — not instrumented`

### B3

- RAM / PSS peak: `N/A — not instrumented`
- CPU utilization: `N/A — not instrumented`
- thermal status / temperature: `N/A — not instrumented`
- storage delta / free-space telemetry: `N/A — not instrumented`
- canonical WAV exact generation time: `N/A — exact wall-clock timing not captured`

Unavailable telemetry is explicitly recorded as N/A rather than inferred. The operational evidence still covers the release-critical outcomes: successful completion, background/screen-off behavior, crash/ANR/hang observations, persistence, playback, search and export across real 30/60/120-minute audio runs.

## Final Stage 14.7B acceptance

Stage 14.7B is **ACCEPTED**.

All three real-device duration gates passed on the same accepted production-signed V1 RC binary:

| Gate | Audio | SenseVoice RTF | Diarization RTF | Operational result |
| --- | ---: | ---: | ---: | --- |
| B1 | 30:48 | 0.1342 | 0.4275 | PASS |
| B2 | 64:27 | 0.1241 | 0.4267 | PASS |
| B3 | 121:07 | 0.1349 | 0.4582 | PASS |

Acceptance basis:

- production-signed binary identity remained unchanged throughout B1/B2/B3;
- 30-, 60-, and 120-minute-class real audio all completed SenseVoice and speaker-diarization processing successfully;
- background / screen-off behavior remained normal;
- no crash, ANR, hang or reported OOM occurred;
- playback, search, export and result persistence remained normal;
- no data-loss or recovery blocker was reported;
- canonical-WAV generation speed is retained as a non-blocking performance follow-up because exact timings were not captured and no correctness/stability failure accompanied it.

Stage 14.7B no longer blocks Final Freeze/Handoff preparation. PR #24 should remain Draft/Open until the Stage 14 Final Freeze/Handoff artifacts and final CI/release checks are completed. Do not merge solely on the basis of this acceptance document.
