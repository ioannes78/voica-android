# Kardo Reference Baseline

## Reference

- Repository: `laidely/kardo`
- Commit: `bcec3c5fdbcb34810a6f235e8b5873683f2ab951`
- Commit date: 2026-08-24
- Platform: iOS/macOS, Swift/SwiftUI
- Repository-level license: none declared at the pinned reference

Voica uses this repository as a public behavioral/protocol reference. Swift implementation is not copied or mechanically translated.

## Functional mapping

| Kardo area | Observed behavior | Voica Android target |
|---|---|---|
| CoreBluetooth | AE20 service, AE21 write, AE22/AE23 notify | Android BluetoothGatt transport |
| KardoProtocol | framing, CRC, parsing, field decoding | independent Kotlin protocol module |
| KardoRecorder | request/response, file list/download/delete, realtime/control | coroutine-based recorder/session layer |
| KardoOgg | raw QS668 Opus packaging | independent audio/Opus module |
| KardoLibrary | local recording persistence | Room + app file storage |
| KardoAudioPlayer | accurate WAV seeking/speed | Android Media3/AudioTrack as appropriate |
| KardoSpeechEngine | local ASR + diarization pipeline | Android-compatible on-device engines |
| KardoSegmentMerge | normalize/merge real timed segments | Kotlin pure-function equivalent behavior |
| KardoLlm | OpenAI-compatible meeting notes | provider-agnostic HTTP client |
| SwiftUI views | local/device list, detail, settings | Jetpack Compose |

## Protocol facts to reproduce and verify

- Frame layout: `5A | SEQ | CRC16-LE | LEN-LE | DATA`
- CRC: CRC-16/XMODEM over `LEN + DATA`
- Standard CRC vector: `123456789 -> 0x31C3`
- Service/characteristics: AE20 / AE21 / AE22 / AE23
- AE22 and AE23 need separate stream-parser state
- TYPE=2/CMD=2 import request is a complete 36-byte frame
- list entries contain a short/truncated filename field and use observed big-endian numeric decoding
- file download candidates include `base.opus`, `base.wav`, then original listed name
- single-file delete behavior is based on observed firmware behavior rather than blindly trusting protocol prose
- device raw Opus behavior must be validated against real sample files before codec implementation is frozen

## Kardo design choices that are platform-specific and must NOT be copied

- SwiftUI / iOS 26 Liquid Glass
- CoreBluetooth lifecycle model
- AVAudioEngine
- MLX / MLXSwift
- CoreML Sortformer
- UserDefaults persistence
- Apple-specific memory/cache handling

## Android replacements

- Jetpack Compose
- BluetoothGatt + serialized operation queue
- Coroutines / StateFlow
- Room + DataStore
- Android-compatible Opus/PCM pipeline
- Android-compatible ASR/diarization runtime, selected through Stage 5 benchmarking
- Media3 and/or AudioTrack depending on seek/timing requirements

## Compatibility rule

When Kardo behavior, protocol documentation and actual CB08/QS668 hardware disagree, Voica follows reproducible device evidence and records the evidence in tests/documentation.
