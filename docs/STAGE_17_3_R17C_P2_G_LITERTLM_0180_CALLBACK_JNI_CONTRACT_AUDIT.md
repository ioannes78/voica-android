# Stage 17.3 R17C-P2-G — LiteRT-LM 0.18.0 Kotlin/JNI callback contract audit

Status: **SOURCE AUDIT COMPLETE / NATIVE ROOT CAUSE UNRESOLVED / GPU ANR FAIL / NO DEVICE TEST**.
Repository source of truth: \`ioannes78/voica-android\`; PR #37 OPEN/DRAFT.
Source baseline reviewed: \`9678e9c6f3b960a6bce7baeeb6b07f9271903cf6\`.
Target branch: \`stage17-fully-offline-intelligence\`; production \`main\` unchanged.

## P2-F preceding gate (verified, not merely planned)

- Mock-only JNI thread-affinity test source:
  \`engine/local-llm/src/test/kotlin/io/github/ioannes78/voica/localllm/Stage17P2FMockJniThreadAffinityTest.kt\`.
- 6 test scenarios: cross-thread callback / same owner thread / acknowledged
  cancellation / missing terminal acknowledgment quarantine / release error /
  synchronous send failure / concurrent request denial.
- Fixed test-only dispatcher cancellation classification in \`9678e9c6\`.
  Nested \`withContext(NonCancellable) { withContext(ownerDispatcher) { ... } }\`
  prevents a successful fake owner-thread cancellation operation from being
  mistaken for a failed native signal when returning to a cancelled caller.
- Android PR CI **SUCCESS** (run \`43002993615\`), Stage 17.3 QA APK build
  **SUCCESS** (run \`43002993547\`). Success does **not** qualify GPU.
- P2-F uses fake callbacks and executor under \`src/test/\` only. It does not
  establish behavior of a real GPU delegate, JNI thread pool or Android HWUI.

## Sources and provenance

**Project actual code / release dependency**
- \`engine/local-llm/build.gradle.kts\`: pinned
  \`com.google.ai.edge.litertlm:litertlm-android:0.18.0\`.
- \`engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LiteRtLocalLlmRuntime.kt\`:
  \`withContext(Dispatchers.IO)\`, native engine lifecycle, callback terminal.
- \`engine/local-llm/src/main/kotlin/io/github/ioannes78/voica/localllm/LocalLlmRuntimeCoordinator.kt\`:
  mutex, progress observer, poison/release semantics.
- \`app/src/main/java/io/github/ioannes78/voica/Stage17R5CrashBreadcrumb.kt\`:
  last persisted phase and previous-process Android exit information.
- \`app/src/main/java/io/github/ioannes78/voica/Stage17R17CP2DAnrClosure.kt\`:
  fail-closed GPU/AUTO QA containment, preserved.

**Upstream exact v0.18.0 tag source**
- LiteRT-LM release v0.18.0 (tag commit reported as \`b2f686e\`):
  https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.18.0
- \`Conversation.kt\` from **v0.18.0**, not moving \`main\`:
  https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt
- \`Engine.kt\` from **v0.18.0**:
  https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Engine.kt
- Current Android integration guide (guidance, NOT proof of 0.18.0 JNI affinity):
  https://developer.android.com/agents/skills/device-ai/litert/litert-lm/skill
- Historical issue on \`0.13.1\`/\`0.16.0\`:
  https://github.com/google-ai-edge/LiteRT-LM/issues/3266

**Evidence limit:** This audit could read the v0.18.0 GitHub tag's Kotlin sources,
but could NOT independently download and disassemble the published 0.18.0
Google Maven AAR/native \`.so\` in the current environment. The tag source
and release coordinate align by version only. Byte-for-byte AAR correspondence,
compiled symbol behavior, GPU delegate/JNI native worker behavior, and
device-specific driver details remain **UNVERIFIED**.
Do not label this work an AAR binary or native-code audit.

## Direct source comparison: what v0.18.0 says

| API boundary | Upstream v0.18.0 observable behavior | Existing WENZHI usage | Status / limit |
| --- | --- | --- | --- |
| \`Engine.initialize()\` | Uses synchronized engine lock; enters \`nativeCreateEngine\`; documentation recommends background execution | Called from \`Dispatchers.IO\` block | Off main: source-supported. One OS-thread lifetime: NOT guaranteed |
| \`Engine.createConversation()\` | Uses synchronized engine lock; invokes \`nativeCreateConversation\` | Called after initialize in same coroutine before first suspend | Ordered at source level; JNI behavior opaque |
| \`Conversation.sendMessageAsync(..., callback)\` | Prepares JSON and \`JniMessageCallbackImpl\`, then **synchronously enters** \`nativeSendMessageAsync\` API; asynchronous generation/callbacks need not mean the JNI submission call itself cannot block | WENZHI records \`GENERATING\` **before** calling \`sendMessageAsync\` | Cannot tell from R5 whether JNI submission returned |
| Callback \`onMessage/onDone/onError\` | JNI callback adapter delegates content and terminal callbacks; onDone may resubmit native work when automatic tool responses are pending; onError maps cancelled status to CancellationException | WENZHI waits on \`CompletableDeferred\`, content appended only locally, callback emits scalar phase | Native callback thread is not established by Kotlin source; return/terminal may be lost or delayed |
| Flow-based \`sendMessageAsync(): Flow\` | Its \`callbackFlow\` cleanup is \`awaitClose {}\`, with no \`cancelProcess()\` in cleanup | WENZHI correctly uses **callback-based**, not Flow-based overload, and explicitly calls \`cancelProcess()\` | Current wrapper avoids that Flow cancellation omission |
| \`Conversation.cancelProcess()\` | Checks conversation alive, invokes \`nativeConversationCancelProcess\` | On coroutine cancellation then waits for callback terminal (10s bounded) | No documented same-thread requirement in the inspected Kotlin method; native termination is not guaranteed by mere cancel-call return |
| \`Conversation.close()\` | Atomically flips alive state then invokes \`nativeDeleteConversation\` | Only after termination acknowledged; uncertain native ownership quarantined | JNI delete can block; exact driver wait chain unverified |
| \`Engine.close()\` | Synchronized on engine lock, invokes \`nativeDeleteEngine\` | After Conversation.close, unless quarantined | Source ordering confirmed; no native close latency bound |

**Do not conflate:** Kotlin \`synchronized(lock)\` inside Engine is NOT a
global GPU graphics mutex and does not serialize Android HWUI/RenderThread.
Upstream source does NOT explicitly establish a same-thread contractual
requirement across \`initialize\`, \`sendMessageAsync\`, \`cancelProcess\`, and \`close\`.
The P2-F single-owner executor is a sensible **isolation hypothesis**,
NOT a proven LiteRT-LM 0.18.0 API requirement or an ANR fix.

## Key revision to our interpretation of C2-B ANR

User-provided last R5 breadcrumb:
\`GPU / GENERATING / emissionCount=0 / 1988 estimated tokens / ANR(6)\`.
In WENZHI adapter, the order is:

\`\`\`
onPhase(GENERATING)                     # durable R5 write
liveConversation.sendMessageAsync(...)  # Java -> JNI submission entry
terminal.await()                        # only after sendMessageAsync returns
\`\`\`

The breadcrumb cannot distinguish:
1. **SUBMISSION BLOCKED:** Java/JNI \`nativeSendMessageAsync\` has not returned.
2. **SUBMISSION RETURNED:** the submission completed and the caller is
   suspended waiting for the first or terminal callback.
3. **CALLBACK NOT OBSERVED:** a native error/terminal occurred internally but
   never reached Kotlin, or was delayed. The last observed phase is not
   absolute proof that no internal native work occurred.

This is a material diagnostic gap. A \`GENERATING\` label is not evidence that
decode reached first-token generation. No root cause (driver deadlock, OOM,
worker starvation, ABI mismatch, JNI thread affinity) can yet be selected.

## Historical upstream issue — limited relevance

Issue \`#3266\` reports missing terminal callbacks and callback-thread-pool
timeout for **older** Android versions \`0.13.1\` and \`0.16.0\`; it also
describes an OpenCL kernel failure in one test. It is strong precedent that
callback/terminal handoff warrants investigation, but it is **not proof** the
same bug persists in \`0.18.0\`, nor that it caused this WENZHI GPU ANR.
We must not automatically change to synchronous \`sendMessage()\` in product
based on this report.

## P2-G gate and next safe design

**P2-G deliverable:** version-specific tagged Kotlin API review and
evidence matrix. No new APK, no native calls and no real-device request.

**Proposed P2-H (requires a separate implementation review):**
1. Design a **content-free** QA phase boundary for
   \`SEND_ENTERED\` -> \`SEND_RETURNED\` -> \`TERMINAL_AWAIT_ENTERED\`,
   before and after \`sendMessageAsync\` and before deferred terminal wait.
   Encode timestamps and (safe, non-identifying) thread labels without any
   transcript, prompt, generated text, raw ANR trace or personal data.
2. Move potentially blocking R5 persistence work out of the JNI callback
   and evaluate correctness against fake callbacks first. Avoid changing
   production callback semantics until reviewed.
3. Test missing terminal, callback race, and native close cancellation using
   deterministic JVM fakes and test timeouts; no real GPU.
4. Only if approved later, package an isolated CPU-only diagnostic build.
   GPU remains disabled by P2-D; do not silently flip \`GPU_QA_SUSPENDED\`.
5. Do **not** insert a naïve \`withTimeout\` around JNI as a supposed native
   deadlock fix: coroutine cancellation cannot forcibly unblock native C++.

## Final restrictions

- GPU real-device gate: **ANR FAIL / SUSPENDED**.
- P2-F mock CI: **SUCCESS**; no product behavior changed.
- Native 0.18.0 AAR artifact equality/behavior: **not independently verified**.
- P2-G result: **upstream-tag source review**, not proven root-cause isolation.
- \`main\` remains untouched, PR #37 stays OPEN/DRAFT.
