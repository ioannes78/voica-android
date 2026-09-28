# Voica Development Rules

## Source of truth

The current repository is the only source of truth for Voica implementation status.

Reference behavior is pinned to:
- repository: `laidely/kardo`
- commit: `bcec3c5fdbcb34810a6f235e8b5873683f2ab951`

Do not copy or translate Kardo Swift source into Voica. Reimplement behavior independently for Android.

## Development gate

Before each stage:
1. Read this file, the current roadmap, architecture, protocol notes and previous stage freeze.
2. Inspect the actual current code.
3. Produce a revised requirement and implementation plan.
4. Wait for explicit approval before feature coding.

At the end of each stage:
1. Run applicable unit/build/static checks.
2. Produce a testable APK when the stage contains user-facing functionality.
3. Record exact commit SHA, build evidence, known risks and manual test checklist.
4. Add a stage freeze/handoff document.

## Android baseline

- Language: Kotlin
- UI: Jetpack Compose
- Concurrency: Kotlin Coroutines / Flow
- Minimum SDK: to be fixed during Stage 1; target modern Android while preserving practical device coverage
- Proposed application ID: `io.github.ioannes78.voica`
- ABI priority: `arm64-v8a`
- Build system: Gradle Kotlin DSL

## Architecture rules

- BLE transport, binary protocol, audio codec, ML engines and UI must be separable.
- BLE notifications AE22 and AE23 use independent stream parsers.
- GATT writes are serialized.
- The TYPE=2/CMD=2 import request is treated as one complete 36-byte protocol frame.
- Raw device audio is preserved before any conversion.
- Destructive device operations require explicit UI confirmation.
- No UI layer may parse binary protocol frames directly.
- No ASR/LLM engine-specific model type may leak into feature UI contracts.
- Long-running download, decode and inference operations must be cancellable and lifecycle-safe.

## Testing rules

Protocol logic requires deterministic unit/golden tests, including:
- CRC-16/XMODEM vector `123456789 -> 0x31C3`
- frame construction and stream parsing
- split/coalesced notification input
- file-list endianness
- 36-byte import request
- single-file delete request layout
- filename candidate fallback
- Opus packet/container conversion invariants
- segment normalization and merge invariants

Do not treat CI success as device acceptance. BLE and long-audio stages require real-device acceptance.

## CI policy

Keep CI intentionally small:
- run fast unit/build checks on pull requests
- avoid emulator/instrumentation work unless required by the current stage
- avoid duplicate workflows for the same commit
- do not build every experimental branch automatically
