# Stage 17 P0 — Offline LLM Runtime / Model Feasibility

Status: **IN PROGRESS / IMPLEMENTATION STARTED / NO PRODUCTION DECISION YET**

Branch: `stage17-fully-offline-intelligence`
Base: `0fbdc78341e991f7964274e52a2ecabbdc6a060e`

## Purpose

P0 exists only to decide whether the first local LLM candidate is technically suitable for WENZHI on Android. It does not authorize P1, production Summary, retrieval, Q&A, model-channel promotion, or a production model contract.

## Reverified external inputs — 2026-10-24

### Runtime

- LiteRT-LM latest GitHub release observed at P0 start: `v0.18.1`.
- Android Maven coordinate used by the probe: `com.google.ai.edge.litertlm:litertlm-android:0.18.1`.
- The official Kotlin API documents CPU and GPU backends, Android Gradle/Maven integration, Engine lifecycle, Conversation lifecycle, and Flow-based streaming generation.
- GPU use requires optional `libvndksupport.so` and `libOpenCL.so` declarations in the merged Android manifest.

### Gemma 4 E2B candidate

Current Google AI Edge Gallery allowlist data observed at P0 start identifies:

- model id: `litert-community/gemma-4-E2B-it-litert-lm`
- file: `gemma-4-E2B-it.litertlm`
- primary allowlist revision: `6e5c4f1e395deb959c494953478fa5cec4b8008f`
- primary allowlist size: `2,588,147,712` bytes
- minimum device memory: `8 GB`
- maximum advertised context: `32K`
- accelerators: CPU / GPU
- optional MTP update revision: `7fa1d78473894f7e736a21d920c3aa80f950c0db`

These values are P0 feasibility inputs. They are not yet a WENZHI production model pin.

## Known risk evidence to measure, not assume away

- LiteRT-LM 0.18.x has a reported Android `ModelInfo.from()` regression that can temporarily copy large model sections into RSS. The P0 probe therefore does not use `ModelInfo.from()` for basic smoke execution.
- Structured JSON plus speculative decoding has a reported failure combination on an earlier 0.17.x Android runtime. P0 starts with ordinary text generation and does not enable speculative decoding.
- Upstream benchmark numbers are reference points only. WENZHI must collect its own physical-device data.

## P0 probe contract

The isolated `:engine:local-llm` module must verify:

1. LiteRT-LM 0.18.1 resolves and compiles under the current Android/Gradle/Kotlin toolchain.
2. A caller-supplied `.litertlm` file can initialize on CPU and, where supported, GPU.
3. The fixed smoke prompt is:
   `请用一句中文回答：你现在运行在本地设备上。`
4. The probe records:
   - initialize latency;
   - time to first streamed emission;
   - total generation latency;
   - PSS before initialize;
   - PSS after initialize;
   - PSS after generation;
   - a bounded response preview.
5. Cancellation must unwind Conversation and Engine lifecycle through structured resource closing.

## Explicit non-goals

P0 does not:

- implement Summary;
- implement hierarchical long-text processing;
- implement RAG / retrieval;
- implement Q&A;
- add provider/API-key/cloud fallback code;
- modify `ioannes78/voica-model-channel` PR #21;
- merge or promote any speech model manifest;
- declare Gemma 4 E2B production-ready.

## Exit decision

P0 ends only with one of:

- `PASS`;
- `PASS WITH RESTRICTIONS`;
- `FAIL`.

A decision requires real-device CPU/GPU evidence, repeated-load memory recovery, cancellation, foreground/background lifecycle, and thermal observation. P1 remains blocked until that decision is recorded.
