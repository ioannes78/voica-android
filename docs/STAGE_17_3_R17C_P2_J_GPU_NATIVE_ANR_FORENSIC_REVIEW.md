# Stage 17.3 R17C-P2-J — GPU ANR forensic review (source + packaged-binary evidence)

Status: **STATIC FORENSIC REVIEW COMPLETE / GPU ROOT CAUSE UNRESOLVED / GPU REAL-DEVICE ANR FAIL-SUSPENDED**.

Repository: `ioannes78/voica-android`, branch `stage17-fully-offline-intelligence`, PR #37 OPEN/DRAFT/UNMERGED. Baseline before this document: `db2706814da07ee8f56834e0a68c4a8f563169ff`. Production `main` was `28ec9a837c5b0debac8e6d32ab99e0cf8d80be12`.
No product/QA runtime changes, model upgrade, test unlock, device commands, Room/asset changes or native compilation in P2-J.

## Scope and evidence provenance

This review uses: the repository's pinned 0.18.0 adapter and safety guard; P2-D/E/G/H/I evidence; user-supplied prior-process R5 GPU ANR breadcrumb; user-supplied P2-I CPU QA1/QA2; public upstream reports; and an **offline ELF inspection of the actual signed QA APK** produced by GitHub workflow `43238303040` from code SHA `d5633c513f493d30c15ccef02d799b1e73c1e01e`.

The QA APK SHA-256 was independently confirmed in this review:
`6fa1c7efd7a648ed72fac27323c16ed662464b4f12c2b0cb85c4ea9cfc1949a8`.
No actual GPU inference was run and **no complete native ANR trace** was obtained.

The independently inspected binary is an **APK-extracted** `lib/arm64-v8a/liblitertlm_jni.so`, NOT an independently obtained Google Maven 0.18.0 AAR or Google's native build artifacts. This review does not prove that the upstream AAR and packaged native library are byte-identical. The published AAR could not be independently retrieved in this environment.

## 1. Discriminating CPU and GPU evidence

| Boundary | Explicit CPU P2-I short probe | Historical GPU P2-C2-B |
| --- | --- | --- |
| Runtime | 0.18.0 | 0.18.0 |
| Requested/resolved | CPU/CPU, no fallback | GPU/GPU |
| Request class | Short local probe | Real medium transcript prefix, est. 1,988 tokens, native JSON schema off |
| SEND_ENTERED | +6866 ms | NOT INSTRUMENTED at incident time |
| SEND_RETURNED | +6870 ms; wrapper call took ~4 ms | UNKNOWN |
| TERMINAL_AWAIT_ENTERED | +6870 ms | UNKNOWN |
| FIRST_EMISSION | +10453 ms | No emission observed in last R5 breadcrumb |
| TERMINAL_DONE | +11952 ms | NOT OBSERVED |
| Last R5 phase / system exit | Successful local answer and post-close resource sample | GENERATING / emissionCount=0; Android ANR(6) |
| Post-close PSS | 380,884 KiB | No successful post-close sample |
| Exit-time PSS | N/A | 2,014,444 KiB |

Explicit CPU runtime telemetry: initialize 934 ms, conversation create 7 ms, TTFE 3348 ms, generation 4095 ms, peak PSS 1,364,550 KiB. The produced response was `我正在本地设备上运行。`.
P2-I observation **does not** include independent coordinator `IDLE` state; neither CPU sustained-run stability nor GPU function is proven.

The old GPU GENERATING marker was committed **before** entering `sendMessageAsync`; it cannot tell whether that Java/JNI submission returned. The ~11s interval from the last durable breadcrumb to exit metadata is not a measured request duration. GPU last R5 PSS 2,032,921 KiB and exit PSS 2,014,444 KiB are high but do not establish low-memory killing (Android exit was ANR, not LOW_MEMORY) or rule out graphics memory/resource pressure.

## 2. Binary evidence from the exact QA APK

Inspection used the APK as a ZIP, SHA-256 on the extracted `.so`, `readelf -d`, `readelf -Ws` and printable `strings`. It did not run or disassemble the GPU work scheduler.

- `lib/arm64-v8a/liblitertlm_jni.so` is present; unpacked size 22,155,672 bytes.
- Its SHA-256 is `4e724d6a4bdb56051b77d4899c801495b410352e986eabe31576a183462a5e10`.
- ELF dynamic dependencies include `libGLESv2.so`, `libGLESv3.so`, `libEGL.so`, `libandroid.so`, `liblog.so`, `libdl.so`, `libc.so` etc.
- Dynamic JNI exports include `LiteRtLmJni_nativeSendMessageAsync`, `nativeConversationCancelProcess`, `nativeDeleteConversation`, `nativeDeleteEngine` and `nativeCreateEngine`.
- Printable strings include OpenCL backend/sampler names and diagnostics such as `Failed to load OpenCL for LiteRT` and `Waiting for %zu OpenCL events`.
- The APK does not bundle a `libOpenCL.so`; that alone is **not** a defect: the Android manifest advertises the optional vendor/system OpenCL library.

Interpretation: the actual shipped APK has the native JNI entrypoints and embedded graphics/backend code paths necessary for the wrapper. **Symbols/strings do not demonstrate which path executed during the historical ANR, whether the driver supplied an OpenCL context, which GPU fence was waited on, or whether a runtime callback was dropped.** Neither an ABI mismatch nor a driver defect has been proven by these checks. No claims of byte-for-byte AAR provenance are made.

## 3. Evidence-weighted hypotheses, not root-cause declarations

### H1. GPU context contention / Android HWUI frame synchronization — PRIORITY TO INVESTIGATE

R5's main-thread subset contains `pthread_cond_wait` and `libhwui.so`; RenderThread contains `libsync.so:sync_wait`, `libui` and `libgui`. These are compatible with graphics synchronization pressure, **not a demonstrated wait-for cycle**. Prior P0 GPU probes did pass: the problem is workload/device/lifecycle sensitive rather than universal GPU initialization failure.

Independent upstream context:

- [LiteRT-LM #3992](https://github.com/google-ai-edge/LiteRT-LM/issues/3992), Oct 15 2026: 0.18.0 Gemma 4 E2B GPU tests reported UI stalls; an **experimental rebuilt native AAR** with low-priority GPU context substantially reduced frame pauses. Its device, JNI patch and workload differ from WENZHI's. The Kotlin API does **not** expose this setting as documented by the reporter; do not retrofit their patch directly.
- [LiteRT-LM #4315](https://github.com/google-ai-edge/LiteRT-LM/issues/4315), Oct 30 2026: initialization on a background thread still froze UI frames for ~580 ms on another phone, in 0.18.0 and 0.19.0. That event was during initialize, whereas WENZHI's last stored phase was GENERATING. It is analogous graphics contention, not proof of the same defect or a fix.

The incident has no complete RenderThread/worker/driver timeline, context IDs, fence owners or Perfetto trace. A GPU context-priority modification is NOT approved or proven safe.

### H2. JNI submission/callback terminal handoff failure — IMPORTANT OBSERVABILITY GAP

Pinned source calls `onPhase(GENERATING)` before synchronously entering `Conversation.sendMessageAsync`, and awaits terminal only **after return**. If JNI submission blocks, R5 shows GENERATING; if submission returns but native worker or callback hangs, R5 can show the **same** last marker.

[LiteRT-LM #3266](https://github.com/google-ai-edge/LiteRT-LM/issues/3266), opened Aug 17 2026, documents missing terminal callbacks and blocked closing in older Android versions 0.13.1/0.16.0. That report includes cases where output tokens had already arrived and a GPU error path was not forwarded; WENZHI's historical case had zero observed emissions. It is a relevant **failure category**, not a regression proof for 0.18.0, not proof of a GPU stall, and does not establish synchronous send blocked.

The P2-I bounded diagnostic now proves the CPU call returned on the tested phone. It was intentionally **CPU-only** and did not instrument the historical GPU attempt.

### H3. Lifecycle/JNI OS-thread affinity / cancellation & release uncertainty — UNVERIFIED

Current adapter uses `withContext(Dispatchers.IO)`, not a dedicated OS-thread executor. Suspending at `terminal.await()` permits a later resumed continuation to land on a different IO worker. Current Android integration guidance recommends single-thread background execution, but an exact 0.18.0 native affinity contract has not been established. P2-F/H JVM fakes model safe semantics; they are not GPU tests.

Prior incident occurred **before** an observed terminal, not during a confirmed `close()` or `cancelProcess()` call. Do not replace the adapter or install a coroutine `withTimeout` as a putative native GPU fix. Retain explicit quarantine after unacknowledged cancellation or release.

### H4. Instrumentation overhead / memory pressure — SECONDARY, NOT EXCLUDED

P2-E found the R5 observer did synchronous Debug.getPss and SharedPreferences commits in callback paths. In the historical incident `emissionCount=0`, so a per-`onMessage` logging stall is *not* directly supported; sampler/other instrumentation overhead remains a candidate. P2-I CPU now records first-emission via an off-callback persistence queue; it did not modify GPU behavior.

GPU PSS ≈ 2.0 GiB on this device indicates meaningful memory/graphics load but is insufficient to establish a memory leak, OOM or a driver fence root cause.

## 4. What is **not** evidenced / no-go assumptions

1. No full per-thread native ANR stack, native scheduler state, vendor GPU logs, graphics fence ownership, OpenCL context ID, Perfetto trace or complete bugreport from the failed GPU attempt.
2. No `SEND_RETURNED` phase from the failed historical GPU attempt; CPU data cannot fill that gap.
3. No independent exact-version Maven AAR-vs-APK native byte comparison.
4. No proof that moving work to a single-thread executor fixes GPU/HWUI contention.
5. No causal proof for a MediaTek-specific defect, or proof that 0.18.1/0.19.x would cure this issue.
6. No authorization to re-run GPU, enable AUTO on QA, test device lock/background GPU, republish models or merge PR #37.

## 5. Next gate proposal: P2-K forensic design (no GPU execution)

1. Preserve the fail-closed `Stage17R17CP2DAnrClosure.GPU_QA_SUSPENDED=true` and pre-native GPU/AUTO gate. Keep production AUTO+JSON-schema->CPU safety.
2. Draft an **isolated, explicit opt-in future GPU diagnostic** only as a plan first: single bounded attempt, small content-free prompt, separate process or demonstrably isolated runtime ownership, no automatic retries/close while native ownership is unknown, and user-visible abort conditions. Process isolation does *not* guarantee GPU driver or HWUI isolation.
3. If richer evidence is ever needed, define privacy-minimal capture of Java/JNI SEND_ENTERED/SEND_RETURNED/terminal phases plus Android main/RenderThread and worker wait chains. Do not save prompt/transcript or raw logs to an automatic external endpoint.
4. Review exact pinned 0.18.0 AAR/native provenance, device GPU/driver/OS characteristics, and full ANR type before attempting a runtime-version A/B.
5. A controlled GPU requalification requires **fresh explicit authorization**, reviewed safety design, separate build gates and one attempt at a time. One successful short GPU prompt would not close the historical 1,988-token ANR.
6. If safe evidence capture cannot be guaranteed, maintain CPU-only behavior for affected product use cases; do not treat a GPU repair as mandatory to advance offline summarization.

## Stage result

**P2-J = STATIC EVIDENCE COMPLETE, root cause UNKNOWN, NO GPU TEST.**
P2-I CPU QA remains PASS WITH EXPLICIT COVERAGE LIMITS. GPU test gate remains **ANR FAIL / SUSPENDED**. P2-J neither modifies native code nor authorizes another GPU attempt.
