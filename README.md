# Voica

Voica is an Android client for QS668 / CB08 AI recorder cards.

The project is a clean Android implementation inspired by the observable behavior and protocol documentation of [laidely/kardo](https://github.com/laidely/kardo), pinned for reference at commit `bcec3c5fdbcb34810a6f235e8b5873683f2ab951`.

## Goals

- Reliable QS668 / CB08 BLE connectivity
- Device information, battery, capacity, firmware and time sync
- Device file discovery, download and deletion
- Raw Opus preservation and 16 kHz PCM/WAV processing
- Local playback with seek and transcript synchronization
- On-device transcription and speaker diarization
- AI meeting notes through configurable OpenAI-compatible APIs
- Android-first architecture, testing and lifecycle handling

## Implementation principles

- Kotlin + Jetpack Compose
- Android-native BLE implementation
- Protocol behavior reproduced from public protocol facts and device observations
- No direct Swift-to-Kotlin source translation
- Protocol and binary behavior covered by golden tests
- Destructive device actions require explicit confirmation
- Long-running audio / ML work must be lifecycle-safe and memory-bounded

## Project status

Planning baseline. No production feature implementation has started.

See:

- [Product requirements](docs/PRODUCT_REQUIREMENTS.md)
- [Kardo reference baseline](docs/KARDO_REFERENCE_BASELINE.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Roadmap](docs/ROADMAP.md)
- [Development rules](AGENTS.md)

## Reference project

Reference: `laidely/kardo`  
Pinned commit: `bcec3c5fdbcb34810a6f235e8b5873683f2ab951`

Kardo currently has no repository-level license declared. Voica therefore treats it as a behavioral/protocol reference only and implements Android code independently.

## License

Not selected yet.
