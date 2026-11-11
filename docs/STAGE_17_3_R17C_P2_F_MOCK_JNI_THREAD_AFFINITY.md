# Stage 17.3 R17C-P2-F — Mock-only JNI thread-affinity protocol

Status: IMPLEMENTED IN JVM UNIT TEST SOURCE / CI PENDING / NO REAL GPU QUALIFICATION.
Repository: ioannes78/voica-android, PR #37 OPEN / DRAFT.
Basis: P2-E audit HEAD 41ab2398a7a620746eed5968ad7b9aa07a390d97.
main remains untouched. The development branch must not merge at this gate.

## Decision

**Do not refactor the running LiteRT-LM 0.18.0 adapter yet.** The last real
GPU attempt P2-C2-B exited with Android ANR(6), at GENERATING / 0 emissions,
input estimate 1988, native JSON schema disabled, no screen lock, and Android
HWUI/graphics synchronization wait symbols. This is a failure of GPU
stability, **not** evidence of one specific deadlock or of a LiteRT-LM
same-thread contractual requirement.

The P2-E source audit found that \`LiteRtLocalLlmRuntime.generate()\` wraps
native calls in \`withContext(Dispatchers.IO)\`. That avoids a direct main
thread call but does not guarantee OS-thread affinity across suspension at
\`CompletableDeferred.await()\`, cancellation and resource close.

Changing dispatchers in a production model adapter with an unresolved native
GPU ANR is not an acceptable diagnostic strategy. P2-F therefore uses a
**test-source-only fake interface** under
\`engine/local-llm/src/test/kotlin/.../Stage17P2FMockJniThreadAffinityTest.kt\`.
It is compiled/run by \`:engine:local-llm:testDebugUnitTest\` and NEVER linked
into the product or QA APK. It imports no \`com.google.ai.edge.litertlm.*\`,
does not create GPU objects, and cannot communicate with a device driver.

## Proposed ownership protocol — prototype only

\`\`\`text
caller / coroutine / UI
    |
    v
P2FMockSingleOwner.generate()   [one in-flight call; sticky quarantine]
    |
    +----> dedicated single-thread executor ("P2F-mock-jni-owner")
    |         initialize()
    |         createConversation()
    |         sendMessageAsync(callback)
    |                  |
    |                  v
    |        fake native callback may execute on any other thread
    |                  |
    +<---- CompletableDeferred terminal Done / Failed (nonblocking await)
    |
    | cancellation:
    |   send cancelProcess() ON SAME owner thread
    |   wait for explicit terminal callback WITH BOUNDED ACK WINDOW
    |   if no callback / cancel error: QUARANTINE, DO NOT CLOSE
    |
    +----> SAME owner thread:
              closeConversation() -> closeEngine()  [only if terminal known]
\`\`\`

Strict invariants:

1. All fake native API calls are owned by **one named executor OS thread**,
   even when the coroutine suspends at an asynchronous callback or is canceled.
2. The owner executor is **not** blocked waiting for the callback; the fake
   callback can complete from a separate thread.
3. A single occupied flag prevents overlapping inference; a sticky poisoned
   flag refuses further inference after unconfirmed termination.
4. If cancellation occurs during generation: post \`cancelProcess()\` to the
   owner; wait for a terminal callback without blocking that owner; only
   release after acknowledgment.
5. Unknown native state (cancel signal failure, missing acknowledgment,
   synchronous send failure, initialization/create outcome not confirmed,
   or release exception) must not be represented as a clean IDLE or a
   successful object close. Conservative quarantine may retain native
   references in a real implementation; this is deliberate fail-closed
   behavior, not an automatic resource-repair method.
6. A complete or terminal-error callback allows ordered closing, but
   \`closeConversation\` failure quarantines and prevents \`closeEngine\` from
   being called blindly.
7. The test harness shuts down its own mock executor after its tests. It
   cannot recover or terminate a genuinely hung JNI call.

### Cancellation dispatcher-hop finding during CI (test-only)

Initial PR CI run 43001759688 executed 42 local-LLM tests with one failure:
`cancelUsesSameOwnerAndClosesOnlyAfterNativeAcknowledges`. Merely waiting for
the async terminal-await state in revision 1b5dbf17 did **not** fix the failure
(PR CI run 43002387123 failed the same test).

P2-F mock audit identified a concrete risk in the original fake protocol:
`withContext(NonCancellable + dispatcher)` from an already-cancelled parent
changes dispatchers. Kotlin coroutine prompt-cancellation behavior when
resuming to the original cancelled context can surface a CancellationException
**even after** `cancelProcess()` completed successfully on the owner.
Using outer `runCatching { ... }.isSuccess` as evidence that the native cancel
signal was sent would then falsely poison the mock session and skip safe
close. That is a **protocol-design bug in the test-only experiment**; it does
not establish what happened in the production GPU ANR.

The revised fake uses a nested non-cancellable owner operation:

```kotlin
withContext(NonCancellable) {
    withContext(ownerDispatcher) { nativeOperation() }
}
```

This keeps the caller in an uncancellable scope while the owner-thread
dispatch runs, and distinguishes actual native-operation exceptions from
prompt cancellation of the surrounding caller. The same helper manages
explicit cancel and ordered close. It remains **mock-only**, with no changes
to production `LiteRtLocalLlmRuntime` or current GPU quarantine.
Final CI status must be verified on the revised exact commit.

## Unit-test matrix

| Mock test | Expected result |
| --- | --- |
| Foreign-thread callback after async send | initialize/create/send/close/closeEngine on identical OS thread |
| Cancellation + native acknowledgment | owner-thread cancel, then owner-thread close; no poison |
| Cancellation with missing native terminal | quarantine, no close, no second attempt |
| Native terminal error + close failure | close attempts on owner, poison, no blind engine close |
| Synchronous sendMessageAsync exception | quarantine, no close, no retry |
| Concurrent request while one generate is active | second request rejected before creating second session |

Tests use fake in-memory objects and short bounded asynchronous coordination.
These are *functional properties of the fake protocol only*; they do not
validate how LiteRT-LM actually schedules its callbacks or GPU work.

## Explicitly excluded

- No real \`Engine\`, \`Conversation\`, GPU delegate, OpenCL/Vulkan, Android
  instrumented test, or hardware workload.
- No \`Dispatchers.IO\` replacement in the current native adapter.
- No change to cancellation timeout or production retry policy.
- No model installation, Room schema, BLE, summaries, PSS collection, R5
  observer, Android graphics rendering or user-data path changes.
- No LiteRT-LM 0.18.0 -> 0.18.1 upgrade and no GPU production promotion.
- No QA APK is needed to validate a JVM-only test. The repo's existing
  Stage 17.3 workflow may nevertheless build one automatically on PR push;
  that workflow result is not a GPU acceptance gate and need not be installed.

## Gate and next steps

P2-F gates:
- \`:engine:local-llm:testDebugUnitTest\` included in Android PR CI;
- full Android PR CI SUCCESS on the exact commit;
- source-discipline proof: test lives strictly under \`src/test\`,
  does not reference LiteRT-LM and no product adapter was modified;
- P2-D fail-closed GPU/AUTO quarantine unchanged.

**No real-device GPU PASS is expected or permitted in P2-F.**

Next possible P2-G (requires separate review): audit pinned 0.18.0 AAR/JNI
threading semantics and compare current callback and cancellation behavior to
the fake protocol. Then decide whether a wholly isolated engine
implementation merits further non-GPU verification. A later hazardous
real-device GPU test would require fresh explicit authorization and
independent containment, never an automatic stage progression.
