# Stage 17.3 R17C-P2-H — JNI send-return and callback diagnostic protocol

Status: **MOCK-ONLY IMPLEMENTATION / CI PENDING / GPU ANR FAIL / NO REAL DEVICE TEST**.
Repository: \`ioannes78/voica-android\`, PR #37 remains OPEN/DRAFT.
Source baseline: \`984d8c21d686f5f6d9286acdc5ca12af06773ec5\`.
No changes to production \`main\`, model/Room/assets or LiteRT-LM 0.18.0 runtime adapter.

## Why P2-H exists

The historic P2-C2-B ANR record is:
- requested/resolved GPU/GPU; estimated prompt 1,988 tokens;
- native JSON schema disabled; interactive/unlocked;
- last persisted phase \`GENERATING\`; \`emissionCount=0\`;
- previous process exit \`ANR(6)\`;
- main \`pthread_cond_wait\` / HWUI, RenderThread \`libsync.so:sync_wait\`
  plus broad trace marker matches.

Current \`LiteRtLocalLlmRuntime\` signals \`GENERATING\` **before**
entering \`Conversation.sendMessageAsync\`, and then awaits a deferred terminal
callback only **after** the submission call returns. Thus R5 cannot distinguish
a blocking synchronous JNI submission from returned submission without a
callback; it cannot prove decode or GPU driver execution had started.

P2-G compared the pinned 0.18.0 version to the upstream version-tagged
Kotlin API. It did **not** prove that the published Maven AAR matches the
tag byte-for-byte, nor confirm native scheduler, JNI worker, or driver
threading behavior.

## Implemented mock-only evidence model

Implementation:
\`engine/local-llm/src/test/kotlin/io/github/ioannes78/voica/localllm/Stage17P2HMockSubmissionBoundaryTest.kt\`

The test harness is JVM source-only, imports **no LiteRT-LM or Android
hardware code**, does not invoke Engine/Conversation and cannot execute GPU.
It defines a bounded, content-free, thread-safe phase ledger.

The proposed phase boundaries are:

| Phase | Only what it means |
| --- | --- |
| SEND_ENTERED | Wrapper is about to enter synchronous Java/JNI submission |
| SEND_RETURNED | The Java/Kotlin \`sendMessageAsync\` submission call returned |
| TERMINAL_AWAIT_ENTERED | Caller began waiting for an asynchronous terminal callback |
| FIRST_EMISSION | One first callback observed across JNI; no text retained |
| TERMINAL_DONE | Done callback observed |
| TERMINAL_ERROR | Error callback observed, including cancellation acknowledgment |
| CANCEL_REQUESTED | Client began simulated cancellation |
| CANCEL_SIGNAL_SENT | Mock cancel signaling operation was invoked |
| CANCEL_ACK_TIMEOUT | Fake terminal did not arrive within test-only bounded wait |
| RELEASE_BLOCKED | Protocol refuses close when terminal ownership is unknown |

The mock ledger stores **only enum phases and elapsed milliseconds**,
capped at 32 records per attempt. It intentionally cannot record prompt,
transcript, generation, stack trace, file path, process/session IDs,
recording name or native thread identity. No synchronous disk/database
write occurs in any fake callback.

### Snapshot interpretation

| Snapshot | What can be concluded | What cannot be concluded |
| --- | --- | --- |
| SEND_ENTERED, absent SEND_RETURNED | Submission wrapper has not observed return | Underlying native worker deadlocked or GPU decode definitely started |
| SEND_RETURNED, no callbacks | Submitted and waiting for first callback | No native computation occurred |
| SEND_RETURNED + FIRST_EMISSION, no terminal | A callback arrived; terminal still unobserved | Native generation is still running |
| TERMINAL_DONE without FIRST_EMISSION | Terminal callback with zero output observed | Output was valid or useful |
| TERMINAL_ERROR without FIRST_EMISSION | Error callback with zero output observed | The error originated in GPU driver |
| CANCEL_ACK_TIMEOUT | Mock terminal not acknowledged within test bounded window | A real native timeout can safely free engine resources |
| callback before SEND_RETURNED | Callback and submission return order can race | Non-return implies no callback possible |

The status classifies **observed boundaries only**. It is not a semantic model
quality test, ANR detector, native watchdog or proof of an Android thread wait chain.

## Deterministic simulated test matrix

1. Simulated synchronous JNI submission blocks on a latch; assert
   SEND_ENTERED and absence of SEND_RETURNED; explicitly release latch.
2. Submission returns normally but the fake never calls back; a **test-only**
   timeout observes awaiting-first-callback state.
3. Native fake invokes onDone synchronously during submission; ensure
   callback can precede SEND_RETURNED without being erased.
4. A foreign fake callback thread emits first output but no terminal; keep
   this separate from a zero-callback case.
5. Zero-emission terminal-error callback produces a distinct state.
6. Fake cancellation without terminal acknowledgment gives RELEASE_BLOCKED,
   not a successful close.
7. Acknowledged mock cancellation records terminal error, not successful generation.
8. Bounded ledger and first-emission dedup prove minimal metadata footprint.

**Important:** a test harness timeout is never used as an excuse to close
real native resources. Do not wrap the current LiteRT-LM JNI submission in a
naive \`withTimeout\` or assume coroutine cancellation can kill blocked C++.

## Acceptance gates

- Android PR CI must pass exact-commit \`:engine:local-llm:testDebugUnitTest\`.
- P2-E/P2-F static guard also checks P2-H fake remains in \`src/test\`,
  imports no \`com.google.ai.edge.litertlm\`, and leaves GPU quarantine on.
- This task does **not** modify runtime \`LocalLlmRuntimePhase\`, R5 schema,
  \`sendMessageAsync\` call order or CPU/GPU scheduling.
- Stage 17.3 diagnostic APK workflow may run automatically from a PR push,
  but its build success is not a P2-H hardware qualification and installation
  is not requested.
- GPU stability remains **ANR FAIL / SUSPENDED**; no GPU test authorized.

## Next engineering gate — optional P2-I

Only after P2-H CI and a separate review, consider a QA-only **explicit CPU**
diagnostic experiment:
- Add metadata-only \`SEND_ENTERED\`, \`SEND_RETURNED\`,
  \`TERMINAL_AWAIT_ENTERED\` boundaries while preserving existing callback and
  cancellation behavior.
- Avoid synchronous per-token SharedPreferences and PSS reads on the native
  callback thread. Provide a bounded evidence buffer with off-callback
  persistence and verified lifecycle behavior.
- Retain pre-native GPU/AUTO blockade and product AUTO+schema CPU safety.
- Include race/error/cancellation JVM tests before any CPU-on-device work.
- Real GPU requalification remains a different future stage with separate
  explicit user approval. No promotion based on mock test PASS.

## References

- \`docs/STAGE_17_3_R17C_P2_D_GPU_ANR_SAFETY_CLOSURE.md\`
- \`docs/STAGE_17_3_R17C_P2_E_GPU_HWUI_STATIC_AUDIT.md\`
- \`docs/STAGE_17_3_R17C_P2_F_MOCK_JNI_THREAD_AFFINITY.md\`
- \`docs/STAGE_17_3_R17C_P2_G_LITERTLM_0180_CALLBACK_JNI_CONTRACT_AUDIT.md\`
- https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt
