# Stage 16C — Local Speech Pipeline Optimization

状态：**RESERVED / NOT STARTED**

日期：2026-10-20

本文件是 `docs/ROADMAP_STAGE_13C_PLUS.md` 的后续路线图增量补充。

Stage 16C 插入在：

`Stage 16B — Local File ASR V2`

与：

`Stage 17 — 云端文件 ASR / 音频上传转写`

之间。

新的顺序为：

`Stage 16A → Stage 16B → Stage 16C → Stage 17 → Stage 18`

本次仅做路线预留：**不得开始编码，不得创建 Stage 16C 开发分支，不得修改 production model channel，不得改变现有 Stage 16A / Stage 16B 已冻结或正在执行的产品契约。**

Stage 16C 只有在 Stage 16B 完成全部开发、真机验收、Freeze/Handoff，并重新核验 GitHub 当前真实状态后，才允许进入需求确认流程。

---

## 16C.0 定位

Stage 16C 名称：

**Local Speech Pipeline Optimization — 本地语音链路优化**

目标是对 Stage 16A / Stage 16B 完成后的本地语音链做第二轮产品级优化，重点解决：

1. 实时转写 VAD 重新选型；
2. 说话人分离处理时间过长；
3. 断句与标点质量不足。

Stage 16C 不承担云端 ASR。Stage 17 / Stage 18 的云端文件 ASR、云端实时 ASR边界保持不变。

---

# Stage 16C.1 — Realtime VAD Matrix

状态：**RESERVED / NOT STARTED**

目标：重新确定 Voica 本地实时转写的 production Streaming VAD。

候选至少保留三组：

1. **Silero VAD** — 当前实时 production baseline；
2. **FSMN-VAD** — Stage 16B 文件 VAD 强候选；
3. **TEN-VAD** — realtime challenger。

即使某个 VAD 在 Stage 16B 文件 Gate 中胜出，也不得直接推导它必然适合实时转写。Realtime VAD 必须重新执行独立 benchmark。

重点指标至少包括：

- sentence-leading speech loss / 首字吞失；
- sentence-ending truncation / 句尾截断；
- 短停顿误切；
- 长停顿 endpoint；
- speech-start latency；
- endpoint / FINAL latency；
- PARTIAL → STABLE → FINAL 稳定性；
- 连续讲话 fragmentation；
- blank / duplicate / premature-final 行为；
- CPU / RAM / PSS；
- thermal；
- realtime RTF / processing latency；
- 10–30 分钟连续运行稳定性。

比较时必须固定相同 Streaming ASR、相同 canonical PCM、相同测试录音与相同下游文本处理，只有 VAD 变化。

FSMN / TEN 若进入 realtime benchmark，必须实现符合 Voica `StreamingVadEngine / StreamingVadSession` contract 的独立 streaming adapter，不得将文件型 `VadEngine` 分块包装后伪装成 true streaming。

最终 VAD 选择需用户明确确认后才能修改 production realtime binding。

---

# Stage 16C.2 — Diarization Performance V2

状态：**RESERVED / NOT STARTED**

问题定义：

> 说话人分离处理时间仍明显过长，在部分真实录音中甚至超过 ASR 本身。

Stage 16C.2 继承 Stage 13C 已建立的性能优化原则，但不重开 Stage 13C Freeze。

优化顺序固定为：

### 第一层：现有完整链 Profiling / 去重复计算

至少重新测量：

- VAD time；
- segmentation time；
- speaker embedding time；
- clustering time；
- stitching time；
- alignment time；
- total diarization time；
- RTF；
- peak RAM / PSS；
- CPU / thermal；
- segment / window / embedding count。

优先评估：

- VAD result reuse；
- overlap embedding reuse；
- cross-window embedding cache；
- bounded batch / vectorized embedding；
- 减少重复 segmentation / embedding；
- window / overlap 参数对 workload 的影响；
- 极短 speech fragment 策略；
- clustering / stitching 调度。

### 第二层：Fast Diarization 候选

若完整链优化后仍明显慢于产品目标，再评估：

`Selected VAD → CAM++ → clustering → stitching/alignment`

即实验性跳过重型 segmentation 路径。

必须与完整链在同批真机录音上 A/B 比较：

- total time / RTF；
- speaker count correctness；
- same-speaker fragmentation；
- merge/confusion；
- short-turn behavior；
- overlap speech；
- RAM / PSS；
- CPU / thermal；
- 长录音稳定性。

不得只为了速度牺牲不可接受的 speaker consistency。

### 第三层：核心模型调整

只有在前两层证据不足时，才重新评估 speaker embedding / segmentation 模型或 runtime。

任何核心 diarization 模型替换都必须重新建立回归基线并取得用户明确确认。

---

# Stage 16C.3 — Segmentation & Punctuation V2

状态：**RESERVED / NOT STARTED**

目标：统一优化本地文件 ASR 与本地实时 ASR 的断句、endpoint 与标点体验，同时保持 realtime latency 与文本稳定性。

逻辑分层固定为：

`Audio boundary layer`
→ `VAD / endpoint`

`ASR utterance layer`
→ `PARTIAL / STABLE / FINAL`

`Text segmentation layer`
→ `句界 / 段落边界`

`Punctuation layer`
→ `ASR native punctuation + external punctuation policy`

重点解决：

- 句子中间错误断开；
- 长句一直不断；
- 首句 / 末句缺失；
- 短停顿造成频繁 FINAL；
- 逗号 / 句号位置不自然；
- 标点缺失；
- ASR 自带标点与外部标点重复或冲突；
- realtime PARTIAL 文本频繁重排；
- FINAL 后正式标点与阅读段落不一致；
- 文件转写与实时转写断句规则差异过大。

原则：

- Stage 16C.1 必须先完成，确认 realtime VAD 后才允许冻结 16C.3 的 realtime boundary 参数；
- realtime 可以使用低延迟 interim punctuation，但 FINAL 必须有稳定的正式标点策略；
- 文件与实时可以共享文本分句核心规则，但允许 endpoint / latency 参数不同；
- 不允许将所有断句行为绑定到单一 ASR 模型；
- 每次正式转写应保存必要的模型 / config snapshot，保证结果可追溯。

验收至少覆盖：

- 普通话单人；
- 口音；
- 快语速；
- 长句；
- 大量短停顿；
- 双人 / 多人会议；
- 噪声；
- 中英混合；
- realtime 连续讲话；
- 文件长录音。

---

# Stage 16C 开发顺序

只有在 Stage 16B Final Freeze/Handoff 后，才允许重新核验并输出 Stage 16C 修订需求。

预留执行顺序：

`16C.1 Realtime VAD Matrix`
→ `16C.2 Diarization Performance V2`
→ `16C.3 Segmentation & Punctuation V2`
→ `Stage 16C Final Freeze/Handoff`
→ `Stage 17`

16C.1 优先于 16C.3，因为 VAD / endpoint 会直接影响断句与 FINAL latency；不得先冻结断句参数再更换 realtime VAD。

---

# RESERVED 门禁

截至本文件写入时：

- Stage 16C = **RESERVED / NOT STARTED**；
- 不创建 Stage 16C branch；
- 不创建 Stage 16C PR；
- 不编码；
- 不修改 production realtime VAD；
- 不修改 production diarization model matrix；
- 不修改 production punctuation binding；
- 不提前改变 Stage 17 / Stage 18 范围。

Stage 16C 正式开始前必须执行既有项目门禁：

`读取 Stage 16B Final Freeze/Handoff → 重新核验 GitHub main / open PR / HEAD / CI / Room / model channel / 当前源码 → 输出 Stage 16C 修订需求 → 用户确认 → 输出 Stage 16C 修订开发规划 → 用户再次确认 → 才允许创建开发分支与编码`

本文件仅完成路线图预留，不构成任何实现授权。
