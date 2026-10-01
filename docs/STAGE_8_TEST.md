# Voica Stage 8 Real-Device Test Checklist

Status: candidate acceptance checklist — do not Freeze Stage 8 until all required items pass and the user explicitly confirms.

Candidate:
- versionCode: 20
- versionName: 0.8.0-stage8-alpha1
- ABI: arm64-v8a
- sherpa-onnx runtime: 1.13.8
- Room schema: 2

## A. Installation / migration / regression

1. Upgrade-install over the accepted Stage 7 APK.
   - Existing local recordings remain present.
   - Existing verified canonical WAV assets remain playable.
   - Existing Stage 7 playback seek/speed/lifecycle behavior remains normal.
2. Cold install on a clean device also starts normally.
3. Connect QS668/CB08, refresh file list, download a recording and confirm the existing BLE / download / canonical-audio path still works.
4. Start device recording while local playback is active and confirm playback still pauses as required by Stage 7.

## B. Local AI runtime / built-in VAD

5. Settings -> local AI runtime -> run the sherpa-onnx native-load probe.
   - Expected: available / success.
6. Settings -> local models.
   - Built-in Silero VAD is shown as installed/active.
   - Automatic model checks are enabled by default.
   - Silero small-model automatic updates are enabled by default.
   - ASR / punctuation / SenseVoice must never auto-download merely because an update exists.

## C. Model-channel prerequisite

The production URL is:

https://raw.githubusercontent.com/ioannes78/voica-model-channel/main/manifests/production.json

Before tests D–H, the independent public repository `ioannes78/voica-model-channel`
must exist and its production manifest must contain approved packages for:

- Small Bilingual Zipformer first-pass ASR
- CT-Transformer zh-en punctuation
- SenseVoice 2024 int8 second-pass ASR
- Silero VAD baseline / managed override metadata

Until that repository is published, update checks may report a network / 404 failure and
Fast / High Quality transcription must report missing models rather than crash.

## D. Manual model download / integrity / activation

7. Download Small Bilingual.
   - Progress is a real byte count / percentage.
   - Cancel during download; partial package is removed and state returns to a retryable state.
   - Retry and complete download.
   - SHA/package/file verification succeeds.
   - Candidate remains inactive until "验证并启用" is used.
   - Native smoke validation succeeds before the active version changes.
8. Repeat for CT-Transformer punctuation.
9. Repeat for SenseVoice second-pass ASR.
10. Confirm downloaded model delete, retry and rollback controls:
    - A model used by an active transcription cannot be deleted.
    - Deleting an inactive candidate works.
    - Silero downloaded override can roll back to the APK built-in baseline.
    - A failed candidate never replaces the previous active model.

## E. Fast local transcription

11. Choose a short Mandarin recording with verified canonical WAV and tap "快速转写".
    - Phase sequence is meaningful: preparing -> VAD -> first pass -> punctuation -> persisting.
    - Percentages are real for phases that have a denominator; no fabricated overall percent.
    - Final text is stored and shown read-only.
    - Chinese punctuation includes at least normal comma / full stop / question / exclamation behavior where appropriate.
12. Test a recording containing Mandarin + English words/acronyms/numbers.
13. Test clear pauses / silence between phrases.
    - VAD segmentation must not lose speech around boundaries.
14. Open "查看转写".
    - Segment times are based on canonical absolute sample indices.
    - Existing playback timeline is unchanged.

## F. High Quality two-pass transcription

15. Run "高质量转写" on the same short Mandarin sample.
    - SenseVoice second pass loads only for the HQ task.
    - Final text is persisted as an independent Transcription version; the Fast version is not overwritten.
16. Run mixed zh-en content and compare the displayed HQ result with Fast.
    - This is a functional check only; Stage 8 does not define a subjective WER threshold.
17. If second-pass or punctuation processing fails recoverably, the UI must show the fallback / failure state without corrupting a previously completed transcription.

## G. Cancellation / retry / process lifecycle

18. Cancel during VAD.
    - Task becomes CANCELLED.
    - PCM/model resources and exact revision leases are released.
19. Cancel during first-pass ASR.
20. Cancel during second-pass ASR.
21. Force-stop the app during an active transcription, then reopen.
    - The old active DB row is reconciled to INTERRUPTED.
    - It must not remain falsely "running".
22. Retry after failure/cancellation.
    - A new Transcription version is created; the prior attempt remains historical evidence.

## H. Memory / stability

23. Run several short transcriptions back-to-back.
    - At most one high-load transcription runs at a time.
    - App remains responsive and does not retain all models permanently.
24. While transcription is active, open Settings and attempt to delete its model revision.
    - Deletion is blocked.
25. Confirm playback and BLE connection remain usable after transcription completes.

## I. Deferred long-recording acceptance

Automated tests cover virtual 30 / 60 / 120 minute sources with bounded 4,096-sample reads,
Long absolute sample indices, cancellation and no whole-file PCM allocation.

Formal real-device 30 min / 1 h / 2 h stress acceptance remains deferred to Stage 13,
consistent with the Stage 7 freeze boundary.

## Acceptance gate

Stage 8 may be frozen only after:

- CI is green on the exact candidate commit.
- The candidate APK is installed and tests A–H applicable to the device are completed.
- The production model channel is live and all three downloadable model classes pass
  download + integrity + native smoke activation.
- No Stage 1–7 regression is found.
- The user explicitly says the Stage 8 real-device tests passed.
