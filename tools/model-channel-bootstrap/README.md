# Voica model-channel bootstrap

This directory is a staging template for the independent public repository:

`ioannes78/voica-model-channel`

It is intentionally kept outside the Android runtime source tree. The Android app
continues to treat that independent repository as the production model channel.

## Semi-automatic release policy

1. A maintainer manually triggers the candidate workflow with a new revision.
2. CI downloads the pinned upstream release asset, verifies the pinned byte size,
   extracts only the four files required by Voica's Android streaming engine, renames
   them to the runtime contract, and creates a deterministic ZIP.
3. CI emits both the candidate ZIP and machine-readable metadata containing package
   SHA-256 plus every installed file's size and SHA-256.
4. The candidate is installed on a real Android device and must pass ModelManager
   integrity verification plus the native sherpa-onnx smoke activation gate.
5. Only after that evidence exists may a human merge a production-manifest PR.
6. The App never silently changes an active model merely because upstream published
   a new asset.

## First-pass ASR candidate

Upstream source:

- sherpa-onnx release tag: `asr-models`
- GitHub release asset id: `157661357`
- archive:
  `sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16.tar.bz2`
- pinned upstream asset size: `458187351` bytes
- upstream model/source license: Apache-2.0

The upstream archive contains both floating-point and int8 variants. Voica publishes
only these four runtime files:

- `encoder-epoch-99-avg-1.int8.onnx` -> `encoder.int8.onnx`
- `decoder-epoch-99-avg-1.onnx` -> `decoder.onnx`
- `joiner-epoch-99-avg-1.int8.onnx` -> `joiner.int8.onnx`
- `tokens.txt` -> `tokens.txt`

The documented generic CPU int8 invocation uses an int8 encoder and joiner with the
fp32 decoder. Those files are approximately 41 MB + 14 MB + 3.1 MB and tokens are
approximately 55 KB, so the installed first-pass payload is roughly 58–60 MB rather
than the full upstream archive.

The source archive also contains `decoder-epoch-99-avg-1.int8.onnx`, but Voica does
not publish that smaller decoder in the first production revision because the
model-specific sherpa-onnx int8 example does not use it. It may be evaluated later as
a distinct candidate revision after Android accuracy/stability validation.

Do not substitute the ~49 MB RK356x/RK3588 release assets. Those are Rockchip-targeted
packages and are not the generic Android arm64 CPU package used by Voica.

## Repository initialization

After the empty `voica-model-channel` repository is created, copy:

- `scripts/prepare-small-bilingual.sh`
- `.github/workflows/prepare-small-bilingual-candidate.yml`

Then add `manifests/production.json`. The production manifest must always include
the Silero baseline entry expected by the app plus any downloadable ASR,
punctuation, and second-pass entries. A candidate package must not enter
`production.json` until its real-device smoke test is recorded.

The Android app currently reads:

`https://raw.githubusercontent.com/ioannes78/voica-model-channel/main/manifests/production.json`
