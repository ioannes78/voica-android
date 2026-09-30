# Stage 5 Real Device Findings

## 2026-09-30 Alpha 1 / Alpha 3

### Full download
- QS668/CB08 full transfer succeeds with TYPE=2 CMD=2 -> CMD=3 -> CMD=4* -> CMD=5.
- The transferred file is not RIFF/WAVE.
- The transferred bytes match the device logical `.opus` recording size and are saved as the local raw `.opus` artifact.
- Standard request filename is 24 ASCII bytes; observed request frame is 36 bytes. This does not by itself prove every filename is fixed-width.

### Range transfer (CMD=12)
Real-device probe:
- requested range: 0..255
- received bytes: 255
- returned filename: `note20260930-145841.opus`
- received bytes matched the corresponding local full-download prefix
- remote status: 0

Conclusion:
- CMD=12 end offset is **exclusive** for this device/firmware.
- Range semantics for the tested case are `[start, end)`.

### Single recording delete
Alpha 3 candidate used the list entry raw bytes as CMD=8 body.
Real-device result:
- one attempt timed out / produced an unknown outcome and the recording remained present after refresh;
- another attempt returned a rejection;
- no tested recording was deleted.

Conclusion:
- raw list-entry CMD=8 payload is **not accepted by this device behavior** and must not be frozen.
- The next candidate is the alternate 28-byte layout already represented by the historical protocol builder:
  `00000000 + full filename padded to 24 bytes`.
- Alpha 4 switches to that candidate for a new safe-recording test only.
- No automatic destructive fallback or retry is permitted.
