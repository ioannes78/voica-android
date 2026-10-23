# Stage 16B Gate B — B2 Real-Device Report

Status: **REAL-DEVICE PASS / B3 NEXT**

Date: 2026-10-20

## Scope

B2 evaluates real-recording content completeness for the three Gate B ASR candidates under one frozen VAD baseline:

- `funasr-nano-int8`
- `sensevoice-2024-int8`
- `qwen3-asr-0.6b-int8`

Formal B2 evidence requires exact active `fsmn-vad-int8@1.0.0/1`. FSMN runs once on canonical PCM, canonical `sampleIndex` boundaries are frozen once, and the identical PCM chunks are reused by all three ASRs. External punctuation and Qwen timeline alignment are disabled. No B2 transcript evidence is persisted to Room.

## B2 safe comparison boundary

The initial B2 build inherited a 30-second frozen-input cap. Real-device recording #2 exposed a candidate-specific FunASR whole-chunk blank pattern exactly on all three `30.00 s` chunks while SenseVoice and Qwen3 remained nonblank on the same PCM.

B2 was revised to a common **25-second** maximum frozen-input duration for all three candidates. The same recording was rerun and FunASR recovered from `3/5` blank calls to `0/8` blank calls. The separate evidence is frozen in `docs/STAGE_16B_GATE_B_B2_30S_INPUT_FINDING.md`.

The 25-second cap is therefore the verified safe B2 comparison boundary for the current Android FunASR runtime/adapter. This is a practical compatibility finding, not a claim that upstream FunASR intrinsically has a 25- or 30-second model limit.

## Recording 1 — natural single-speaker / conversational speech

- recording ID: `77388f27281135d7c4015b3f5d77c5ecc531a9b3465d6cb23a2ba2bbb09e6238`
- canonical SHA-256: `807effbae2c9dcc14da58e2bd85b9aa11388c4bd1efa151f3f49e4c3b7ac787b`
- FSMN speech segments: `17`
- frozen ASR inputs: `17`

Summary:

| Candidate | Calls | Blank calls | Notes |
| --- | ---: | ---: | --- |
| Fun-ASR-Nano | 17 | 0 | all chunks nonblank |
| SenseVoice | 17 | 1 | blank on short chunk #8 |
| Qwen3-ASR | 17 | 1 | blank on same short chunk #8 |

Manual review found no crash or repeated-chunk failure. FunASR was generally the most stable on this natural speech sample; Qwen3 showed several stronger semantic substitutions, and SenseVoice showed lexical substitutions on some chunks. One recording alone was not used as a final model rank.

## Recording 2 — long natural AI discussion / compatibility probe

- recording ID: `4a76699d-e8bc-4e07-adf2-a0f63362ecb5`
- canonical SHA-256: `659567ea5d5c7d522dfcd1f41fdbe1f3307f416435e3ac2a14d3fdbdc7c84c6`

Original 30-second B2 cap:

| Candidate | Calls | Blank calls | Blank indexes |
| --- | ---: | ---: | --- |
| Fun-ASR-Nano | 5 | 3 | `0,2,3` |
| SenseVoice | 5 | 0 | none |
| Qwen3-ASR | 5 | 0 | none |

All three FunASR failures were exact `30.00 s` inputs.

Same-recording 25-second retest:

- FSMN speech segments: `5`
- frozen ASR inputs: `8`

| Candidate | Calls | Blank calls | Blank indexes |
| --- | ---: | ---: | --- |
| Fun-ASR-Nano | 8 | 0 | none |
| SenseVoice | 8 | 0 | none |
| Qwen3-ASR | 8 | 0 | none |

The previous structural FunASR failure disappeared completely under the common 25-second boundary.

## Recording 3 — mixed-content natural speech

- recording ID: `9ab2ded7-c31c-4048-9342-fd6b54a51cfb`
- canonical SHA-256: `83ed651fee1553a22f83f5c40f73f68becc21c26c1c44f5e8221aab1e338981d`
- exact VAD: `fsmn-vad-int8@1.0.0/1`
- FSMN speech segments: `10`
- frozen ASR inputs: `10`

Summary:

| Candidate | Calls | Blank calls | Nonblank chunks | RTF signal | Peak PSS signal |
| --- | ---: | ---: | ---: | ---: | ---: |
| Fun-ASR-Nano | 10 | 0 | 10 | `1.0503` | `1768323 KB` |
| SenseVoice | 10 | 0 | 10 | `0.2511` | `476894 KB` |
| Qwen3-ASR | 10 | 0 | 10 | `2.0039` | `2341264 KB` |

All three candidates stayed nonblank across the full frozen-chunk matrix. No crash, repeated-chunk failure, or obvious whole-sentence hallucination was observed.

The recording contained mixed Chinese/English content, dates/numbers and repeated references to `Google`, `AlphaGo`, `AI` and `Artificial Intelligence`.

Manual observations:

- FunASR and Qwen3 generally preserved `AlphaGo` more cleanly than SenseVoice; SenseVoice produced visible `alphalpha go`-style artifacts on multiple chunks.
- all three preserved the major numeric facts and long-form Mandarin content without structural omission;
- punctuation/casing differed materially, but B3 owns formal punctuation/language/ITN scoring;
- the final English-heavy chunk remained nonblank for all three; visible wording differences are retained for B3 rather than treated as a B2 blocker.

The RTF/PSS numbers above are observational only. The models ran sequentially in one process; formal resource ranking remains B5.

## B2 decision

**B2 REAL-DEVICE PASS.**

Evidence across the three representative recordings is sufficient to continue all three candidates to B3/B4:

- no candidate has a recurring crash;
- no candidate has a recurring candidate-specific blank problem under the verified 25-second B2 boundary;
- no candidate shows deterministic repeated-output failure;
- no candidate shows repeated whole-sentence hallucination severe enough to block the next gate;
- the one material runtime-compatibility finding is FunASR at the former 30-second boundary, with a verified 25-second workaround.

B2 does **not** select a production winner. Current qualitative shape only:

- FunASR: strong natural-speech completeness and mixed-content robustness; production viability requires explicit safe chunking if later promoted;
- Qwen3: strong long-Mandarin quality from B1 and viable B2 completeness, but resource cost remains a B5 concern;
- SenseVoice: lighter/faster runtime signal and viable B2 completeness, with weaker complex lexical/proper-noun behavior seen in B1/B2.

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
- change Local File product `FSMN -> Silero` policy;
- change realtime Silero;
- change canonical `sampleIndex` ownership;
- modify QS668/CB08, Android Audio I/O, playback or Stage 16A.2 ownership;
- start Stage 16C.
