# Stage 16B — A3-H2 Gate Closure

Status: **PASS — EXPORT / NUMERICAL / EXACT ALIGNMENT**

Repository: `ioannes78/voica-android`

Evidence checkpoint: `d9ed0d634b4a973bcc3b31a8dbf99e980124eb05`

Workflow: `FunASR CTC A3 H2 Gate` run `#12` / run id `39638799417`

Job: `H2a+H2b+H2c+H2d export + production INT8 + end-to-end parity` / job id `119356579542`

## Decision

A3-H2 is closed as a desktop/export portability and parity **PASS**. The H2 job completed successfully across H2a, H2b, H2c and H2d. This authorizes the next Stage 16B step — candidate revision `/2` Android integration and real-device proof — but does **not** authorize production promotion, PR merge, Final Freeze/Handoff or Stage 16C.

The acceptance criteria were not relaxed:

- max size ratio: `0.35`;
- max relative RMSE: `0.08`;
- max logit absolute error: `2.0`;
- blank id: `60514`;
- fixtures: `zh.wav`, `en.wav`;
- alignment requirement: exact CTC Viterbi state-path equality on both fixtures.

## H2a / H2b / H2c

The same successful H2 job proves the required chain before H2d acceptance:

- H2a: FP32 compact CTC scorer export/parity **PASS**;
- H2b: revision-1 to shared dual-output encoder parity **PASS**;
- H2c: exact production INT8 graph exposes the pre-adaptor CTC state and full revision-1 to dual-output LLM generation parity **PASS**;
- H2d: fine-grained mixed INT8 compact CTC selection **PASS**.

H2a recorded `pytorch_onnx_max_abs = 1.9073486328125e-05` on the reported controlled case and finished with `H2a FP32 CTC scorer export/parity PASS`.

## H2d fine-grained INT8 search

The previous whole-block search was too coarse: projection-only preserved exact alignment but missed the unchanged size limit, while full/whole-block candidates could reduce size but did not preserve the required exact path.

The r6 selector therefore uses the deterministic policy:

`per-node sensitivity probes -> keep only exact+numeric-safe nodes -> deterministic greedy additions`

with four predefined orderings. The successful ordering was `saving-desc`.

Inventory/search evidence:

- five CTC decoder blocks;
- `6` weighted quantizable MatMuls per block = `30` individual probes;
- `2` activation-attention MatMuls per block remain FP32;
- `24 / 30` weighted nodes were individually exact+numeric safe;
- successful greedy candidate count: `12`;
- acceptance reached at `12` selected weighted MatMuls.

Selected weighted MatMul nodes:

1. `/blocks.0/self_attn/linear_k/MatMul`
2. `/blocks.1/self_attn/linear_k/MatMul`
3. `/blocks.1/self_attn/linear_out/MatMul`
4. `/blocks.1/self_attn/linear_q/MatMul`
5. `/blocks.2/self_attn/linear_out/MatMul`
6. `/blocks.2/self_attn/linear_q/MatMul`
7. `/blocks.2/self_attn/linear_v/MatMul`
8. `/blocks.3/self_attn/linear_k/MatMul`
9. `/blocks.3/self_attn/linear_out/MatMul`
10. `/blocks.3/self_attn/linear_q/MatMul`
11. `/blocks.3/self_attn/linear_v/MatMul`
12. `/blocks.4/self_attn/linear_out/MatMul`

## Canonical H2d INT8 result

Canonical candidate: `ctc_compact.int8.onnx`

- SHA-256: `db93af357c1d5ba78e9516950fd8931ead11666ffa00b1928e2fda9e8003bc38`;
- size: `54,235,091` bytes;
- FP32 scorer size: `156,314,748` bytes;
- size ratio: `0.34696080628297465` — **PASS** against `<= 0.35`;
- reduction from projection-only candidate: `9,371,339` bytes;
- selected / added quantized weighted MatMuls: `12 / 12`;
- max relative RMSE: `0.006563685632818176` — **PASS** against `<= 0.08`;
- max logit absolute error: `0.201019287109375` — **PASS** against `<= 2.0`;
- total state-path differing frames across both fixtures: `0`;
- total label-path differing frames across both fixtures: `0`;
- exact alignment: **PASS**.

Fixture evidence:

| Fixture | Frames | Relative RMSE | Max abs logit error | State path | Label path | Changed frames |
| --- | ---: | ---: | ---: | --- | --- | ---: |
| `zh.wav` | 93 | `0.006563685632818176` | `0.201019287109375` | exact | exact | `0` |
| `en.wav` | 119 | `0.005754518665660729` | `0.13217878341674805` | exact | exact | `0` |

The canonical candidate was runner-local only. The successful run uploaded no model artifact; the workflow cleanup removed H2 model/export assets after hashing and verification.

## Evidence boundary

This PASS proves the real export/parity feasibility needed to proceed beyond A3-H2. It does **not** yet prove:

- Android arm64 execution of the real revision `/2` assets through the A2 retained-state runtime;
- real non-uniform user-facing CTC timing;
- byte-fragment compaction and punctuation anchoring in product output;
- Android CTC wall time, total RTF, PSS or thermal behavior;
- Hotword OFF/ON completeness after CTC integration;
- sentence/speaker segmentation quality with real CTC;
- production readiness.

## Next work order

1. Build/connect candidate `funasr-nano-int8@2025-12-30/2` assets to the already-proven A2 same-stream runtime contract without a second encoder pass.
2. Map trusted CTC spans through the SenseVoice CTC tokenizer into product tokens and canonical `sampleIndex`, with fail-closed FSMN/VAD timing fallback.
3. Execute Android arm64 technical/performance proof.
4. Run real-device acceptance on the fixed 118-second Hotword OFF/ON case plus at least two longer recordings.
5. Re-test sentence segmentation and speaker alignment only after real CTC timing is present; do not tune CAM++ first.
6. Keep `total RTF < 1.0` as the important high-quality target where practical.
7. Only after those gates pass may revision `/2` / model-channel v10 become a Draft candidate for later explicit promotion authorization.

## Frozen boundaries

- PR #35 remains Draft/unmerged.
- Model-channel PR #21 remains Draft/unmerged.
- Current production/staged FunASR identity remains revision `/1` until separately authorized.
- No production/model-channel promotion is authorized by H2.
- Room remains v14.
- Stage 16C remains `RESERVED / NOT STARTED`.
