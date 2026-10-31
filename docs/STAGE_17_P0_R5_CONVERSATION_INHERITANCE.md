# Stage 17 P0 R5 — Conversation inheritance isolation

Status: **IMPLEMENTED FOR QA / P0 BLOCKED / DO NOT MERGE**

## R4 real-device evidence

On the target 8.01 GB Android device, both CPU and GPU probes passed model SHA validation and Engine initialization, then the app process died while the persisted phase was `CONVERSATION_CREATING`.

- CPU: `REASON_CRASH_NATIVE`, status/signal `6` (`SIGABRT`), PSS about 126 MB, RSS about 244 MB.
- GPU: the second crash was classified by Android as `REASON_OTHER`, with the same persisted `CONVERSATION_CREATING` phase.
- Because CPU RSS was low when the native abort was recorded, R5 does not treat this as evidence of LMK/OOM and does not lower the KV limit again.
- P0 remains blocked. P1 must not start.

## R5 single-variable change

Keep all R4 feasibility variables fixed:

- LiteRT-LM Android `0.18.0`;
- Gemma 4 E2B base revision `6e5c4f1e395deb959c494953478fa5cec4b8008f`;
- exact file size `2,588,147,712` bytes;
- exact SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`;
- CPU/GPU backends unchanged;
- `maxNumTokens=4000`;
- `cacheDir=null`;
- sampler `topK=64`, `topP=0.95`, `temperature=1.0`;
- fixed short Chinese prompt and `Contents` input.

Correct only the speculative-decoding ownership boundary to match Google AI Edge Gallery:

1. Set `ExperimentalFlags.enableSpeculativeDecoding = false` before Engine construction/initialization.
2. Leave `ConversationConfig.enableSpeculativeDecoding = null`, so the session inherits the Engine-level OFF state instead of forcing a session-level `false` override.
3. Explicitly set `ExperimentalFlags.enableConversationConstrainedDecoding = false` before `createConversation()`.
4. Restore the previous global ExperimentalFlags after the probe exits normally.

This isolates whether the native abort is caused by the R4 session-level speculative-decoding override. No production Summary/RAG/Q&A path is added.

## R5 QA gate

First test **CPU only**.

PASS condition: process remains alive, `CONVERSATION_READY` is crossed, generation completes, and diagnostics are produced.

FAIL condition: process restarts or dies. Reopen the P0 page and capture `上次 Probe 诊断` before any GPU test.

If CPU still dies at `CONVERSATION_CREATING`, the next isolation must remove the explicit sampler/ConversationConfig and test the official `engine.createConversation()` minimum path before changing model artifact, runtime version, or KV size.
