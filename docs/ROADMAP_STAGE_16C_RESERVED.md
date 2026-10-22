# Stage 16C — Local Speech Quality & Latency Consolidation

状态：**RESERVED / REPLANNED V2 / NOT STARTED**

修订日期：2026-10-22

本文件取代 2026-10-20 版本中“Realtime VAD Matrix → Diarization → Segmentation”的旧执行顺序，作为 Stage 16C 后续需求/开发规划的最新路线预留。

Stage 16C 仍位于：

`Stage 16B — Local File ASR V2`

与：

`Stage 17 — 云端文件 ASR / 音频上传转写`

之间。

当前预留顺序：

`Stage 16B P5 R5 CLOSED`
→ `Stage 16B SenseVoice Rich Audio Metadata`
→ `Stage 16B final regression / Freeze / Handoff`
→ `Stage 16C`
→ `Stage 17`
→ `Stage 18`

本文件只更新路线：**不得开始编码，不得创建 Stage 16C branch/PR，不得修改 production model channel，不得修改 Room schema，不得改变当前 production realtime/file bindings。**

Stage 16C 只有在 Stage 16B 完成剩余开发、真机验收和 Final Freeze/Handoff 后，重新核验 GitHub 当前真实状态并完成 Stage 16C requirements / development-plan 确认，才允许实施。

---

# 16C.0 定位与目标

Stage 16C 新定位：

**Local Speech Quality & Latency Consolidation — 本地语音质量与性能收敛**

它不是新的“大模型选型阶段”，也不是重新做 Stage 16A / 16B。

目标是把已经验证的本地 realtime/file 能力收敛成更统一的产品级 pipeline：

1. realtime 与 file 统一到 FSMN-first VAD 策略；
2. 保留低延迟 Zipformer realtime first-pass，同时使用 FunASR 做异步高质量 second-pass refinement；
3. 基于 FSMN boundary + ASR timing/CTC + punctuation + speaker evidence 建立统一 Sentence Composer；
4. 对现有 diarization full chain 做证据驱动的性能优化；
5. 完成本地 realtime/file 全链回归和资源/生命周期验收。

Stage 16C 不承担：

- 云端文件 ASR；
- 云端 realtime ASR；
- SenseVoice emotion/event 的首次接入（该能力保留在 Stage 16B）；
- 无证据的大规模 ASR / VAD / diarization 模型更换。

---

# Stage 16C.1 — Unified FSMN VAD

状态：**RESERVED / NOT STARTED**

## 目标

取消旧规划中的 Silero / FSMN / TEN 三方 realtime VAD 重新选型。

Stage 16C 的目标策略固定为：

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

TEN-VAD 不进入本轮 production 路线；除非未来出现新证据和独立授权，否则不恢复三方 VAD Matrix。

## True-streaming 要求

FSMN realtime 实现必须符合现有：

- `StreamingVadEngine`
- `StreamingVadSession`

不得把文件型 `VadEngine.analyze()` 在短 chunk 上反复重开后冒充 streaming。

目标实现应复用 FSMN native session 的连续状态：

`open session`
→ `acceptSamples(...)`
→ `acceptSamples(...)`
→ `...`
→ `finishInput()`

并暴露 realtime 所需的：

- `speechDetected`
- completed speech ranges
- reset / finish semantics

FSMN native cache、endpoint state 和 canonical sample offsets 必须跨 chunk 连续。

## Shared policy

file / realtime 应尽量共享：

- FSMN model/revision；
- speech/noise threshold；
- endpoint detection semantics；
- max speech duration policy；
- canonical 16 kHz mono assumptions；
- model/config lineage。

以下仍属于 realtime consumer policy，不应错误塞入 VAD model config：

- PARTIAL/STABLE/FINAL；
- realtime FINAL timeout；
- UI refresh cadence；
- ASR session reset policy。

## Offline / Streaming parity

必须增加相同 WAV 的 parity test：

```text
Canonical WAV
├→ Offline FsmnVadEngine
└→ FsmnStreamingVadEngine（模拟实时分块输入）
```

比较：

- speech segment count；
- start/end sample；
- total speech duration；
- short pause / long pause boundary；
- chunk-size independence。

最终 boundaries 应一致，或仅允许有明确合同定义的 frame-level tolerance。

Realtime 额外验证：

- speechDetected 起落；
- completed segment 不重复；
- reset 无残留；
- START/STOP；
- PAUSE/RESUME；
- reconnect / discontinuity；
- screen-off / background；
- microphone/BLE canonical source；
- 短句、长句、大量短停顿、连续讲话。

---

# Stage 16C.2 — Realtime Two-Pass ASR

状态：**RESERVED / NOT STARTED**

## 产品目标

建立：

> **低延迟 realtime first-pass + FunASR asynchronous high-quality second-pass**

FunASR 不替代现有 true-streaming recognizer，也不伪装成 true-streaming ASR。

规划架构：

```text
Canonical PCM
      ↓
FSMN streaming VAD
      ↓
Zipformer realtime ASR
      ↓
PARTIAL → STABLE → FIRST_FINAL
      │
      ├→ UI 立即显示
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

## First-pass 不得被 second-pass 阻塞

优先级原则：

- P0：音频采集 / canonical source；
- P0：FSMN streaming VAD；
- P0：Zipformer realtime ASR；
- P0：PARTIAL/STABLE/FIRST_FINAL UI；
- P1：FunASR refinement。

如果设备资源不足：

> 宁可 FunASR refinement 延后，也不能让 realtime first-pass 卡顿、丢音频、提高 FINAL latency 或破坏录音生命周期。

需要设计独立 `RealtimeSecondPassScheduler` 或等效调度层，至少满足：

- bounded queue；
- 单 worker 或明确受控并发；
- 不并发启动多个重型 FunASR 任务；
- realtime backlog / thermal / memory pressure 下可暂停 refinement；
- STOP 后可继续 drain 已排队任务；
- cancellation / process/lifecycle 行为可追溯。

## 复用 FSMN speech truth，不重新 VAD

Realtime 已经由 FSMN 得到 completed canonical speech ranges，因此 FunASR second-pass 不得重新跑一遍 VAD。

直接消费：

`[startSample, endSample] + canonical PCM`

从而复用：

- VAD model；
- speech boundary；
- canonical sample truth；
- timeline lineage。

## Refinement unit

不得对每个极短 VAD fragment 都单独启动昂贵 FunASR inference。

应设计 bounded refinement unit：

- 可以由一个或多个连续 completed FSMN ranges 构成；
- 保留 absolute sample mapping；
- 不跨 discontinuity / pause 等 hard boundary；
- 复用 Stage 16B offline consumer 的安全边界原则；
- 当前基线继续保护约 30 s outer safety bound，FunASR engine 内部可继续 adaptive chunking。

## CTC timing

FunASR revision-2/P5 已提供 same-stream CTC timing 能力。

second-pass 成功后应允许同时更新：

- refined text；
- refined token timing；
- sentence-composition input。

不得为了 realtime second-pass 再建立一套与 Stage 16B 冲突的时间真值。

## Durable lineage

当前 Stage 16A durable FINAL 只表示 realtime first-pass FINAL。Stage 16C 不应简单把 FunASR 文本无历史地覆盖原记录。

需要在正式开发规划中设计：

```text
Realtime utterance
├─ first-pass lineage
│  ├─ realtime model/config
│  ├─ text
│  └─ timing
└─ refinement lineage
   ├─ FunASR model/revision/config
   ├─ refined text
   ├─ CTC timing
   └─ status/error
```

如果这要求 Room schema 变化：

- 必须先重新核验当时正式 Room version；
- 单独确认 migration；
- 不允许 destructive migration；
- 不由本路线文档自动授权 schema bump。

## 人工编辑优先级

后台 refinement 不能覆盖人工 revision。

规则必须保证：

- 未人工编辑：可按产品规则 adopt refined version；
- 已人工编辑：FunASR result 只能保留为可追溯候选/metadata，不能静默覆盖人工文本；
- second-pass failure / OOM / model missing / cancellation：保留可用的 first-pass FINAL。

## STOP 后行为

如果录音过程中一部分 utterance 已完成 refinement，STOP 后不得从头重做全部录音。

目标：

```text
已 refined → 复用
pending → drain
failed/cancelled → 明确状态
```

这使“录音结束后高质量转写”等待时间只取决于剩余 backlog，而不是重新从 0% 开始。

---

# Stage 16C.3 — Sentence Composition V2

状态：**RESERVED / NOT STARTED**

## 定位

旧规划中的“Segmentation & Punctuation V2”升级为：

**Sentence Composition V2 — 句子重建 / 阅读结构 V2**

目标不再是简单调一个断句阈值，而是把多个可信信号组合成产品句界：

```text
FSMN boundary
      +
ASR token / CTC timing
      +
native / external punctuation
      +
speaker turns
      +
text continuity / fragment rules
      ↓
Sentence Composer
      ↓
Timeline sentence / Reading sentence
```

## Hard / Soft boundary

Hard boundary 示例：

- canonical discontinuity；
- 明确 pause/control boundary；
- 文件开始/结束；
- 不可跨越的 media gap；
- 用户人工边界。

Soft boundary 示例：

- `。！？!?`；
- speaker turn；
- 中等 silence gap；
- comma-like punctuation；
- CTC/token spacing；
- 当前句子过长；
- very-short-fragment merge evidence。

不能继续采用：

- 一个 VAD segment = 一句话；
- speaker 一变化 = 必然切句；
- 固定 N 秒 = 一句话。

## Speaker 与句界

P5 R5 已证明 native/local diarization 可能出现短暂弱 speaker evidence，因此：

- speaker change + punctuation/silence 支持 → 强句界；
- speaker change only → 倾向切句但允许延迟确认；
- 极短 low-evidence speaker fragment → 不应直接砍碎完整句子。

## File / Realtime 共用核心

共享：

- punctuation normalization；
- sentence scoring / merge rules；
- minimum fragment rules；
- speaker-turn handling；
- canonical timing contract。

允许不同：

- realtime endpoint latency；
- PARTIAL/STABLE behavior；
- trailing silence；
- FINAL timeout。

FunASR 可使用真实 CTC timing；其它模型使用其当前可信 timing source。统一的是 Sentence Composer contract，而不是强迫所有 ASR 模型使用同一种底层 timestamp 算法。

验收至少覆盖：

- 普通话单人；
- 口音；
- 快语速；
- 长句；
- 大量短停顿；
- 双人/多人；
- 噪声；
- 中英混合；
- 文件长录音；
- realtime 连续讲话；
- first-pass → refined text 原位更新后的句界稳定性。

---

# Stage 16C.4 — Diarization Performance V2

状态：**RESERVED / NOT STARTED**

## 原则

P5 R5 已完成当前 speaker correctness 收敛。Stage 16C.4 不重新进行 clustering-threshold sweep，也不以单台测试机的绝对耗时决定跨设备 product policy。

首先保护：

- AUTO speaker correctness；
- fixed speaker-count independence；
- weak short false-speaker guard；
- overlap/manual assignment；
- current canonical alignment semantics。

## 第一层：完整 profiling

至少拆分测量：

- VAD reuse/input preparation；
- segmentation；
- speaker embedding；
- clustering；
- stitching；
- transcript alignment；
- total wall time / RTF；
- invocation/window/embedding counts；
- duplicated audio workload；
- RAM/PSS；
- CPU / thermal。

优化必须根据 profile 结果排序，而不是预设“embedding 一定最慢”。

## 第二层：减少重复工作

优先评估：

- 已有 FSMN VAD result reuse；
- overlap segmentation / embedding 是否重复；
- cross-window reusable evidence；
- bounded batch/vectorized embedding（只有 runtime/API 和 RAM 证据支持时）；
- 减少无 speech 区域重型处理；
- window/overlap workload；
- clustering/stitching 调度。

Stage 13C 已证明 exact-range speaker embedding cache 当时无有效命中；不得无证据恢复同一方案。

## 第三层：Fast Diarization 候选

只有完整链优化后仍不满足产品目标，才允许实验：

`Selected/Unified VAD → CAM++ → clustering → stitching/alignment`

即绕过重型 segmentation 的 Fast Diarization challenger。

必须与完整链在相同录音上比较：

- speaker count correctness；
- same-speaker fragmentation；
- merge/confusion；
- short-turn behavior；
- overlap speech；
- total time/RTF；
- memory/thermal；
- 长录音稳定性。

不得为了速度牺牲不可接受的 speaker consistency。

## 核心模型调整

只有前述工程优化和 fast-path 证据均不足时，才重新评估 segmentation / speaker embedding model/runtime。

任何核心 diarization model replacement 都需要新的需求确认、模型基线和真机 QA。

---

# Stage 16C.5 — Full Pipeline Regression

状态：**RESERVED / NOT STARTED**

Stage 16C 最终必须验证的不是单个组件，而是统一 pipeline：

```text
Canonical Audio
      ↓
Unified FSMN VAD
      ├→ realtime Zipformer first-pass
      │        ↓
      │   FunASR second-pass refinement
      │
      └→ file ASR
               ↓
       Sentence Composer V2
               ↓
       Speaker enrichment
               ↓
          Transcript
```

至少覆盖：

- realtime first-output latency；
- PARTIAL/STABLE/FIRST_FINAL；
- FunASR refinement latency/backlog；
- first-pass → refined 原位更新；
- second-pass failure fallback；
- model missing；
- manual-edit protection；
- STOP 后仅 drain pending，不重复已完成 refinement；
- FSMN file/realtime parity；
- 30 s safety bound / long utterance；
- reconnect / discontinuity；
- PAUSE/RESUME；
- microphone / BLE canonical source；
- screen-off / background；
- process/lifecycle interruption；
- long recording；
- memory / PSS / thermal / battery；
- sentence boundary；
- speaker enrichment；
- Stage 16B SenseVoice Rich Metadata 不被 16C pipeline 改造破坏。

Stage 16C 不通过任何“单一测试机快了几秒”宣布 performance PASS；性能证据应区分设备差异、质量正确性和算法 workload。

---

# Stage 16C 开发顺序

正式执行顺序预留为：

`16C.1 Unified FSMN VAD`
→ `16C.2 Realtime Two-Pass ASR`
→ `16C.3 Sentence Composition V2`
→ `16C.4 Diarization Performance V2`
→ `16C.5 Full Pipeline Regression`
→ `Stage 16C Final Freeze/Handoff`
→ `Stage 17`

顺序理由：

1. 先固定 realtime/file 共用 VAD boundary source；
2. 再建立 first-pass / second-pass transcript lineage；
3. Sentence Composer 才能同时使用最终 boundary 和 refined timing；
4. diarization 性能优化不阻塞文字 first availability；
5. 最后验证全部生命周期和资源竞争。

---

# 与 Stage 16B SenseVoice Rich Metadata 的边界

Stage 16B SenseVoice Rich Audio Metadata 明确保留，并在 Stage 16B Final Freeze/Handoff 前完成。

Stage 16C 不把 FunASR refinement 的文本/CTC timing 冒充 SenseVoice emotion/event，也不通过 LLM 猜测情绪填充 SenseVoice metadata。

如果 16C Sentence Composer 或 transcript lineage 需要消费 Stage 16B rich metadata，必须保留其：

- source model；
- source sample range；
- raw / normalized value；
- independent lineage。

---

# RESERVED 门禁

截至本次修订：

- Stage 16C = **RESERVED / REPLANNED V2 / NOT STARTED**；
- 不创建 Stage 16C branch；
- 不创建 Stage 16C PR；
- 不编码；
- 不修改 production realtime VAD；
- 不修改 production ASR model binding；
- 不修改 Room schema；
- 不修改 production diarization model matrix；
- 不修改 production punctuation binding；
- 不提前改变 Stage 17 / Stage 18 范围。

Stage 16C 正式开始前必须：

`完成 Stage 16B SenseVoice Rich Metadata`
→ `完成 Stage 16B final regression / Freeze / Handoff`
→ `重新核验 GitHub main / PR / HEAD / CI / Room / model channel / 当前源码`
→ `输出 Stage 16C 修订需求`
→ `用户确认`
→ `输出 Stage 16C 开发规划`
→ `用户再次确认`
→ `才允许创建开发分支与编码`

本文件是路线/架构预留，不构成实现授权。