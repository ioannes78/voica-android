# Stage 17 P0 — Offline LLM Runtime / Model Feasibility

Status: **IN PROGRESS / R4 GALLERY PARITY / NO PRODUCTION LLM DECISION YET**

Branch: `stage17-fully-offline-intelligence`
Base production baseline: `28ec9a837c5b0debac8e6d32ab99e0cf8d80be12`

## Purpose

P0 exists only to decide whether the first local LLM candidate is technically suitable for WENZHI on Android. It does not authorize P1, production Summary, retrieval, Q&A, or a production LLM model contract.

The Android `main` branch now independently contains the accepted FunASR Nano revision-2 native CTC production baseline. P0 inherits that speech baseline but does not modify or republish it.

## Runtime path

P0 started by validating LiteRT-LM 0.18.1. Real-device testing then showed that both CPU and GPU could terminate the app during the common Engine/model-load path.

R4 is a controlled same-device parity test against Google AI Edge Gallery, which can run the same Gemma 4 E2B base model on the test device. R4 therefore deliberately aligns the WENZHI probe with the Gallery configuration before changing the already verified model artifact:

- LiteRT-LM Android runtime: `0.18.0`;
- `maxNumTokens = 4000`;
- `cacheDir = null`;
- `SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0)`;
- ordinary `Contents` prompt input;
- speculative decoding disabled.

This is a feasibility configuration only. It is not a production runtime freeze.

## Gemma 4 E2B candidate

- model id: `litert-community/gemma-4-E2B-it-litert-lm`
- file: `gemma-4-E2B-it.litertlm`
- frozen base revision: `6e5c4f1e395deb959c494953478fa5cec4b8008f`
- exact file size: `2,588,147,712` bytes
- exact SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`
- minimum candidate device memory: `8 GB`
- advertised maximum context: `32K`
- candidate accelerators: CPU / GPU
- optional MTP update revision: `7fa1d78473894f7e736a21d920c3aa80f950c0db`

These values are P0 feasibility inputs. They are not yet a WENZHI production LLM pin.

## P0 probe contract

The isolated `:engine:local-llm` module must verify:

1. The R4 LiteRT-LM runtime resolves and compiles under the current Android/Gradle/Kotlin toolchain.
2. The exact SHA-256-verified `.litertlm` base model can initialize on CPU and, where supported, GPU.
3. The fixed smoke prompt is `请用一句简短中文回答：你现在运行在本地设备上。`
4. The probe records initialize latency, first streamed emission latency, total generation latency, PSS before/after initialize, PSS after generation/close, peak PSS, backend, runtime, context limit, and a bounded response preview.
5. Probe phase breadcrumbs and Android process-exit diagnostics must distinguish caught Kotlin failures from native crash / signal / low-memory termination where Android exposes that evidence.
6. Cancellation must unwind Conversation and Engine lifecycle through structured resource closing.
7. The QA harness remains unavailable to the production application ID.

## Explicit non-goals

P0 does not:

- implement Summary;
- implement hierarchical long-text processing;
- implement RAG / retrieval;
- implement Q&A;
- add provider/API-key/cloud fallback code;
- change the production FunASR/model-channel baseline inherited from `main`;
- republish the patched Sherpa runtime;
- declare Gemma 4 E2B production-ready.

## Exit decision

P0 ends only with one of:

- `PASS`;
- `PASS WITH RESTRICTIONS`;
- `FAIL`.

A decision requires real-device CPU evidence, GPU evidence or a documented unsupported restriction, repeated-load memory recovery, cancellation, foreground/background lifecycle behavior, and thermal observation. P1 remains blocked until that decision is recorded.
