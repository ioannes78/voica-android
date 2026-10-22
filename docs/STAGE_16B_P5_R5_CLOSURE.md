# Stage 16B — P5 revision-5 Closure

Status: **P5 R5 CLOSED / REAL-DEVICE PASS / STAGE 16B FINAL FREEZE NOT PERFORMED**

Date: 2026-10-22

Repository: `ioannes78/voica-android`

Development branch: `stage16b-local-file-asr-v2`

Base `main` at closure verification: `988931ebed72a70ba799d909cd36a416a673eef5`

P5 R5 implementation evidence head: `5fa2e750e5186178198ecc31d53f3ddd2a402b17`

P5 R5 production-like QA trigger head: `9c0046b5d432c45da62b0bc3b286572db72c29ba`

`9c0046b...` is an empty QA trigger commit and has the same source tree as the verified implementation head.

Room remains `v14`.

## 1. Scope

P5 revision-5 closes the post-CTC real-device remediation for:

- FunASR revision-2 same-stream CTC QA observability;
- diarization AUTO clustering/stitching runtime transparency;
- removal of the hidden AUTO clustering-threshold floor;
- fixed-speaker-count independence from AUTO behavior;
- conservative suppression of low-evidence short local-speaker identities that otherwise create a false global speaker;
- per-task FunASR diagnostics reset;
- explicit effective ASR threads / Hotword / adaptive-retry diagnostics;
- real-device regression across FunASR and SenseVoice, AUTO and fixed-two-speaker modes;
- overlap/manual speaker-assignment regression.

R5 does **not** reopen the CTC forced-alignment core algorithm, SenseVoice timestamp semantics, Room schema, production model-channel promotion, or Stage 16B Final Freeze/Handoff.

## 2. Code behavior closed by R5

### 2.1 Diarization tuning

- recommended AUTO clustering threshold: `0.70`;
- cross-window stitching cosine threshold remains `0.75`;
- the user-visible clustering value is the runtime value; there is no hidden AUTO threshold floor;
- fixed speaker-count modes remain independent and pass their explicit expected speaker count to native diarization;
- production AUTO diarization policy remains `4` threads unless explicitly overridden; test-device 2-vs-4 timing differences are not used to change product policy.

### 2.2 Low-evidence short-speaker guard

The regression sample repeatedly produced native per-window local counts `[3, 3, 2]` for a known two-speaker recording because of a very short local identity near the same audio boundary.

R5 does not hard-code AUTO to two speakers and does not discard all short speech. Instead, AUTO global stitching now refuses to create a third-or-later global identity when that local identity lacks a usable anchor and lacks the existing minimum anchor evidence (`stitchingMinimumAnchorSamples=16000`, one second at 16 kHz).

Consequences verified on device:

- strong third-or-later speakers remain eligible once sufficient evidence exists;
- fixed 1/2/3/4/5+ modes are unaffected by this AUTO guard;
- the pathological short fragment becomes `说话人未确定` instead of inventing a third global speaker;
- native local-speaker diagnostics remain visible, so the guard does not hide the underlying model output.

### 2.3 FunASR QA diagnostics

Each new FunASR high-quality task now starts a fresh QA diagnostics session rather than accumulating prior runs.

The Stage 16B FunASR CTC block exposes:

- `effectiveThreads`;
- `hotword=ON/OFF`;
- `adaptiveRetryCount`;
- `retryOccurred`;
- per-run chunk count and total audio duration;
- text decode wall time / RTF;
- CTC alignment wall time;
- total measured wall time / RTF;
- output token count;
- per-chunk audio/decode/alignment/token metrics;
- alignment success and same-stream status.

This is observability only; it does not change the CTC alignment algorithm.

## 3. Final CI / build evidence

Production-like P5 QA workflow run: `39789162602`.

Final Fast PR Gate: **SUCCESS**.

P5 patched sherpa + revision-2 QA APK job: **SUCCESS**.

The successful P5 job includes:

- immutable revision-2 candidate-manifest verification;
- pinned sherpa-onnx `v1.13.8` verification;
- A0/A1/A2 native CTC patch application;
- arm64 JNI build with ONNX Runtime `1.28.2`;
- patched arm64 AAR packaging;
- patched AAR ABI / diarization JNI / native CTC protocol verification;
- isolated Maven publication and standard dependency-graph resolution;
- unit tests with the patched sherpa dependency;
- production-like `releaseQa` APK build;
- release/security/sherpa package verification;
- QA APK upload.

Artifact:

- id: `12431421759`;
- name: `Voica-P5-CTC-revision2-r3-releaseQa-apk`;
- workflow head: `9c0046b5d432c45da62b0bc3b286572db72c29ba`.

## 4. Real-device acceptance matrix

Fixed recording: `EHBZKFZH6GIZG_[cut_118sec].mp3`, approximately `117.778 s`, known true speaker count = 2.

### 4.1 FunASR + AUTO — PASS

Runtime:

- `expectedSpeakerCount=auto`;
- `clusteringThreshold=0.70`;
- `stitchingCosineThreshold=0.75`;
- `numThreads=4`;
- `chunkSize=60 s`;
- `chunkOverlap=10 s`;
- `pyannoteWindowShiftRatio=0.10`.

Observed:

- native `localSpeakerCounts=[3, 3, 2]`;
- final product speaker count = **2**;
- the previous false third-speaker fragment is retained conservatively as `说话人未确定` instead of creating speaker 3.

FunASR CTC diagnostics for the accepted AUTO run:

- `chunks=9`;
- `audioMs=117778`;
- `textDecodeMs=80726`;
- `textDecodeRtf=0.6854`;
- `ctcAlignmentMs=899`;
- `totalMs=81862`;
- `measuredRtf=0.6951`;
- `outputTokenCount=569`;
- `sameStream=true`;
- 9/9 chunks `alignmentSucceeded=true`;
- `effectiveThreads=2`;
- `hotword=OFF`;
- `adaptiveRetryCount=0`;
- `retryOccurred=false`.

### 4.2 FunASR + fixed 2 — PASS

Observed:

- `expectedSpeakerCount=2`;
- native `localSpeakerCounts=[2, 2, 2]`;
- final product speaker count = **2**;
- no regression from the AUTO weak-speaker guard.

Accepted FunASR run:

- `textDecodeMs=82580`;
- `textDecodeRtf=0.7011`;
- `ctcAlignmentMs=835`;
- `totalMs=83690`;
- `measuredRtf=0.7106`;
- 9/9 alignment succeeded and remained same-stream.

### 4.3 SenseVoice + AUTO — PASS

Observed:

- `expectedSpeakerCount=auto`;
- `clusteringThreshold=0.70`;
- `stitchingCosineThreshold=0.75`;
- native `localSpeakerCounts=[3, 3, 2]`;
- final product speaker count = **2**;
- the same low-evidence short fragment remains unresolved rather than becoming a false third global speaker.

This confirms that the R5 speaker guard is in the shared diarization/stitching path and is not FunASR-specific.

### 4.4 SenseVoice + fixed 2 — PASS

Observed:

- `expectedSpeakerCount=2`;
- native `localSpeakerCounts=[2, 2, 2]`;
- final product speaker count = **2**.

Fixed-speaker behavior remains independent from AUTO clustering/stitching heuristics.

### 4.5 Thread override quality check — PASS / PERFORMANCE NOT PRODUCT EVIDENCE

The same test device was also exercised with diarization `numThreads=2`:

- fixed 2: `localSpeakerCounts=[2, 2, 2]`;
- AUTO: `localSpeakerCounts=[3, 3, 2]` and final two speakers.

No quality regression was observed. However, the user explicitly designated this phone as a test device and requested that device-specific timing variation be ignored. Therefore R5 does **not** change the product AUTO diarization thread policy from 4 to 2 and does not claim a cross-device performance conclusion from these timings.

## 5. R5G overlap / manual assignment — PASS

Real-device regression passed for manually assigning a speaker to an overlap/unresolved span:

- speaker 1/2 can be assigned;
- UI updates correctly;
- the manual assignment persists after leaving and reopening the recording;
- playback / timeline seek remain normal;
- adjacent normal speaker spans are not unintentionally rewritten.

R5G is therefore closed as PASS.

## 6. R5 closure decision

| Area | Decision |
| --- | --- |
| settings/runtime consistency | **PASS** |
| AUTO speaker correctness | **PASS** |
| fixed speaker-count independence | **PASS** |
| FunASR AUTO / fixed-2 regression | **PASS** |
| SenseVoice AUTO / fixed-2 regression | **PASS** |
| low-evidence false-speaker suppression | **PASS** |
| overlap/manual assignment regression | **PASS** |
| FunASR per-task diagnostics reset | **PASS** |
| effective threads / Hotword / retry visibility | **PASS** |
| same-stream CTC alignment on accepted sample | **PASS** |
| device-specific diarization performance tuning | **STOPPED — no product-policy change** |

**P5 revision-5 is CLOSED / REAL-DEVICE PASS.**

The older `STAGE_16B_PROGRESS.md` and `STAGE_16B_POST_GATE_REMEDIATION.md` contain historical in-progress wording from before A3-H2/P5/R5 completion. For P5 R5 status, this closure document is the newer authoritative additive evidence record; the historical documents remain unchanged for traceability.

## 7. Production / repository boundaries after closure

At closure verification:

- Android PR #35 remains **Draft / open / unmerged**;
- model-channel PR #21 remains **Draft / open / unmerged**, head `eeb59214244f392f3e2eecdb90016efbe3a95401`;
- model-channel `main/manifests/production.json` remains production manifest version `7`;
- revision-2 FunASR candidate is **not production**;
- no model-channel promotion is authorized by this closure;
- Room remains `v14`;
- no migration is added;
- no Stage 16B Final Freeze/Handoff is performed;
- no Stage 16C work is started;
- no PR is merged to `main`.

## 8. Stop point / replanning boundary

Work stops after P5 R5 closure.

The user has explicitly requested that subsequent work be **replanned**. Therefore previously reserved or drafted next-stage ordering must not be treated as implementation authorization.

Before any work after this closure:

1. re-read this R5 closure and current GitHub state;
2. wait for the user's revised roadmap / requirements direction;
3. produce the requested revised requirements/planning artifact before creating a new stage branch or starting implementation.

This closure is **not** Stage 16B Final Freeze/Handoff and does not imply approval of any previously reserved next stage.