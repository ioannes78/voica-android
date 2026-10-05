# Voica Stage 13C+ 后续开发路线图

状态：**PLANNING / APPROVED FOR DOCUMENTATION**

生效范围：**从 Stage 13C 开始**。

本文件不修改、不重定义 Stage 13B 的既有开发范围。Stage 13B 仍按当前开发分支与 PR 的真实状态继续推进；Stage 13B 完成、真机验收、Freeze/Handoff 后，下一阶段改为 Stage 13C，而不是直接进入 Stage 14。

GitHub 当前仓库始终是唯一事实来源。开始任一后续 Stage 前，都必须重新核验 `main`、open PR、HEAD、CI、Room schema、当前源码、Freeze/Handoff、production model channel 与适用的模型/runtime 文档；不得依据本文件中的历史 SHA 猜测实现状态。

Voica 是独立项目。不得复制、迁移、继承、cherry-pick、机械翻译或改写 `ioannes78/voice-card-android` 的任何实现。

---

## 0. 本次规划核验基线

本规划写入前已重新核验：

- repository：`ioannes78/voica-android`
- default branch：`main`
- main HEAD：`29fbc973698a84408cf99e4213d73996b8147f1d`
- main 已完成：Stage 13A QA6 merge / Final Freeze / Final Handoff
- 当前 Stage 13B 开发 PR：#17
- Stage 13B 分支：`stage13b-stability-background-longrecording`
- 本次核验时 PR #17 head：`dcfc0d4b16aff906eeb956d743fb5c0e74e2c636`
- PR CI：Android PR CI #721 / run `37345664717` / success
- PR #17 当前不修改 `docs/ROADMAP.md`、`AGENTS.md` 或既有 Handoff 文档
- Room：Stage 13A 冻结为 v7
- production model channel：本规划不修改、不提升

以上 SHA/CI 只记录本次规划核验时的事实；Stage 13B 仍在开发中，后续必须重新读取 GitHub 当前状态。

---

# Stage 13C — 说话人分离性能专项

## 13C.0 定位

Stage 13C 的问题定义固定为：

> **说话人分离处理时间过长，有时超过文本 ASR 的处理时间。**

本阶段主要目标是降低 diarization 的总耗时、RTF、CPU、RAM/PSS、thermal 与 battery cost，并改善长录音等待体验。

本阶段**不是**以重新提升说话人准确率为主要目标，也不默认重新选型 speaker embedding 模型。

Stage 13A 已冻结的当前产品说话人链作为 Stage 13C baseline：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering → cross-chunk stitching → transcript alignment`

Stage 13C 开始前必须重新读取：

- `docs/STAGE_13A_FREEZE.md`
- `docs/STAGE_13A_HANDOFF.md`
- `docs/STAGE_13A_QA5_FINAL_DECISIONS.md`
- Stage 13B Final Freeze/Handoff（届时创建）
- 当前 Room schema
- 当前 production model manifest

Stage 9 的 ERes2Net 历史冻结事实仅代表 Stage 9 当时状态；Stage 13A 已将当前产品 embedding 模型收敛为 CAM++。

## 13C.1 分阶段性能 Profiling

每次 diarization benchmark 至少记录：

- audio duration
- VAD time
- Pyannote segmentation time
- CAM++ embedding time
- clustering time
- cross-chunk stitching time
- transcript alignment time
- total diarization time
- RTF
- peak RAM / PSS
- average / peak CPU
- thermal state（可取得时）
- battery impact（可测时）
- segment count
- embedding count
- chunk count
- cache hit / reuse ratio

必须先通过 Profiling 确定实际瓶颈，再决定具体优化；不得凭主观猜测直接更换核心模型。

## 13C.2 复用 ASR/VAD 已有结果

若同一 canonical lineage 已完成可复用的 Silero VAD speech regions：

- diarization 不得无条件重新跑相同 VAD；
- 必须建立可验证的 source/model/config lineage；
- 只有模型 revision、VAD 参数、canonical lineage 或必要输入变化时才允许失效重算。

## 13C.3 Speaker embedding 缓存

为同一 Recording / canonical lineage / diarization model revision / config 建立可追溯的 embedding cache 或等效复用机制。

以下操作不得导致无必要的完整 speaker embedding 重算：

- 修改转写文字或创建人工 Revision
- 切换 ASR Transcription version
- 重新生成 AI Summary
- 重新打开详情页
- Speaker rename
- 只执行 transcript/speaker alignment 重建

缓存必须具备 revision/config invalidation，不能把旧 embedding 伪装成新模型结果。

## 13C.4 CAM++ 批处理与 runtime 调度

重新核验当前 runtime 是否支持安全 batch / vectorized inference；若支持，则 benchmark：

- 单 segment inference
- bounded batch inference
- 不同 batch size
- 1 / 2 / 4 / AUTO threads

目标是减少 JNI/runtime 调用开销和无效线程竞争。

线程数不能无限开放；高级设置必须使用安全范围并通过真机 thermal / RAM / RTF 验证。

## 13C.5 Chunk overlap 去重

长录音 chunk overlap 只用于保持跨 chunk 连续性，不应让相同音频范围重复完成全部 speaker embedding 工作。

需要设计：

- overlap range identity
- embedding reuse
- cross-chunk anchor reuse
- bounded cache

不能为了减少重复计算破坏 absolute canonical sample timeline。

## 13C.6 极短 speech segment 策略

对过短、低信息量的 speech fragment：

- benchmark 最短可可靠 embedding 的 duration；
- 低于阈值的 fragment 可合并到相邻 speech region、延后归属或显式 unresolved；
- 不得为了提速直接伪造 speaker；
- threshold 必须保存到运行 config snapshot。

## 13C.7 单说话人快速路径

在现有“预计说话人数”能力上明确提供：

- 自动
- 1 人
- 2 人
- 3 人
- 4 人+

当用户明确选择 **1 人** 时，可直接将有效 speech 归为 `Speaker 1`，跳过 Pyannote / CAM++ / clustering 的重型多人分离链，只保留必要的 VAD、timeline 与 alignment。

此模式必须清楚标记为用户指定单人，不得冒充自动 diarization 的推断结果。

## 13C.8 ASR 完成与 Diarization 完成解耦

产品生命周期调整为：

`ASR 完成 → Transcription 立即可读/可编辑/可复制/可总结 → Diarization 后台继续 → Speaker 信息完成后增量补充`

不得因为 diarization 尚未完成而阻塞已经完成的正文展示。

UI/任务状态至少区分：

- 转写已完成
- 正在识别说话人
- 说话人识别已完成
- 说话人识别失败/取消，但正文仍可用

AI Summary 是否等待 speaker 信息，应由任务输入契约明确决定；普通正文总结不得因为 speaker 后处理较慢而无条件阻塞。

## 13C.9 Fast Diarization 实验候选

只有在完成 13C.1–13C.8 的现有链优化后，若 diarization 仍明显慢于产品目标，才允许评估：

`Silero VAD → CAM++ → clustering → stitching/alignment`

即实验性跳过 Pyannote segmentation。

必须与完整链做同一批真机 A/B benchmark：

- RTF
- total time
- RAM/PSS
- CPU/thermal
- speaker consistency
- fragmentation
- merge/confusion
- overlap 场景
- 30 / 60 / 120 分钟稳定性

未经 benchmark 与用户确认，不得替换正式完整链。

## 13C.10 Stage 13C 门禁

Stage 13C 必须在 Stage 13B Final Freeze/Handoff 后开始。

Stage 13C 默认只优化现有说话人分离链性能，不以更换模型提升准确率为目标。

如果 Stage 13C 更换核心 diarization 模型、推理 runtime、核心 segmentation/embedding 路径或显著改变资源模型：

> 必须重新执行受影响的 Stage 13B 真实 30 / 60 / 120 分钟稳定性验证后，才允许进入 Stage 14。

Stage 13C 真机验收通过并 Freeze/Handoff 后，才允许进入 Stage 14。

---

# Stage 14 — Voica V1.0 Release Freeze

Stage 14 在 Stage 13B + Stage 13C 全部完成后执行。

必须冻结：

- Release APK / AAB
- R8 / Proguard
- 权限 / 隐私
- Room schema / migrations
- production model manifest
- 当前正式文件 ASR / streaming ASR / diarization 模型矩阵
- diarization 性能基线与默认参数
- 默认性能档位与高级参数 schema
- 默认线程 / VAD / decoder / diarization 参数
- Benchmark 基线
- 30min / 1h / 2h 稳定性证据
- Test / Architecture / Freeze / Handoff

Stage 14 不承担新的大规模模型选型。

若 V1.0 Freeze 前更换核心本地语音模型或 runtime，应退回相应模型阶段并重新完成 Stage 13B/13C 所需验证。

---

# Stage 15 — BLE 实时音频链路

保持既有边界：

`BLE TYPE=1 Audio → Opus → PCM → realtime canonical timeline`

重点：

- 实时音频连续性
- packet/drop diagnostics
- reconnect
- timeline 一致性
- bounded buffering
- 生命周期

Stage 15 不提前实现完整 ASR 产品 UI。

---

# Stage 16A — 本地实时 ASR

Stage 16A 对应原 Stage 16 的真正 streaming ASR 实现。

必须遵循：

`docs/STAGE_16_STREAMING_CONTRACT_V2.md`

主链：

`BLE PCM → Streaming VAD → Streaming ASR → Interim → Stable → Final → 两层标点`

负责：

- streaming session 生命周期
- Interim / Stable / Final
- 实时短句标点 + FINAL 正式标点
- 实时转写与录音文件最终 Transcription lineage
- streaming 参数安全默认值与高级设置
- 断连/恢复/结束 finalization
- realtime speaker pipeline 或与后处理 diarization 的明确衔接

不支持 true streaming 的文件型高质量模型不得通过离线 chunk 模拟后宣称为实时模型。

---

# Stage 16B — 本地文件 ASR V2

## 16B.1 目标

在 Stage 13A 已冻结的文件 ASR 基线上，引入 Fun-ASR-Nano，并通过同一批真机录音重新确定长期文件 ASR 产品矩阵。

本阶段不是为了堆叠模型数量，而是为了确定实际值得长期保留的模型组合。

## 16B.2 Benchmark 候选

至少比较：

1. **SenseVoice INT8**
   - 当前快速 / 默认基线
   - 低资源候选

2. **Fun-ASR-Nano INT8**
   - 新增重点候选
   - 中文 / 方言 / 中英混合 / 高质量文件转写方向
   - 优先评估现有 sherpa-onnx Android 路线可否直接承接；开发时重新核验官方支持、模型 revision、量化格式、license 与 redistribution 条件

3. **Qwen3-ASR 0.6B INT8**
   - 当前高质量基线
   - 高质量 / 多语言候选

## 16B.3 测试集

至少覆盖：

- 单人普通话
- 福建口音 / 常见地方口音
- 可取得的方言样本
- 中英混合
- 双人 / 多人会议
- 快语速
- 远场
- 背景噪声
- 长静音
- 30 / 60 / 120 分钟真实录音

## 16B.4 指标

至少记录：

- CER / WER（有 reference 时）
- punctuation
- timestamp 可用性/稳定性
- hotword / contextual bias（模型支持时）
- language behavior
- RTF
- total time
- peak RAM/PSS
- CPU
- thermal
- battery
- crash / ANR / OOM
- 长录音稳定性

## 16B.5 最终矩阵原则

不预先规定必须保留三个模型。

可能结果包括但不限于：

- SenseVoice + Fun-ASR-Nano
- SenseVoice + Fun-ASR-Nano + Qwen3-ASR
- 其它由真实 benchmark 支持的收敛组合

最终保留数量由精度收益、资源占用、维护成本和用户理解成本共同决定。

任何新增正式模型进入 production model channel 都必须独立受控、重新核验 manifest，并取得明确授权；本规划文件本身不修改 production model channel。

---

# Stage 17 — 云端文件 ASR / 音频上传转写

## 17.1 目标

实现长录音、高质量或用户主动选择的在线文件转写。

主链：

`Recording / compatible audio upload → Cloud File ASR Provider → normalized Voica Transcription`

## 17.2 多 Provider

支持多个 Cloud ASR Provider，而不是写死单一厂商。

Provider 能力至少包括：

- Provider Profile
- credential / region / base URL
- capability discovery
- file ASR model list
- connection test
- selected model
- supported audio formats
- max file size / duration
- timestamps
- language
- hotwords（支持时）
- speaker/detection capabilities（支持时）

云端输出必须转换为 Voica 统一的 Transcription / Segment / Timestamp / lineage，不建立第二套转写数据源。

## 17.3 上传与隐私

开始前必须明确：

- 音频将上传给第三方 ASR Provider
- Provider / model
- 预计网络与 API 成本（可取得时）
- 文件大小/时长限制

支持：

- upload progress
- cancel
- retry
- interrupted state
- long-file strategy

不得因本地 ASR 失败自动静默上传云端。

---

# Stage 18 — 云端实时 ASR

主链：

`BLE Audio → Cloud Realtime ASR → Interim → Stable → Final → Voica transcript contract`

至少实现：

- realtime provider adapter
- session lifecycle
- authentication
- model selection
- connection test
- Interim / Stable / Final normalization
- reconnect
- network interruption recovery
- fallback policy
- latency / packet / cost diagnostics

用户必须明确知晓实时音频会发送到第三方 Provider。

Cloud realtime ASR 与 Text LLM 继续解耦。

---

# Provider Profile 统一能力架构

从 Stage 17 起，Provider Profile 建议扩展为共享身份/凭据容器 + 独立 capability adapter：

`Provider Profile`

- Credential
- Region
- Base URL / endpoint metadata
- Text LLM capability
- File ASR capability
- Realtime ASR capability
- Audio LLM capability

同一厂商可以同时支持多种 capability，但：

- 模型列表独立
- 请求协议独立
- 参数独立
- connection test 独立
- capability availability 独立

可以共享 credential/region/base metadata，但不得因为同一厂商同时提供 LLM 与 ASR，就把 `TextLlmProvider`、`CloudAsrEngine` 与 `AudioUnderstandingProvider` 合并成一个模糊接口。

API Key 继续遵循 Android Keystore + app-private encrypted credential store；密钥不得进入 Room 的内容 lineage。

---

# Stage 19A — 离线 AI 总结

## 19A.1 模型矩阵

本地 AI 总结正式保留两个 Gemma 4 模型：

### Gemma 4 E2B

产品定位：

- 轻量
- 省资源
- 低内存设备 fallback
- 快速总结

### Gemma 4 E4B

产品定位：

- 高质量
- 默认本地 AI 总结模型
- 复杂长文本理解
- 深度总结

普通 UI 优先显示“轻量 / 高质量”，具体型号、量化/runtime、内存信息放在模型详情/高级设置。

开发 Stage 19A 时必须重新核验当时官方 Gemma 4 / LiteRT-LM Android 支持、license、量化格式、设备兼容性和内存要求。

## 19A.2 默认总结链

默认本地总结：

`Current Effective Transcription → structured transcript → Gemma 4 → AI Summary`

优先保留 Stage 11/12 已冻结的：

- SMART / PRESET / CUSTOM 模板语义
- Summary version
- evidence / lineage
- current/candidate 生命周期
- cancellation
- revision
- search integration

本地总结与云端 Text LLM 总结使用同一产品生命周期，不建立第二套 AI Summary 数据源。

## 19A.3 长文本 Map / Reduce

长录音转写不得简单把全部内容一次性塞入最大 context。

建议：

`Transcript → bounded chunks → local intermediate summaries → merge/reduce → Gemma 4 E4B final summary`

必须保留：

- source transcriptionId
- source transcriptionRevisionId（若产品允许以人工修订作为显式输入）
- source sample ranges / evidence ranges
- chunk lineage
- model / revision / config snapshot

## 19A.4 Thinking 高级设置

若 Stage 19A 开发时所选 LiteRT-LM / Gemma 4 runtime 仍直接支持 `ThinkingConfig(enableThinking, thinkingTokenBudget)`，则将 Thinking 做成正式高级设置。

普通用户层：

- 自动
- 快速
- 标准
- 深度

高级层允许：

- 自动
- 关闭
- 256
- 512
- 1024
- 2048
- 4096
- 自定义安全范围

建议初始 benchmark 默认值：

- E2B：512
- E4B：1024

以上只作为 benchmark 起点，不是永久冻结值。

每次 Summary 必须保存实际 thinking config snapshot。

## 19A.5 Thinking 与输出预算

`thinkingTokenBudget` 与最终输出 token 预算必须联动。

不得出现：

`thinking budget ≥ max output budget`

导致模型推理完成后没有足够空间输出完整 Summary。

运行时至少保证：

`maxOutputTokens > thinkingTokenBudget + minimumFinalOutputReserve`

具体 reserve 由真机和真实 Summary 模板 benchmark 决定。

高级 UI 应明确提示：更高 Thinking 可能增加耗时、耗电和发热。

## 19A.6 Structured Output 兼容性

Stage 19A 开发时必须重新核验最新 LiteRT-LM：

- Thinking + constrained JSON / JSON Schema 是否可稳定共存
- response format 与 reasoning token 的实际约束

如果当时仍存在兼容问题，采用两阶段：

1. Thinking ON：语义分析 / reasoning / intermediate result
2. Thinking OFF：严格结构化整理 / schema validation

不得为了启用 Thinking 降低 Stage 11 已冻结的 structured result / evidence validation。

---

# Stage 19B — 离线 Audio LLM

## 19B.1 模型

继续只保留两个本地 Gemma 4 模型：

- Gemma 4 E2B：Audio LLM 默认 / 低资源
- Gemma 4 E4B：Audio LLM 高质量

不为了 Audio LLM 再增加第三个本地 Gemma 档位，除非未来 benchmark 给出明确必要性并重新确认规划。

## 19B.2 定位

Audio LLM 不替代专业 ASR。

默认主链仍为：

`Recording → ASR → structured transcript → Gemma 4 E4B Text → AI Summary`

离线 Audio LLM 是显式高级模式，用于补充：

- 语气
- 情绪/表达线索
- 非语音声音事件
- 音频环境
- 专业 ASR 文本难以表达的信息
- 直接音频问答/理解

## 19B.3 长音频

开发 Stage 19B 时必须重新核验 Gemma 4 当时官方音频输入时长/格式限制。

当前规划调研基线为单次音频输入需要短 chunk；实现必须按 runtime capability 分块，不把 1h/2h 文件整段直接载入模型。

建议链：

`Recording / canonical audio → bounded audio chunks → Gemma 4 Audio local understanding → structured intermediate observations → Gemma 4 E4B Text reduce → final insight/summary`

每个 chunk 必须保留 absolute canonical sample range，从而让最终 observation / evidence 可回链原始录音。

## 19B.4 性能

重点测试：

- E2B vs E4B Audio RTF
- peak RAM/PSS
- CPU/accelerator behavior
- thermal
- battery
- model load/unload cost
- 30 / 60 / 120 分钟分块总处理时间

本地 speech runtime 与 Gemma runtime 不应无必要长期同时常驻；需要设计明确模型释放/切换策略，避免 RAM 峰值叠加。

---

# Stage 19C — 云端 Audio LLM + 高级 AI / 语义能力

## 19C.1 云端 Audio LLM

主链：

`Recording / canonical compatible audio → Audio LLM Provider → structured understanding → AI Summary / insights`

支持：

- 多 Audio LLM Provider
- model discovery
- capability check
- connection test
- format / size / duration preflight
- upload progress
- cancel / retry
- long-audio strategy
- Audio-only 与 Audio + Transcript 联合分析
- provider/model/input mode/template/source lineage

必须明确告诉用户实际上传内容：

- 音频
- 转写文本
- 或两者

普通“AI 总结”不得因 Text LLM 失败自动切换成 Audio LLM 上传音频。

## 19C.2 高级 AI 内容能力

在 Stage 12 本地关键词全文搜索之上增加：

- 自动章节
- 关键词 / 主题提取
- 决策 / Action Items 深化
- 单条录音问答
- 多版本总结比较
- 语义搜索
- 相似录音 / 相似片段
- 跨录音主题聚合
- 内容知识问答

所有语义结果必须尽量回链到 Recording / Transcription / Summary / evidence range，不能生成不可追溯的孤立内容。

语义检索与 Stage 12 的确定性离线关键词搜索并存，不互相替代。

---

# Stage 19D — 全 App UI / UX 最终精修

Stage 19D 在主要核心能力完成后统一执行最终产品体验收口。

原则：

> 简洁、便捷、交互自然、美观、高频效率工具。

范围包括：

- 首页 / 设备
- 录音卡
- 录音库
- Recording Detail
- 播放器 / 波形
- 转写阅读模式 / 时间轴模式
- Speaker
- AI Summary
- Search
- Provider
- 模型管理
- 高级参数
- 后台任务 / 通知
- Loading / Empty / Error / Retry
- Dialog / Snackbar
- Dark Mode
- typography
- spacing
- icon consistency
- 动效
- 状态层级
- accessibility / 大字体 / 常见屏幕尺寸

Stage 19D 不再引入大规模核心功能、核心模型选型或数据库架构重写；主要目标是统一产品体验、减少交互摩擦和视觉不一致。

Stage 12A/12B 已冻结的高频效率工具方向继续作为设计基线。

---

# Stage 20 — Voica V2

维持后续大版本能力：

- 云同步
- 账号
- 多设备
- Speaker Voiceprint
- 跨录音全局 Speaker identity
- Web / PC
- 团队协作

Stage 20 的 Speaker Voiceprint 不得提前混入 Stage 13C 的 run-local diarization 性能专项。

---

# 最终顺序

从当前 Stage 13B 之后，路线固定为：

`Stage 13B → Stage 13C → Stage 14 → Stage 15 → Stage 16A → Stage 16B → Stage 17 → Stage 18 → Stage 19A → Stage 19B → Stage 19C → Stage 19D → Stage 20`

其中：

- Stage 13C：说话人分离性能专项
- Stage 16A：本地实时 ASR
- Stage 16B：本地文件 ASR V2 / Fun-ASR-Nano Benchmark
- Stage 17：云端文件 ASR / 音频上传转写
- Stage 18：云端实时 ASR
- Stage 19A：Gemma 4 E2B/E4B 离线文本 AI 总结 + Thinking 高级设置
- Stage 19B：Gemma 4 E2B/E4B 离线 Audio LLM
- Stage 19C：云端 Audio LLM + 高级 AI / 语义能力
- Stage 19D：全 App UI / UX 最终精修

---

# 固定阶段流程

继续遵循：

`读取上一阶段 Freeze/Handoff → 检查 GitHub 当前真实状态 → 修订需求 → 用户确认 → 修订开发规划 → 用户确认 → development branch → 开发 → Unit Test/Build/CI → APK → 真机测试 → 修复 → 用户确认“测试通过” → Freeze/Handoff → 最终 CI → 合并 main`

任何 production model channel 修改都必须独立受控并取得明确授权；本路线图的创建不等于授权模型 promotion。
