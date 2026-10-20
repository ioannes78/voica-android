# Stage 16B Gate B — B0 Candidate Readiness

Status: **REAL-DEVICE PASS / B1 NEXT**

## Scope

Gate B compares the current Local File ASR candidates without changing product selection:

1. `sensevoice-2024-int8`
2. `funasr-nano-int8`
3. `qwen3-asr-0.6b-int8`

The formal comparison baseline is frozen to `fsmn-vad-int8`. A run that resolves or falls back to Silero is invalid for Gate B.

## B0 harness contract

The QA-only B0 harness intentionally bypasses the product `TranscriptionCoordinator` for benchmark execution:

1. Resolve active `fsmn-vad-int8` directly. Do not accept the Local File Silero availability fallback.
2. Run FSMN exactly once against the canonical PCM source.
3. Freeze the returned canonical `sampleIndex` speech boundaries.
4. Read the same canonical PCM and deterministically split only oversized speech ranges into at most 30 s ASR chunks.
5. Reuse those exact frozen PCM chunks for every ASR candidate.
6. Run candidates in bring-up order: Fun-ASR-Nano → SenseVoice → Qwen3-ASR.
7. Do not run external punctuation or Qwen timeline alignment in B0. Those are measured separately in later Gate B phases.
8. Do not persist benchmark transcripts or benchmark state to Room.

B0 is intentionally limited to recordings up to 120 s. Representative long-file work remains B6.

## Evidence captured per candidate

- model id/version/revision
- canonical SHA-256 identity from existing canonical lineage
- FSMN model identity and shared VAD wall time
- frozen speech/ASR-input boundary counts
- engine-open wall time
- ASR call count
- blank-call count
- raw ASR text
- detected languages when the runtime exposes them
- ASR wall time
- end-to-end benchmark wall time / RTF
- starting and peak PSS
- managed runtime exception text

A native process crash remains externally observable as an APK/process termination and therefore fails B0 even if no in-process exception can be recorded.

## QA entry

`Stage16BGateBBenchmarkActivity` is compiled once but declared as a launcher only by the `qa` and `releaseQa` manifest overlays. Production `release` has no Gate B launcher declaration.

The QA activity lists recent local recordings and starts B0 directly. Candidate download, integrity verification, installation and activation stay in the existing Model Manager; the benchmark does not duplicate model-management UI.

## Automated checkpoint

Verified B0 code checkpoint: `a27ed998db56e8edd71c17ff50303285ae3453bf`.

- Android PR CI #1945 / Fast PR Gate: **AUTOMATED PASS**
- unit tests + debug compile: **PASS**
- FSMN candidate runtime boundary: **PASS**
- Android PR CI #1946 / production-like QA APK Gate: **AUTOMATED PASS**
- `assembleReleaseQa`: **PASS**
- QA APK verification/security/model-channel checks: **PASS**
- QA artifact ID: `12245362192`
- QA artifact digest: `sha256:07c976c4aa38a3064e6e7e6628bea5a53107c81f595c6b773bde6299f918aa34`
- QA artifact head: `a27ed998db56e8edd71c17ff50303285ae3453bf`

## Real-device evidence — 2026-10-20

User-operated production-like `releaseQa` B0 run: **REAL-DEVICE PASS**.

Formal shared baseline:

- VAD: `fsmn-vad-int8@1.0.0/1`
- VAD wall: `1026 ms`
- FSMN speech segments: `17`
- frozen ASR inputs: `17`
- the same 17 frozen PCM inputs were reused for every ASR candidate

Candidate readiness observations:

| Candidate | Result | Engine open | ASR calls | Blank calls | ASR wall | Total wall | RTF | Start PSS | Peak PSS |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Fun-ASR-Nano INT8 | READY | 15507 ms | 17 | 0 | 29913 ms | 46446 ms | 0.5176 | 120567 KB | 1778348 KB |
| SenseVoice INT8 | READY | 3272 ms | 17 | 1 | 3566 ms | 7864 ms | 0.0876 | 89579 KB | 583185 KB |
| Qwen3-ASR 0.6B INT8 | READY | 8275 ms | 17 | 1 | 36454 ms | 45755 ms | 0.5099 | 78292 KB | 1681547 KB |

Interpretation boundary:

- All three candidates created their native recognizer and produced nonblank overall transcription without process crash.
- Fun-ASR-Nano therefore completes the candidate manifest → install/activate → native recognizer → real-audio transcription bring-up chain required by B0.
- SenseVoice and Qwen each had one blank chunk, but both produced substantial nonblank overall output and satisfy the B0 readiness contract. Blank-chunk frequency is carried forward as a B1/B2 quality metric rather than treated as a B0 failure.
- The large RTF/PSS differences are useful early signals only. B0 is not a performance ranking gate; controlled performance/memory conclusions remain B5.
- Raw transcript differences are not scored in B0 because this run has no frozen reference transcript. Accuracy, deletion/insertion, repetition and hallucination decisions begin in B1.
- SenseVoice surfaced runtime language tags in raw output; language-tag cleanup/detection semantics are deferred to B3 and are not a B0 failure.

## B0 decision

**PASS.** All three Gate B candidates are eligible to enter B1 Same-input Base Quality. B0 selects no production winner and authorizes no ASR promotion or removal.

## Frozen product state

This work does not:

- merge PR #35 or model-channel PR #21;
- modify `main`;
- add Fun-ASR-Nano to production offline ASR choices;
- remove SenseVoice or Qwen3-ASR;
- change Room v14;
- change realtime Silero VAD;
- change Local File production FSMN → Silero availability fallback;
- change canonical `sampleIndex` ownership;
- change Stage 16A.2 annotation, playback, Android Audio I/O or QS668/CB08 ownership;
- start Stage 16C.

## Next gate

Proceed to **B1 Same-input Base Quality** using a small frozen reference corpus. B1 must keep the same exact-FSMN/frozen-input fairness contract and measure raw ASR quality before downstream punctuation/timeline enrichment.
