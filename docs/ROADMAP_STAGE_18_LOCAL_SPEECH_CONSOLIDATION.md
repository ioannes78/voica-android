# Stage 18 — Local Speech Quality & Latency Consolidation

状态：**RESERVED / MOVED FROM FORMER STAGE 16C / NOT STARTED**

修订日期：2026-10-23

本文件承接原 `docs/ROADMAP_STAGE_16C_RESERVED.md` 中已确认的本地语音质量/延迟收敛方向，并将其整体后移为 Stage 18。

Stage 18 位于 Stage 17 Fully Offline Intelligence 之后。

本阶段不承担云端文件 ASR、云端 realtime ASR 或 online AI。

---

## 18.0 目标

把当前已经验证的本地 realtime/file 能力进一步收敛为统一、低延迟、可维护的本地语音 pipeline：

1. realtime 与 file 统一到 FSMN-first VAD；
2. realtime 使用低延迟 Zipformer first-pass + 异步 FunASR second-pass refinement；
3. 建立统一 Sentence Composer；
4. 对 diarization 做证据驱动的性能优化；
5. 完成完整本地语音链回归。

Stage 18 不重新做无证据的大规模模型选型。

---

# Stage 18.1 — Unified FSMN VAD

状态：**RESERVED / NOT STARTED**

目标策略：

```text
Local File:
Canonical PCM 16 kHz mono
→ FSMN-VAD primary
→ Silero fallback
→ file ASR

Realtime:
Canonical realtime PCM 16 kHz mono
→ FSMN-VAD true-streaming adapter primary
→ Silero streaming fallback
→ realtime ASR
```

TEN-VAD 不进入当前 production 路线，除非未来出现新证据和独立授权。

### True-streaming 要求

FSMN realtime 必须符合现有 `StreamingVadEngine / StreamingVadSession` contract。

不得把文件型 `VadEngine.analyze()` 对短 chunk 反复重开后冒充 streaming。

目标生命周期：

`open session`
→ `acceptSamples(...)`
→ `acceptSamples(...)`
→ `...`
→ `finishInput()`

FSMN native cache、endpoint state 和 canonical sample offsets 必须跨 chunk 连续。

### File / realtime parity

相同 canonical WAV 同时进入：

- Offline FSMN；
- Streaming FSMN（模拟 realtime chunk input）。

至少比较：

- speech segment count；
- start/end sample；
- total speech duration；
- short/long pause boundary；
- chunk-size independence。

允许的 tolerance 必须显式定义，不能依赖模糊 wall-clock 近似。

---

# Stage 18.2 — Realtime Two-Pass ASR

状态：**RESERVED / NOT STARTED**

目标：

> **低延迟 Zipformer first-pass + FunASR asynchronous high-quality second-pass。**

架构：

```text
Canonical PCM
      ↓
FSMN streaming VAD
      ↓
Zipformer realtime ASR
      ↓
PARTIAL → STABLE → FIRST_FINAL
      │
      ├→ UI immediately
      │
      └→ completed canonical speech ranges
                 ↓
       bounded refinement queue
                 ↓
            FunASR Nano
                 ↓
      refined text + CTC timing
                 ↓
        SECOND_PASS_FINAL
```

### First-pass 优先级

second-pass 绝不能阻塞：

- audio capture；
- canonical source；
- FSMN streaming VAD；
- Zipformer realtime ASR；
- PARTIAL/STABLE/FIRST_FINAL UI。

资源不足时宁可延后/暂停 refinement，也不能增加 realtime first-pass latency 或丢音频。

### Reuse speech truth

FunASR second-pass 直接消费 realtime FSMN 已确认的 completed canonical speech range。

不得重新跑一遍 VAD。

### Refinement scheduler

至少满足：

- bounded queue；
- single worker 或严格受控并发；
- memory/thermal/backlog pressure 下可暂停；
- STOP 后只 drain pending；
- 已 refined 不从头重算；
- cancellation/status 可追溯。

### CTC timing

继续复用 Stage 16B P5 R5 已验证的 same-stream CTC 能力，不建立第二套时间真值。

### Human edit precedence

后台 second-pass 不得静默覆盖人工文本编辑。

人工修改优先级高于后续自动 refinement。

second-pass failure/OOM/model missing 时保留 first-pass FINAL。

---

# Stage 18.3 — Sentence Composition V2

状态：**RESERVED / NOT STARTED**

目标：不再采用“一段 VAD = 一句话”或“speaker 一变 = 一定切句”的简单规则。

组合输入：

`FSMN boundary`
+ `ASR token/CTC timing`
+ `punctuation`
+ `speaker evidence`
+ `text continuity / fragment rules`
→ `Sentence Composer`

### Hard boundary

例如：

- canonical discontinuity；
- 明确 control/pause boundary；
- media gap；
- file/session start/end；
- 用户人工边界。

### Soft boundary

例如：

- `。！？!?`；
- speaker turn；
- medium silence；
- punctuation；
- token spacing；
- long sentence limit；
- very-short-fragment merge evidence。

speaker change 只有在其它证据支持时才作为强句界；短暂弱 speaker evidence 不得直接砍碎完整句子。

File/realtime 共享核心 composition policy，但允许 realtime endpoint latency、PARTIAL/STABLE、trailing silence 等 consumer policy 不同。

---

# Stage 18.4 — Diarization Performance V2

状态：**RESERVED / NOT STARTED**

P5 R5 已完成当前 correctness 收敛。本阶段优先保护：

- AUTO speaker correctness；
- fixed speaker-count independence；
- weak short false-speaker guard；
- overlap/manual assignment；
- canonical alignment semantics。

优化必须先 profile，再按证据排序。

至少拆分：

- VAD reuse/input prep；
- segmentation；
- embedding；
- clustering；
- stitching；
- transcript alignment；
- total wall time / RTF；
- invocation/window/embedding counts；
- duplicated audio workload；
- RAM/PSS；
- CPU/thermal。

优先评估：

- 复用已有 FSMN VAD；
- 减少重复 segmentation/embedding；
- bounded batch/vectorization（仅 runtime/RAM 证据支持时）；
- 减少无 speech 区域重型处理；
- window/overlap workload；
- clustering/stitching scheduling。

不得为了速度无证据恢复已证明无效的 cache 或牺牲 speaker correctness。

---

# Stage 18.5 — Full Speech Pipeline Regression

状态：**RESERVED / NOT STARTED**

完整回归至少覆盖：

- BLE realtime source；
- phone microphone source；
- canonical PCM/timeline；
- FSMN streaming/file；
- Silero fallback；
- Zipformer realtime first-pass；
- FunASR second-pass；
- SenseVoice file ASR；
- CTC；
- Sentence Composer；
- diarization；
- AUTO/fixed speaker；
- overlap/manual assignment；
- START/STOP/PAUSE/RESUME；
- reconnect/discontinuity；
- background/screen-off；
- long recording；
- memory/thermal/cancellation。

Stage 18 完成后形成新的本地 speech pipeline Freeze/Handoff。

---

## Stage 18 边界

Stage 18 不承担：

- cloud ASR；
- online AI；
- Gemma/LLM runtime；
- SenseVoice Rich Audio Metadata；
- 跨录音 Speaker Voiceprint；
- 无证据的大规模模型替换。

正式实施前仍需重新核验 GitHub，并按 requirements → user approval → development plan → user approval 的 Gate 进入开发。
