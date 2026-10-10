# Stage 14.4 — Security / Privacy / Data / Model Supply Chain

Status: RC3 implementation candidate; not a Final Freeze/Handoff document.

## Scope boundary

Stage 14.4 starts from the real-device accepted Stage 14.3 RC2-R2 package baseline.

This slice changes release security boundaries only:

- disables Android system backup for Voica application data;
- supplies explicit legacy and Android 12+ backup/data-extraction exclusions as defense in depth;
- rejects application cleartext HTTP traffic;
- freezes the V1 production model catalog to the accepted immutable model-channel commit plus exact production manifest version/digest;
- preserves debug-only candidate model-channel override behavior;
- preserves the Stage 14.3 R8/Sherpa JNI/native-trim configuration;
- keeps Room at schema 12;
- does not modify production model-channel contents;
- does not modify transcription, diarization, playback, notification, durable-attention or AI-summary business state machines.

## RC3 identity

- versionCode: `85`
- Release versionName: `1.0.0-rc3`
- production-like QA versionName: `1.0.0-rc3-security-qa`
- Release applicationId: `io.github.ioannes78.voica`
- QA applicationId: `io.github.ioannes78.voica.qa`
- Room: schema 12
- ABI: `arm64-v8a`

## Privacy and local-data boundary

The V1 application manifest now sets:

- `android:allowBackup="false"`;
- `android:dataExtractionRules="@xml/data_extraction_rules"`;
- `android:fullBackupContent="@xml/backup_rules"`;
- `android:usesCleartextTraffic="false"`.

The explicit backup/data-extraction rules exclude app root files, regular files, databases, shared preferences and external app files from both cloud backup and device-to-device transfer paths. The explicit rule files are retained even though `allowBackup=false` is the primary application-level switch, so the intended privacy boundary stays documented and machine-checkable.

This is appropriate for Voica because local recordings, transcript/summary metadata, model-install state and provider configuration must not silently cross devices through Android backup/restore semantics.

## API credentials and FileProvider audit

No new credential-storage mechanism was introduced in RC3.

Existing provider credentials already use Android Keystore backed AES/GCM encryption. Encrypted credential state is stored under `noBackupFilesDir/provider-credentials`, and legacy plaintext credential files are migrated and deleted.

The existing FileProvider remains non-exported and grant-URI based. Its path configuration exposes only the dedicated cache `shares/` directory rather than the application file tree, database directory or model directory.

## Network boundary

V1 rejects application cleartext HTTP traffic at the manifest level. Production model catalogs and model packages remain HTTPS-only. Normal third-party AI provider HTTPS endpoints remain usable; RC3 does not introduce certificate pinning for user-configurable providers because Voica supports multiple provider hosts configured by the user.

## V1 model supply-chain freeze

Before RC3, the production catalog was fetched from mutable model-channel `main`. The catalog format has SHA-256/digest integrity and model packages have package/file SHA-256 verification plus runtime validation, but the production manifest currently has no publisher signature (`signature` / `keyId` are null).

V1 therefore freezes the production model matrix to the accepted immutable Git commit and additionally validates the decoded manifest identity:

- model-channel commit: `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`;
- channel: `production`;
- manifestVersion: `7`;
- manifestDigest: `8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`.

The production URL is commit-pinned and no longer contains `/main/`. A production fetch is rejected if its decoded channel/version/digest does not match those V1 anchors. Debug candidate manifests remain debug-only and are intentionally outside this production pin.

This is a V1 freeze mechanism, not a replacement for a cryptographically signed mutable model channel. A future signed publisher-manifest design would require separate key provisioning, rotation/recovery policy and explicit authorization before changing production model-channel behavior.

## Package-integrity boundary retained

RC3 preserves all accepted Stage 14.3 gates:

- R8/resource shrink;
- Sherpa Java/Kotlin JNI ABI preservation after R8;
- removal only of unused `libsherpa-onnx-c-api.so` and `libsherpa-onnx-cxx-api.so`;
- retention of `libsherpa-onnx-jni.so` and `libonnxruntime.so`;
- ELF check that Sherpa JNI does not `DT_NEEDED` a trimmed standalone API library.

## Production signing boundary

Production signing is still externally blocked until the project owner can create and securely back up the first production keystore/certificate on a trusted computer.

RC3 does not weaken that boundary. PR Release APK/AAB artifacts remain intentionally unsigned, and CI still rejects incomplete signing configuration and any attempt to use the public QA keystore/alias as the production signer.

## CI result

Android PR CI:

- run number: `1107`;
- run id: `38009102164`;
- code head: `bb6cf5d04e9acc99652ca2fdd7bd287f7a6ceb34`;
- result: **SUCCESS**.

The gate passed:

- all existing unit tests plus Stage 14 security contract tests;
- minified/shrunk production-like QA APK;
- unsigned true Release APK;
- Release AAB;
- QA signer verification;
- version/application identity;
- non-debuggable release checks;
- built APK `allowBackup=false` and `usesCleartextTraffic=false` checks;
- source backup/data-extraction rule checks;
- immutable production model-channel commit/version/digest checks;
- rejection of mutable `/main/` production catalog URL;
- R8/Sherpa JNI ABI gate;
- Sherpa native trim gate;
- production signing negative guardrails;
- Room v1-v12 committed schema provenance gate.

## RC3 package sizes and QA artifact

- production-like QA APK: `30,651,379` bytes;
- QA APK SHA-256: `c957d4c8071a18e6b72ec7e3158f1750440bf60fce677c946379f03c2c41031c`;
- unsigned Release APK: `30,639,079` bytes;
- Release AAB: `25,570,923` bytes;
- GitHub artifact ZIP SHA-256: `dfb5f7555a9c1fd89b34b01d9e0e5c0b3fb386954e0b36e40d66cb91e6ee3411`.

The security changes therefore retain the accepted Stage 14.3 package-size boundary.

## Real-device acceptance gate

RC3 remains a candidate until real-device smoke testing confirms:

- in-place QA upgrade preserves current local library/data;
- application starts normally;
- model manager can load the pinned production catalog and installed models remain usable;
- normal HTTPS AI-provider access still works;
- one local ASR/diarization smoke path still completes;
- share/export through the existing FileProvider still works.
