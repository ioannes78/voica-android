# Stage 14.6 — V1 Release Candidate

Status: **RC CI PASS / REAL-DEVICE ACCEPTANCE PENDING / FINAL FREEZE BLOCKED**

This record prepares the final V1 release-candidate gate. It is **not** the Stage 14 Final Freeze/Handoff and it does not claim that a signed production release exists.

## 1. Scope

Stage 14.6 is a release-candidate convergence slice only. It does not add product functionality and does not change the accepted Stage 14.4.1 product behavior.

The candidate identity remains intentionally unchanged:

- versionCode: `87`
- Release versionName: `1.0.0-rc3-r2`
- production-like QA versionName: `1.0.0-rc3-r2-export-qa`
- Release applicationId: `io.github.ioannes78.voica`
- ReleaseQa applicationId: `io.github.ioannes78.voica.qa`
- ABI: `arm64-v8a`
- Room schema: `12`

No version bump is introduced merely to create another RC label. The objective is to verify and accept the already stabilized V1 binary contract instead of creating unneeded release churn.

## 2. Verified repository baseline before Stage 14.6 changes

Re-verified on 2026-10-10 before this slice:

- `main`: `36c25e4f0de8c515b4d950df8230e601590917ce` — Stage 13C Final
- Stage 14 branch: `stage14-v1-release-freeze`
- Stage 14 branch head before this slice: `f69226a52048f6ff2e40e90fa7a4f08c61be63cd`
- PR: `#24` — OPEN / Draft
- Android PR CI on that head: `#1144` / run `38038862015` — SUCCESS
- Android Full Release Gate on that head: run `#4` / id `38038862024` — SUCCESS
- Room committed lineage: `1..12`
- current Room schema: `12`

## 3. Frozen V1 model supply-chain contract

Stage 14.6 does not modify or promote the production model channel.

The V1 release candidate remains pinned to:

- production model-channel commit: `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- manifest version: `7`
- manifest digest: `8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

Mutable `main` model URLs remain prohibited for the frozen V1 production catalog.

## 4. Automated V1 RC source contract

Stage 14.6 adds:

- `ci/verify-v1-rc-source-contract.sh`

The Full Release Gate executes this check before toolchain setup/build so release-contract drift fails fast.

The gate verifies at source level:

- versionCode / Release / ReleaseQa identity remains the accepted v87 contract;
- Release remains non-debuggable with R8 and resource shrinking enabled;
- ABI remains arm64-v8a;
- Room schemas `1..12` are present and no schema greater than v12 has silently appeared;
- production model-channel commit / manifest version / digest remain frozen;
- mutable production-model `main` tracking is absent;
- Stage 13B, Stage 13C and Stage 14.3–14.5 evidence records required by V1 RC are present.

Artifact-level identity, signing, security, R8, Sherpa JNI/native packaging, Room and provenance verification continue to run through the existing Stage 14.5 Full Release Gate.

## 4.1 Stage 14.6 CI evidence

Stage 14.6 source candidate:

- source head: `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`
- commit: `Stage 14.6 prepare V1 release candidate gate`

The same source head passed both required workflows:

- Android PR CI: `#1145` / run `38040238655` — **SUCCESS**
- Android Full Release Gate: `#5` / run `38040238633` — **SUCCESS**

The Full Release Gate verified successfully:

- V1 RC source contract;
- full unsigned Release surface build;
- R8 outputs;
- ReleaseQa application identity and stable QA signer;
- unsigned production Release APK identity;
- production Release AAB structure / identity contract;
- security / privacy / frozen model-channel boundary;
- Sherpa JNI ABI and native packaging;
- committed Room v1..v12 provenance;
- production signing configuration guardrails;
- unsigned-production-artifact boundary;
- generated release provenance;
- releaseQa / unsigned APK / unsigned AAB / provenance artifact upload.

Generated provenance for source head `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`:

- Release APK SHA-256: `a5160e9a9047467d41191018188f9bb80e20dbd33001a31fdb269be641e759ed`
- Release APK size: `30,639,079 bytes`
- Release AAB SHA-256: `41de2dccb76005edc6d7cda51491885560bf81269f1d6eeb4816d9ec19089a74`
- Release AAB size: `25,589,050 bytes`
- ReleaseQa APK SHA-256: `48a0b1b5a41f415c71bbb21c9ab9bc7dd94dd277b8b2e5a37d35e408fce737cc`
- ReleaseQa APK size: `30,651,379 bytes`
- production signed: `false`
- production certificate: `UNPROVISIONED`

Full Release Gate artifact records:

- `Voica-release-gate-releaseqa-apk`: artifact `11666115140`, ZIP digest `sha256:a25708e2f685dd4cb54cfe58cae7875c4b384bdde018bc0a8930134d07306c98`
- `Voica-release-UNSIGNED-apk`: artifact `11665244879`, ZIP digest `sha256:79af79cd5b274a0e6c5aa909c26335d3e49ffc46ce336a5358b72dbbe6b6d379`
- `Voica-release-UNSIGNED-aab`: artifact `11665294770`, ZIP digest `sha256:eb6ff3e96ab7d5b74b317a39085d35293fbf042ebabad9e5155021f64c504399`
- `Voica-release-provenance`: artifact `11665855574`, ZIP digest `sha256:36cbc2c9feb9b62fa5e8d2f0fbec327a792cd45248d75faa37baa92ee32dea00`

The GitHub PR workflow checkout/build commit may be a generated PR merge SHA. `source_head_sha` in release provenance is the authoritative RC source identity and remains `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`.

## 5. Inherited accepted evidence

Stage 14.6 relies on already accepted evidence only where no product/runtime behavior has changed:

- Stage 13B Final Freeze/Handoff — durable task lifecycle, background/process recovery and final playback notification gate;
- Stage 13C Final Freeze/Handoff — current diarization chain, one-speaker Fast Path, ASR/diarization lifecycle decoupling and Room v12;
- Stage 14.3 — R8/resource shrink/Sherpa JNI/native trim real-device acceptance;
- Stage 14.4 — security/privacy/data/model-supply-chain acceptance;
- Stage 14.4.1 — export UX and v87 real-device acceptance;
- Stage 14.5 — independent full Release APK/AAB/releaseQa build and provenance gate.

CI success does not replace real-device acceptance.

## 6. Final Freeze blockers that must not be fabricated

### 6.1 Production signer — BLOCKED

Stage 14.6 release provenance states:

- `production_signed=false`
- `production_cert_sha256=UNPROVISIONED`

The production private key remains external to the repository. A final production APK/AAB cannot be claimed until the owner provisions the first production signer on a trusted machine and the resulting certificate identity is recorded.

### 6.2 30 / 60 / 120 minute real-device stability evidence — BLOCKED

The Stage 14 roadmap explicitly requires 30min / 1h / 2h stability evidence. Current repository records preserve the requirement and accepted functional QA, but do **not** contain a sufficiently auditable final quantitative record for all three real-device durations covering the expected long-run observations such as RTF, RAM/PSS, CPU, thermal, storage and crash/recovery behavior.

Therefore Stage 14 Final Freeze must remain blocked until that evidence is either:

1. located in an authoritative existing repository record and referenced precisely here; or
2. executed and recorded as a dedicated Stage 14 RC stability run.

Virtual 30/60/120-minute timeline tests must not be substituted for real-device soak evidence.

### 6.3 Stage 14.6 RC real-device acceptance — PENDING

The production-like ReleaseQa APK for source head `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`, SHA-256 `48a0b1b5a41f415c71bbb21c9ab9bc7dd94dd277b8b2e5a37d35e408fce737cc`, must receive explicit user acceptance before Stage 14.6 is accepted.

Because Stage 14.6 changes release gating only, the RC smoke does not need to repeat every previously accepted deep regression unless the RC exposes a regression. It must at minimum confirm that the final candidate installs/upgrades and that critical V1 paths remain operational.

## 7. Stage 14.6 RC smoke checklist

Use the production-like `releaseQa` APK from Stage 14.6 source head `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`.

Minimum candidate smoke:

- install/upgrade over the existing Stage 14 QA package without data loss;
- app launches and Recording Library loads normally;
- BLE scan/connect/device state and one recording/download path work;
- playback / Mini Player / close-notification behavior works;
- SenseVoice default local ASR completes on a known short file;
- Qwen3 high-quality local ASR can be selected and completes on a suitable known file/model installation;
- diarization AUTO works on a known multi-speaker file;
- explicit `1人` Fast Path works on a known single-speaker file;
- completed ASR text remains usable while diarization is still running;
- AI Summary can be generated from a completed transcription using an already configured provider;
- unified search opens the correct filename/transcript/summary target and return context remains correct;
- audio/transcript/summary export works for system default and, when configured, the persisted custom SAF folder;
- restart/reopen does not corrupt Current/Candidate/History state.

Any failure that points to product behavior invalidates the assumption that Stage 14.6 is release-gate-only and must be investigated before acceptance.

## 8. Dedicated 30 / 60 / 120 minute evidence record template

For each duration (`30m`, `60m`, `120m`) record at minimum:

- device / Android version / app version and exact APK SHA-256;
- audio duration / source type;
- selected ASR model and parameter profile;
- diarization mode and speaker-count mode;
- ASR total time and RTF;
- diarization total time and RTF when applicable;
- peak RAM/PSS or the best available repeatable memory observation;
- CPU / thermal observation;
- storage change / free-space condition;
- background / screen-off / process-recovery observations when exercised;
- crash / ANR / OOM result;
- final PASS/FAIL and anomaly notes.

If a measurement cannot be obtained reliably, record it as `N/A` with the reason; do not invent a number.

## 9. Stage 14.6 acceptance rule

Current gate state:

1. Stage 14.6 source head passes Android PR CI — **PASS**;
2. the same source head passes Android Full Release Gate — **PASS**;
3. releaseQa artifact provenance/hash recorded — **PASS**;
4. explicit RC real-device smoke acceptance — **PENDING**.

Therefore Stage 14.6 is **CI-complete but not yet user-accepted**.

Even after Stage 14.6 acceptance, **Stage 14 Final Freeze/Handoff remains blocked** until the production signer and required auditable 30/60/120-minute real-device stability evidence are resolved.

Do not merge PR #24 and do not create Stage 14 Final Freeze/Handoff merely because CI is green.
