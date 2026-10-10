# Post-V1 P0 — Canonical Audio / 标准 WAV 性能与生成体验专项

状态：**PLANNED / USER CONFIRMED / POST-V1 NON-BLOCKING**

本文件记录 Stage 14 V1 RC 真机长音频验收期间发现的标准 WAV（canonical audio）性能与界面问题。该专项**不修改已经通过 Stage 14.7B 30 / 60 / 120 分钟稳定性验收的 V1 RC 二进制**，不作为 Stage 14 Final Freeze 阻断项。

开始本专项开发前，必须重新核验 `main`、上一阶段 Freeze/Handoff、当前 Room schema、production model channel、当前 canonical audio 实现、播放器/转写依赖与当时 CI。GitHub 当前仓库仍为唯一事实来源。

Voica 为独立项目；不得复制、迁移、继承、cherry-pick、机械翻译或改写 `ioannes78/voice-card-android` 的实现。

---

## 1. 真实问题基线

Stage 14.7B 使用同一 production-signed V1 RC 完成真实 30 / 60 / 120 分钟长音频验收，SenseVoice 与 speaker diarization 均稳定完成。

同时确认一个独立的 canonical preprocessing 性能观察：

- 同样输入格式下，约 1 小时音频生成标准 WAV 的耗时约为 0.5 小时音频的 **3 倍左右**；
- 音频长度仅约 2 倍，说明 canonical generation 存在疑似非线性耗时增长；
- 精确 wall-clock 未在 Stage 14.7B 记录，因此不得把该“约 3 倍”当作正式 benchmark 数值；
- 该问题没有伴随 crash / ANR / hang / data loss，因此 Stage 14 判定为**非阻断性能问题**。

当前产品链以 canonical WAV 作为稳定播放/转写处理资产；标准目标规格维持当前冻结事实，开发本专项前需重新核验实际实现。

---

## 2. 专项目标

目标分两部分：

1. **显著降低长音频标准 WAV 生成时间，优先消除随时长增长的非线性退化；**
2. **把“生成标准 WAV”从一个阻塞感很强的空白等待状态，改成可理解、可观察、可取消、可恢复的播放器内任务体验。**

本专项不得以牺牲以下属性换速度：

- canonical 音频正确性；
- source / canonical lineage；
- 文件完整性校验；
- 时间轴一致性；
- 播放与 ASR 输入一致性；
- crash/recovery 行为；
- Room 资产事实关系。

---

# 3. 性能优化规划

## 3.1 先 Profiling，后改算法

开发开始后，先对 30 / 60 / 120 分钟同格式真实样本分阶段计时，至少记录：

- source import/copy time；
- source SHA / validation time；
- media decode time；
- downmix time；
- resample time；
- canonical WAV write time；
- final verification/hash time；
- total canonical generation time；
- canonical generation RTF；
- peak RAM / PSS；
- CPU；
- thermal（可取得时）；
- source size / canonical size；
- GC / allocation 异常（可观测时）。

必须先确定真正瓶颈，不能只凭“重采样慢”或“磁盘慢”猜测。

## 3.2 Canonical WAV Fast Path — P0

优先检查输入是否已经满足 canonical 目标格式。

当输入已满足当前 canonical contract（开发时重新核验，预计包括 PCM16 / 16 kHz / mono / valid RIFF-WAVE）时：

- 不再进入逐 sample 的 downmix / resample normalizer；
- 采用受校验的 fast path；
- 可以是受控复制、结构验证后重封装，或其它不会改变 canonical contract 的低成本路径；
- 必须保留 source lineage、size/hash/integrity 与 cancellation/recovery 语义；
- 不得仅按扩展名判断格式。

Fast Path 必须有独立单元测试与真机 benchmark。

## 3.3 减少重复全文件 I/O / SHA — P0

重新审计以下阶段是否对同一个 app-private immutable source 做重复全文件读取：

- 导入时 copy + SHA；
- canonical 前 source SHA 再验证；
- decode；
- canonical write；
- canonical final hash / verification。

优化原则：

- 完整性不能简单删除；
- 若 source 已被原子提交到 app-private managed storage，且 Room 保存 size/hash/lineage，可评估减少不必要的重复扫描；
- 可考虑把必须的 hash 与 copy/write 流式合并，但必须保持可验证的异常/恢复语义；
- source 被外部替换、路径不可信或 lineage 不完整时不得走弱校验路径。

## 3.4 Buffer / Allocation / GC 优化 — P1

审计长音频逐块处理中的：

- `ByteArray` / `ShortArray` 重复分配；
- chunk size 是否过小；
- PCM conversion 中间数组；
- normalizer 输出数组；
- JNI / MediaCodec buffer copy；
- writer flush / sync 频率。

目标：

- buffer 复用；
- bounded memory；
- 减少 GC；
- 提高顺序读写吞吐；
- 不使用无上限大 buffer 换速度。

## 3.5 压缩音频解码流水化 — P1

对 MP3 / M4A / AAC / FLAC / Ogg Opus 等输入，保持流式：

`decode → normalize/resample → canonical writer`

避免：

- 先完整解码到超大 PCM 临时文件/内存；
- 不必要的二次中间 PCM；
- 无界队列。

评估 decoder 与 writer 能否形成更高吞吐的 bounded producer/consumer pipeline；任何并发优化必须以真机 CPU / thermal / RAM 数据为依据。

## 3.6 ASR 直通解码 — P2 / 架构候选

作为更后续候选评估：

`compressed source → streaming decode PCM → ASR`

使用户在 canonical WAV 尚未完整落盘时即可开始文件 ASR，或让 ASR 不必把“完整 canonical 文件落盘完成”作为绝对前置条件。

该项属于架构级优化，只有在以下契约明确后才能开发：

- 播放仍需要稳定 canonical asset 时的关系；
- ASR source lineage；
- absolute sample timeline；
- retry/recovery；
- speaker diarization / VAD reuse；
- canonical 最终生成失败时 ASR 结果是否有效。

P2 不得抢在 P0/P1 Profiling 与低风险优化之前实现。

---

# 4. Recording Detail / 播放器界面优化

## 4.1 当前问题

当前生成过程中：

- 播放器卡片只有“需要先生成标准 WAV，才能播放和转写”的等待提示；
- 页面下方另有一个占整行的描边按钮“取消生成标准 WAV”；
- 用户看不到实际生成进度；
- 对 1–2 小时长音频，等待感强，容易误判为卡住。

## 4.2 新交互：进度集成进播放器卡片

**删除播放器卡片外独立的大号“取消生成标准 WAV”描边框。**

生成中播放器卡片改为统一任务状态：

- 标题仍为“播放器”；
- 中心播放按钮保持 disabled / processing visual；
- 主状态显示：`正在生成标准 WAV`；
- 显示阶段：例如 `解码` / `标准化` / `写入` / `校验`；
- 卡片内显示 Linear Progress Indicator；
- 有真实可计算进度时显示百分比，例如 `43%`；
- 无法可靠计算时使用 indeterminate progress，**不得伪造百分比**；
- `取消` 改为卡片内的小型 TextButton / trailing action，不再单独占一整行；
- 可选显示 `已处理 34:12 / 121:07`，前提是该值来自真实 processed sample/time，而不是估算动画。

推荐布局：

`播放器`

`[processing icon]  正在生成标准 WAV     43%`

`[===========------]`

`标准化 · 已处理 52:04 / 121:07               取消`

避免同时出现大按钮、重复状态文本和额外卡片。

## 4.3 自动完成切换

生成成功后：

- 不要求用户刷新页面；
- 进度状态自动过渡到正常波形播放器；
- 播放、转写入口立即可用；
- 不显示多余“生成完成”确认框。

## 4.4 取消状态

用户点击卡片内“取消”后：

- 使用现有安全 cancellation/recovery contract；
- partial `.part` / transient file 按现有清理规则处理；
- 页面变为可重试状态，例如 `尚未生成标准 WAV` + `生成`；
- 不把取消显示成错误；
- 不丢失 imported original / device source。

如果任务已经进入不可安全即时取消的原子提交区间，UI 应短暂显示“正在结束任务”而不是假装瞬间完成取消。

## 4.5 失败与重试

失败时在播放器卡片内显示：

- 简体中文可读原因；
- `重试`；
- 必要时显示“存储空间不足”等可行动原因；
- 技术错误码放诊断页/详情，不在普通 UI 暴露英文内部错误。

## 4.6 转写页联动

如果当前产品仍要求 canonical WAV READY 才允许文件转写：

- 转写页显示与播放器相同的 canonical generation 单一任务状态；
- 不启动第二份 canonical generation；
- 用户从“录音 / 转写”两个 Tab 查看时必须看到同一进度来源。

若后续 P2 实现 ASR 直通解码，则再重新定义该依赖，不在本 UI 优化中提前伪装为已支持。

---

# 5. 进度数据模型要求

为了让 UI 显示真实进度，canonical pipeline 需要把当前仅有的 stage state 扩展为可观察 progress snapshot，建议至少包含：

- `stage`；
- `processedUnits` / `totalUnits`（可可靠获取时）；
- `processedDurationUs` / `totalDurationUs`（音频处理优先）；
- `fraction`（仅当 total 可知）；
- `startedAt`；
- `updatedAt`；
- cancellation state；
- failure state/code；
- recordingId / source lineage identity。

约束：

- UI 不自己猜进度；
- progress snapshot 必须来自 canonical worker/coordinator；
- 恢复页面后能重新观察同一任务；
- 进度更新频率应 bounded，避免高频 Room/Compose 写入反而降低转换速度；
- 不要求把每个进度 tick 永久写入 Room；持久状态与高频瞬态进度应区分。

---

# 6. 验收标准

## 6.1 性能

至少用同输入格式真实 30 / 60 / 120 分钟样本 A/B：

- baseline total canonical time；
- optimized total canonical time；
- RTF；
- 30→60 与 60→120 scaling；
- RAM/PSS；
- CPU；
- thermal；
- storage I/O；
- canonical output hash/format/integrity；
- crash / ANR / OOM。

核心目标不是规定一个虚构的固定秒数，而是：

1. 证明优化后比 V1 baseline 明显更快；
2. 60 / 120 分钟单位音频成本不应出现无法解释的持续恶化；
3. 不能通过关闭完整性校验、降低音频正确性或破坏 lineage 换取表面速度。

## 6.2 UI / UX

真机必须验证：

- standalone “取消生成标准 WAV”大描边框已删除；
- 播放器卡片内进度清楚、无重复状态；
- 有真实 total 时百分比正确且单调；
- total 不可知时使用 indeterminate；
- 取消有效；
- 取消后可重试；
- 切 Tab / 息屏 / 后台 / 返回页面后任务状态一致；
- 完成后自动切播放器；
- 失败提示中文、可行动；
- 30 / 60 / 120 分钟长文件无 UI 卡死。

---

# 7. 开发顺序

本专项正式开发时按以下顺序，不一次性重写：

`P0 Profiling → P0 Canonical Fast Path → P0 重复 I/O/SHA 审计 → P1 buffer/GC/I/O 优化 → UI progress contract → 播放器内进度/取消 UX → 30/60/120 A/B → 是否进入 P2 ASR 直通解码`

每一大步都必须 Unit Test / Build / CI，再进行真机 A/B。

---

# 8. 与 Stage 14 / Stage 15 的边界

- Stage 14 当前 production-signed V1 RC 不因本规划发生任何代码变化；
- Stage 14.7B 已通过的 30 / 60 / 120 分钟证据保持有效；
- 本规划文件本身不改变 Room schema、production model channel 或 APK/AAB；
- 该专项属于 **V1 发布后的 P0 follow-up**；
- 是否在进入 Stage 15 BLE 实时音频链路前优先实施，由 Stage 14 Final Handoff 后用户确认；
- 若先实施本专项，应建立独立 development branch / PR，不在已冻结的 Stage 14 PR 上继续写生产代码。

