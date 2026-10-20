# Stage 16B Gate B — B2 Real Recording Quality / Completeness

Status: **AUTOMATED PASS / REAL-DEVICE PASS / B3 NEXT**

## Objective

B2 checks whether the three Gate B ASR candidates preserve real spoken content under natural recording conditions. Unlike B1, B2 does not manufacture CER/WER for uncontrolled recordings.

Candidates:

1. `funasr-nano-int8`
2. `sensevoice-2024-int8`
3. `qwen3-asr-0.6b-int8`

## Fairness contract

Formal B2 evidence requires:

1. exact active `fsmn-vad-int8`;
2. Silero fallback invalidates the run;
3. FSMN runs exactly once per canonical recording;
4. canonical `sampleIndex` boundaries are frozen once;
5. oversized speech ranges are split deterministically with the B2-specific **25-second** ASR input cap;
6. the exact same frozen PCM chunks are sent to every ASR candidate in the same order;
7. external punctuation and Qwen timeline alignment remain disabled;
8. no B2 transcript/evidence is persisted to Room.

The 25-second cap is B2-specific. B0/B1 historical evidence is unchanged. The boundary finding is documented in `docs/STAGE_16B_GATE_B_B2_30S_INPUT_FINDING.md`.

First-pass B2 runs are limited to 120 seconds. B6 owns representative long-file testing.

## QA evidence model

The QA-only `Stage16BGateB2CompletenessRunner` records, for every frozen input chunk and every candidate:

- zero-based chunk index;
- canonical `startSampleIndex` / `endSampleIndexExclusive`;
- raw hypothesis text;
- detected language when available;
- blank/nonblank after removing only known SenseVoice language metadata;
- model identity;
- engine-open time;
- ASR wall time / total wall time / RTF signal;
- start / peak PSS signal;
- runtime error if present.

The QA page prints a `B2 FROZEN-CHUNK MATRIX` so omissions, repetitions, hallucinations and semantic substitutions can be reviewed on identical PCM.

B2 PSS/RTF are signals only. Formal isolated performance/memory ranking belongs to B5.

## Automated checkpoints

Initial implementation:

- code / QA APK head `e8e5477c4a99b98e6f6e149c9027647652b46324`
- Android PR CI #1973 Fast PR Gate: **PASS**
- B2 chunk-evidence tests: **PASS**
- Android PR CI #1974 production-like QA APK Gate: **PASS**
- artifact ID `12255162649`
- digest `sha256:70de5ca27ac8fcaa70157b38366e043847447e12fd4113679050fb6b5ad25792`

25-second verification revision:

- checkpoint `d00a3093adb3791b9a77866aa4b9148e9d9ea88c`
- Android PR CI #1980 Fast PR Gate: **PASS**
- `30 s -> 25 s + 5 s` split regression: **PASS**
- all-blank model cannot report READY: **PASS**
- Android PR CI #1981 production-like QA APK Gate: **PASS**
- artifact ID `12260190295`
- artifact digest `sha256:6a21093443f0939fcc925558d74f300a08c7cd2c6de8c5cc28599aaba09ab639`
- extracted APK SHA-256 `b4f2229f60279ebb611170cbe857a5fb1fabc24589069db93bce77fae486e9e2`

## Real-device evidence

Full report: `docs/STAGE_16B_GATE_B_B2_REAL_DEVICE_REPORT.md`.

### Recording 1 — natural single-speaker / conversational speech

- 17 frozen inputs;
- FunASR: 17 calls / 0 blank;
- SenseVoice: 17 calls / 1 blank;
- Qwen3: 17 calls / 1 blank;
- no crash or repeated-chunk failure.

The SenseVoice/Qwen3 blank occurred on the same very short chunk. Manual review showed lexical/semantic differences but no recurring structural blocker.

### Recording 2 — long natural AI discussion

Recording ID: `4a76699d-e8bc-4e07-adf2-a0f63362ecb5`.

Original 30-second B2 cap:

- 5 frozen inputs;
- FunASR blank on chunks `0,2,3`;
- each failed chunk was exactly `30.00 s`;
- SenseVoice/Qwen3 were nonblank on the same five inputs.

Same recording with the common 25-second cap:

- exact `fsmn-vad-int8@1.0.0/1`;
- 5 FSMN speech segments -> 8 frozen inputs;
- FunASR: 8 calls / **0 blank**;
- SenseVoice: 8 calls / 0 blank;
- Qwen3: 8 calls / 0 blank.

This verifies the 25-second cap as the safe B2 comparison boundary for the current Android FunASR runtime/adapter. The lower-level root cause is not claimed as proven.

### Recording 3 — mixed-content natural speech

Recording ID: `9ab2ded7-c31c-4048-9342-fd6b54a51cfb`.

- canonical SHA-256 `83ed651fee1553a22f83f5c40f73f68becc21c26c1c44f5e8221aab1e338981d`;
- exact `fsmn-vad-int8@1.0.0/1`;
- 10 FSMN speech segments / 10 frozen inputs;
- FunASR: 10 calls / 0 blank;
- SenseVoice: 10 calls / 0 blank;
- Qwen3: 10 calls / 0 blank;
- no crash, deterministic repeat-output failure, or obvious whole-sentence hallucination.

The recording contains Chinese/English content, numbers/dates and `Google` / `AlphaGo` / `AI` / `Artificial Intelligence`. FunASR and Qwen3 generally preserve `AlphaGo` more cleanly than SenseVoice, while SenseVoice shows visible `alphalpha go`-style artifacts on multiple chunks. Formal punctuation/language/ITN conclusions belong to B3.

## B2 decision

**B2 REAL-DEVICE PASS.**

All three candidates remain viable for B3/B4. No candidate is promoted or removed by B2.

The only material B2 compatibility finding is the former 30-second FunASR input boundary. A common 25-second boundary removes the observed whole-chunk blank failure on the same recording. If FunASR is later selected for production, production chunking must explicitly account for this verified safe boundary before promotion.

Qualitative shape only, not final ranking:

- FunASR: strong natural-speech completeness and mixed-content robustness; requires safe chunking if promoted;
- Qwen3: viable completeness and strong B1 long-Mandarin quality; B5 must assess resource cost;
- SenseVoice: viable completeness and lighter/faster runtime signal, with weaker complex lexical/proper-noun behavior seen in B1/B2.

All three proceed to:

1. **B3 Punctuation / ITN / Language / Hallucination**;
2. **B4 Timestamp / Timeline Compatibility**.

B5 performance/memory and B6 representative long-file testing remain later gates for viable finalists.

## Frozen product state

B2 completion does not:

- merge Android PR #35;
- merge model-channel PR #21;
- modify `main`;
- promote Fun-ASR-Nano;
- remove SenseVoice or Qwen3-ASR;
- change Room v14;
- change Local File `FSMN -> Silero` product policy;
- change realtime Silero;
- change canonical `sampleIndex` ownership;
- modify QS668/CB08, Android Audio I/O, playback or Stage 16A.2 ownership;
- start Stage 16C.
