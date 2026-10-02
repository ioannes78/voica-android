# Voica Android 全项目 Stage 开发路线图

当前状态：**Stage 11 已完成、用户验收通过并冻结；下一阶段为 Stage 12。**

整个 Voica 路线均为全新独立实现，不得从 `voice-card-android` 复制、迁移、继承、cherry-pick 或机械改写任何代码。

协议与行为参考：

- QS668 / CB08 真机可重复验证结果：最高优先级
- 官方 Android App `声云语音转写 3.0.9-u` 静态实现：协议/行为主参考，冲突时真机优先
- `nextproto1024/ai-recorder-card-open-protocol@e741ea72207f1a2aae3df4debc5c135728e0170e`
- `laidely/kardo@bcec3c5fdbcb34810a6f235e8b5873683f2ab951`：仅产品行为参考

## Stage 0 — 项目基线

状态：**已完成**

## Stage 1 — Android 工程基础 + QS668/CB08 协议核心

完成内容：

- Kotlin / Gradle / Jetpack Compose / Material 3 工程
- 简体中文 UI 基线
- CRC-16/XMODEM
- SequenceGenerator
- Frame Builder
- AE22 / AE23 独立流式 FrameParser
- TYPE/CMD 常量
- 文件名编码
- 文件列表/容量基础解码
- 2-2 36B 文件导入请求
- 2-12 分段请求
- 单文件删除请求
- Golden tests
- 精简 CI

状态：**已完成 / 已真机验收 / 已冻结**

## Stage 2 — BLE 连接 + 设备基础信息

完成内容：

- Android BLE 权限与 BLE Scanner
- 扫描去重、RSSI 更新、8s timeout
- BluetoothGatt Session
- Session generation / stale callback 隔离
- 严格串行 GATT Operation Queue
- AE20/AE21/AE22/AE23 发现和 properties 验证
- AE22/AE23 CCCD 串行订阅
- `requestMtu(517)`
- MTU 39 / 171 能力分级
- 时间同步
- 电量 / 充电状态
- 容量
- 固件版本
- Auth
- AE22 / AE23 独立 FrameParser 路由
- 两路完整帧均可完成 TYPE/CMD pending request
- 有限自动重连 1s / 2s / 4s，最多 3 次
- Bluetooth Off/On、前后台和 Compose 生命周期处理
- 简体中文设备页
- BLE Diagnostics
- 低成本 Android PR CI

真机关键事实：

- QS668/CB08 Actual MTU = **517**
- 36B atomic support：通过
- 168B data channel capability：通过
- Battery Response `0/4` 真机可以从 **AE23** 返回
- response sequence 不保证回显 request sequence

两轮真机验收：

- 主链路：通过
- 稳定性 / 异常链路：通过
- 连续连接/断开 >=10 次：通过
- 设备关机、远距离断开与有限重连：通过
- 用户主动断开不重连：通过
- Bluetooth Off/On：通过
- 前后台 >=5 次：通过
- 无 Crash / ANR

状态：**已完成 / 已真机验收 / 已冻结**

冻结文档：

- `docs/STAGE_2_TEST.md`
- `docs/STAGE_2_FREEZE.md`
- `docs/STAGE_2_HANDOFF.md`

## Stage 3 — 录音控制 + 设备实时状态

完成内容：

- App 卡录音开始 / 暂停 / 继续 / 停止并保存
- 当前录音状态、时长、大小、文件名、增益
- TYPE=3 设备物理按键事件
- App 主动控制与物理按键状态统一
- App 控制：CMD=2/4/6/8 + `01`
- 物理事件：CMD=1/3/5/7
- 物理事件 acknowledgement 后 reconciliation
- Pause 语义锁存，兼容当前固件 GET_STATE=1 无法区分 Recording/Paused
- Recording 前台约 1 秒 GET_TIME Poller
- Full sync / 物理事件优先停止 Poller，避免 GET_TIME 竞争
- Idle 不再查询当前 GET_TIME / GET_FILENAME
- 辅助读取失败仅进入 Diagnostics
- 最后成功设备地址持久化与自动连接
- Stage 1/2 回归测试持续通过

真机关键事实：

- App 卡录音控制不是奇数 CMD request 模型，而是偶数 CMD=2/4/6/8 + `01`
- 设备物理按键上报为奇数 CMD=1/3/5/7
- 当前固件 Pause 后 GET_STATE 可持续返回 1，因此不能仅靠 GET_STATE 判断 Paused
- GET_STATE=2 可确认 Idle
- GET_TIME 使用 LE duration + size，真机显示与设备录音一致
- 当前文件名示例为 `noteYYYYMMDD-HHMMSS.opus`
- 物理事件与 App 控制均可稳定驱动 UI 收敛

状态：**已完成 / 已真机验收 / 已冻结**

冻结文档：

- `docs/STAGE_3_TEST.md`
- `docs/STAGE_3_FREEZE.md`
- `docs/STAGE_3_HANDOFF.md`

## Stage 4 — 设备文件列表 + 文件名解析

完成内容：

- TYPE=2/CMD=0 文件列表请求
- TYPE=2/CMD=1 多帧数据块聚合
- TYPE=2/CMD=18 正常完成信号
- 列表 COUNT / time / size 按 BE32 严格解析
- 官方 App 兼容的动态 filename field 解析
- 当前真机 filename field = 20B
- 标准截断名 `noteYYYYMMDD-HHMMSS.` 安全恢复为 `.opus`
- 文件录制时间解析、时长、大小、排序
- 真机确认 entry 第一个 BE32 = duration seconds
- RemoteDeviceFile 稳定/临时 identity 契约
- 手动刷新、Fresh/Empty/Stale/Failed 状态
- Disconnect/session generation 防旧列表污染
- Recording/Paused 时禁止文件刷新，保护 Stage 3 Poller
- 文件列表 Diagnostics
- 2/12 已重新定位为区间文件传输，不用于 Stage 4 文件名探测

状态：**已完成 / 已真机验收 / 已冻结**

冻结文档：

- `docs/STAGE_4_TEST.md`
- `docs/STAGE_4_FREEZE.md`
- `docs/STAGE_4_HANDOFF.md`
- `docs/STAGE_4_PROTOCOL_FINDINGS.md`
- `docs/STAGE_4_REAL_DEVICE_FINDINGS.md`

## Stage 5 — 文件下载 + 删除 + 原始音频落盘

完成内容：

- Ready / reconnect / Recording finalized 自动刷新文件列表与 refresh coalescing
- TYPE=2/CMD=2 → CMD=3 → CMD=4×N → CMD=5 完整下载链路
- 文件 DATA 专用可靠通道，不依赖可丢帧 observer SharedFlow
- 下载进度、取消 CMD=7、超时、断线终止、session generation 防污染
- `.part` 流式落盘、size 校验、SHA-256、fsync、atomic commit
- app-private / noBackupFilesDir 本地原始录音保存
- 重复下载保护与 Remote / Local 独立生命周期
- 本地文件独立删除；不联动设备
- CMD=12 ranged transfer 真机确认 end exclusive：`[start, end)`
- 单录音设备删除 CMD=8 真机协议冻结
- 设备删除二次确认、OutcomeUnknown 防重复 destructive retry、Fresh list 验证
- 真机确认删除设备录音会同时删除物理同名 `.opus + .wav`
- 真机确认 BLE 下载的是原始 `.opus` 字节流，不是 WAV
- 本地显示“OPUS 原始流”
- 不提供 Delete All / DeleteBoth
- Stage 1–4 regression 持续通过

状态：**已完成 / 已真机验收 / 已冻结**

冻结文档：

- `docs/STAGE_5_TEST.md`
- `docs/STAGE_5_FREEZE.md`
- `docs/STAGE_5_HANDOFF.md`
- `docs/STAGE_5_PROTOCOL_FINDINGS.md`
- `docs/STAGE_5_REAL_DEVICE_FINDINGS.md`

## Stage 6 — 本地录音库 + Opus/WAV 音频链路

完成内容：

- Room 正式本地录音库与 Stage 5 legacy metadata 导入
- Recording / AudioAsset / AudioDerivation 数据模型
- DEVICE_OPUS / DEVICE_WAV 独立下载与独立验证
- 设备 OPUS / WAV 真实文件大小显示
- WAV CMD=12 `[0,44)` RIFF header probe
- OPUS / WAV 真实百分比下载进度
- raw Opus framing validation
- official libopus 1.6.1 JNI/NDK decoder
- raw Opus → PCM → 16 kHz mono PCM16 canonical WAV
- PCM WAV → canonical WAV 流式归一化
- 原始设备音频保留、派生资产幂等/可重建
- 本地浏览、逻辑重命名、独立删除
- 标准设备逻辑录音名默认去掉 `.opus/.wav`
- conversion cancellation / interrupted recovery
- `AudioSourceResolver` / `PcmSourceResolver` / `PcmSource` 稳定接口
- Stage 1–5 regression 持续通过
- Alpha 3 QS668/CB08 真机功能验收通过

状态：**已完成 / 已真机验收 / 已冻结**

冻结文档：

- `docs/STAGE_6_TEST.md`
- `docs/STAGE_6_FREEZE.md`
- `docs/STAGE_6_HANDOFF.md`
- `docs/STAGE_6_PROTOCOL_FINDINGS.md`
- `docs/STAGE_6_REAL_DEVICE_FINDINGS.md`

## Stage 7 — 播放器 + 精确时间轴

状态：**已完成 / 已用户验收 / 已冻结**

完成内容：

- canonical WAV / AudioTrack MODE_STREAM 播放
- absolute 16 kHz PCM sample timeline
- 精确 sample seek 与 rapid seek latest-wins
- presented-position tracking
- 当前时间 / 总时长
- 0.5× / 0.75× / 1.0× / 1.25× / 1.5× / 2.0×
- Audio Focus / Bluetooth route / noisy / app lifecycle
- device recording → local playback pause 互锁
- Stage 8 PcmSource 与 Stage 10 PlaybackSnapshot 时间契约
- 30/60/120min virtual source 与 2h rapid seek 自动化专项

验收说明：

- Bluetooth / Audio Focus 专项 1–7 真机通过
- 真实 30min / 1h / 2h、2h 综合稳定性、ADB meminfo 未真机执行
- 上述长录音真机测试债务转入 Stage 13

## Stage 8 — 本地 ASR + VAD + 标点 + 模型管理

状态：**已完成 / 已真机验收 / 已冻结**

完成内容：

- sherpa-onnx 1.13.8 Android runtime，arm64-v8a
- Silero VAD int8 APK 内置基线 + managed override
- Small Bilingual Zipformer zh-en 作为 first-pass / streaming ASR
- CT-Transformer zh-en int8 标点恢复
- SenseVoice 2024 int8 High Quality second pass
- Room 1 → 2 显式迁移
- Transcription / Segment / Token 持久化与状态机
- FAST / HIGH_QUALITY 独立版本，不互相覆盖
- “转写版本”列表与版本切换查看
- 模型下载、SHA 校验、staging、atomic promotion、rollback、exact revision lease
- HTTP Range 断点续传与后台校验/解包
- 独立 `ioannes78/voica-model-channel` production/candidate 发布链
- 固定 production manifest；Debug/QA candidate override 只用于未合并候选验收
- 固定签名 QA 测试轨道
- virtual 30/60/120min bounded-read 自动化；真实长录音压力仍留 Stage 13

处理链：

`16 kHz canonical PCM → Silero VAD → Small Bilingual → CT-Transformer → Timed Transcript`

High Quality：

`16 kHz canonical PCM → Silero VAD → Small Bilingual first pass → SenseVoice second pass → punctuation finalization → persisted transcript`

冻结文档：

- `docs/STAGE_8_TEST.md`
- `docs/STAGE_8_FREEZE.md`
- `docs/STAGE_8_HANDOFF.md`

## Stage 9 — 说话人分离

状态：**已完成 / 已真机验收 / 已冻结**

完成内容：

- 复用 Stage 8 Silero VAD 做 speech / non-speech gating
- Pyannote Segmentation 3.0 INT8 做 speaker activity/change/overlap segmentation
- ERes2Net Base zh-CN 16 kHz 作为正式 speaker embedding
- sherpa-onnx 1.13.8 + FastClustering；未引入第二套推理 runtime
- Room v2 → v3 additive migration
- DiarizationRun / Speaker / SpeakerTurn / TranscriptSpeakerAlignment / TranscriptSpeakerSpan 独立持久化
- 60s chunk + 10s overlap bounded-memory 处理
- 顺序式 PcmSource window reader；不依赖随机 seek
- ERes2Net anchor embedding + overlap/temporal evidence 的 cross-chunk stitching
- absolute canonical sample timeline 上的 token-level speaker alignment
- SECOND_PASS timed token 优先，FIRST_PASS timed token fallback
- overlap / ambiguous attribution 显式保留；同一文字不复制给多个 speaker
- Speaker 1 / Speaker 2 等 run-local 匿名身份和局部重命名
- FAST/HIGH_QUALITY 可复用同一 completed diarization run
- **直接 FAST/HQ 转写默认自动串行继续说话人分离与 Speaker 对齐**
- 独立“单独说话人分离”保留给补做、重跑和模型专项测试
- 查看历史转写不会强制重新跑重型 diarization
- exact model revision lease、取消、失败、启动中断 reconciliation
- Pyannote + ERes2Net bundle smoke 在创建 run 前执行
- Stage 9 speaker bundle 已从 candidate promotion 到 production model manifest
- production 正式运行不依赖 candidate URL；Debug candidate override 只用于未来未合并候选验收
- virtual 30/60/120min bounded-memory 自动化继续覆盖；真实 30min/1h/2h、定量 RTF/PSS/thermal soak 仍留 Stage 13

冻结文档：

- `docs/STAGE_9_TEST.md`
- `docs/STAGE_9_FREEZE.md`
- `docs/STAGE_9_HANDOFF.md`

## Stage 10 — 转写时间轴 + 播放同步

状态：**已完成 / 已真机验收 / 已冻结**

完成内容：

- absolute canonical PCM sample index 作为唯一 Playback ↔ Transcript 同步时间轴
- speaker span / transcript segment 派生 Timeline，不建立第二套转写数据库
- Stage 9 token → finalText projection 抽取为共享纯逻辑
- EXACT token projection 深色高亮；HEURISTIC/UNAVAILABLE 只做 row/span 浅色高亮
- FAST FIRST_PASS timed token 与 HQ SECOND_PASS→FIRST_PASS fallback
- 点击 row/span/segment seek；可靠 timed token 精确 seek；统一走 sample-based seek
- 点击转写文字后 seek + play
- playback source/canonical lineage guard，避免旧 canonical asset 错配
- FAST/HQ/同模式历史版本按 transcriptionId 隔离；切版本保留当前播放 sample
- speaker-aware row、重叠/ambiguous/unresolved 状态与 rename 刷新
- 自动滚动仅在 active row 变化时触发
- 用户手动滚动立即暂停 viewport 跟随；显式“跟随播放”恢复
- Timeline 一次构建 + 高频轻量 active state；无 50ms Room IO / 全文重建
- 顺序播放前向 cursor；随机/反向 seek 二分
- Room schema 保持 v3，仅增加 transcriptionId 批量 token query
- 30/60/120min virtual timeline 自动化与 20Hz 映射回归
- QA `0.10.0-stage10-alpha1` 真机验收通过

冻结文档：

- `docs/STAGE_10_TEST.md`
- `docs/STAGE_10_FREEZE.md`
- `docs/STAGE_10_HANDOFF.md`

## Stage 11 — AI 智能总结 / 内容理解

状态：**已完成 / 已真机验收 / 已冻结**

完成内容包括：

- Room v4 AI Summary/template/evidence/checkpoint additive persistence
- `:core:ai` 与 `:engine:llm` 独立模块边界
- SMART / PRESET / CUSTOM 三种总结模式
- 多 Provider Profile、本地默认 Provider、模型发现与 synthetic connection test
- OpenAI / Gemini / Grok / DeepSeek / 火山 / 硅基流动 / OpenRouter 等 Provider 契约与适配
- Android Keystore + AES-GCM 保存 API Key，密钥不进入 Room/summary lineage
- structured transcript + stable evidenceRef + strict schema/evidence validation
- 长转写 token budget + hierarchical map/reduce + Room checkpoint
- interrupted/cancel/retry 语义
- 默认简体中文 Summary 输出
- OpenRouter 大模型目录搜索选择器
- SiliconFlow Qwen3-32B 与 Volcengine DeepSeek V4.1 Flash structured-output compatibility
- xAI/Grok 中文输出真机修复
- QA `0.11.5-stage11-qa-fix5` 真机验收通过

冻结文档：

- `docs/STAGE_11_TEST.md`
- `docs/STAGE_11_FREEZE.md`
- `docs/STAGE_11_HANDOFF.md`


Stage 11 不把 Voica 限定为“会议录音”。AI 总结面向会议、访谈、课堂/培训、工作汇报、项目讨论、销售沟通、个人语音笔记、头脑风暴等多种录音内容。

### AI 总结主流程

默认处理链：

`录音 → 本地/云端 ASR → 结构化转写文本 → AI 内容理解 → 智能总结`

Stage 11 的默认 AI 总结输入是**转写后的文本和结构化转写元数据**，不是原始录音文件。

可提供给 LLM 的结构包括：

- 转写正文
- 时间戳
- Stage 9 后的 Speaker 标签
- 段落 / segment 边界
- 可用语言信息
- 与录音、transcription version 的稳定关联标识

### 智能总结模式

默认入口为“智能总结”，用户无需先选择固定模板。

AI 先对转写内容做轻量内容理解，例如：

- contentType：会议 / 访谈 / 课堂 / 工作汇报 / 项目讨论 / 销售沟通 / 个人笔记 / 其他
- confidence
- 内容特征
- 推荐输出章节
- 是否存在明确决策、待办、问答、知识点、观点或风险

再根据内容动态生成合适的总结结构。

“智能总结”不得只是在后台机械套用一个固定会议模板。

### 模板体系

Stage 11 同时提供三种使用方式：

1. **智能总结（默认）**
   - AI 自动判断内容性质
   - 自动选择输出结构
   - 用户无需预先选择模板

2. **预设模板**
   - 通用总结
   - 会议纪要
   - 访谈整理
   - 课堂 / 培训笔记
   - 工作总结 / 汇报
   - 项目讨论
   - 销售沟通
   - 头脑风暴
   - 个人语音笔记
   - 后续可按真实用户需求扩展

3. **自定义模板**
   - 用户定义固定章节、关注点和输出要求
   - 模板只控制 AI 输出结构，不绑定 ASR Provider 或底层转写模型

### LLM Provider

- OpenAI-compatible Provider 抽象
- Base URL / API Key / Model
- 模型列表自动获取（Provider 支持时）
- 连接测试
- Provider / Model 可切换
- LLM Provider 与 ASR Provider 解耦
- 同一份转写可重复使用不同模板或不同 LLM 生成多个 AI 总结版本，无需重新上传音频

### 默认隐私 / 上传策略

默认：

**只把转写文本及必要结构化元数据发送给 LLM，不把原始录音发送给 LLM。**

因此允许：

`本地 Small Bilingual / SenseVoice → 本地转写 → 云端 LLM 总结`

原始音频可以完全留在设备端。

只有以下场景才允许上传音频：

- Stage 17/18 用户主动选择云端 ASR，需要把音频/实时音频发送给 ASR Provider
- 未来明确提供“直接音频理解 / Audio LLM”高级模式时，用户主动选择并明确知晓原始音频会上传云端

直接音频分析不得成为 Stage 11 默认总结链路。

### Audio LLM 高级模式契约

Stage 11 先冻结“Audio LLM 直接音频理解”的 Provider 与数据契约，但不要求在 Stage 11 完成全部高级能力。

Audio LLM 与 ASR Provider、Text LLM Provider 必须是三个独立能力层：

- ASR Provider：音频 → 转写
- Text LLM Provider：结构化转写文本 → 总结/问答
- Audio LLM Provider：原始/标准化音频 → 直接内容理解

Stage 11 需要为未来 Audio LLM 预留：

- Provider capability discovery：是否接受音频、支持的音频格式、最大时长/大小、是否支持时间戳/说话人/多轮问答
- Model list / connection test
- 明确的 AudioUnderstandingProvider 或等效抽象，不复用 CloudAsrEngine 假装 Audio LLM
- 输入优先使用 Voica 已验证的 canonical audio 或 Provider 明确要求的兼容格式
- 结果必须转换为 Voica 统一 AI Summary / structured insight 数据结构
- 每次结果记录 provider / model / input mode / source recording / source transcription（如有）等 lineage
- UI 必须明确区分“基于转写文本生成”与“直接分析录音”
- 开始前明确提示：原始/标准化音频将上传给第三方 Audio LLM Provider
- 默认关闭，不自动上传音频
- 用户取消后停止上传/请求；不得因为生成总结而静默复用音频做其他云端任务
- Provider 不支持、文件过大、时长超限或网络失败时，不得自动改为上传到另一个 Provider

Stage 11 可实现最小 capability/configuration 骨架；完整直接音频理解体验统一在 Stage 19 实现。

### 输出结构

智能总结根据内容动态选择，但基础可组合对象包括：

- 摘要
- 核心观点
- 结论
- 决策
- 待办 / Action Items
- 问题
- 风险
- 关键事实
- 主要问答
- 知识点
- 后续事项

AI 输出必须与原始 Recording / Transcription version 保持可追溯关联，为 Stage 12 内容管理和 Stage 19 语义检索提供基础。

## Stage 12 — 产品 UI / UX 完整化 + 本地内容管理

Stage 12 统一完成产品层 UI/UX、本地录音库管理、转写/AI 总结内容管理与本地全文搜索。不得重新建立第二套录音库或第二套转写数据源，必须继续复用 Stage 6/8/10/11 已冻结的数据与时间轴契约。

### Stage 12A — UI / UX 完整化

- Material 3 视觉体系统一
- Dark Mode
- Device / 本地录音 / 转写 / AI 总结 / 设置页面层级与信息架构统一
- 按钮、卡片、列表、间距、Typography、图标与状态组件统一
- Loading / Error / Empty / Retry / Offline State 完整化
- 中文文案、提示、确认、错误信息与危险操作语义统一
- 适配不同 Android 屏幕尺寸、长列表和大文本内容
- 统一长按、更多菜单、多选、搜索、筛选、分享、导出等交互模式
- Stage 9–11 新增页面在本阶段统一收口，不在此前阶段重复做全局 redesign

### Stage 12B — 本地录音库增强

在 Stage 6 已冻结的 Room 本地录音库基础上增强。

- 搜索：按录音名称、时间及可用元数据搜索
- 排序 / 筛选：录制时间、下载时间、名称、大小、是否已转写、是否已有 AI 总结等
- 文件夹 / 标签：使用 Room 中的逻辑组织信息，不通过移动 app-private 原始音频破坏资产引用关系
- 收藏：录音收藏/取消收藏与收藏筛选
- 多选模式
- 批量删除：明确二次确认；保持 Recording / AudioAsset / AudioDerivation / Transcription / AI 总结关联一致性
- 批量导出
- 手机音频导入：使用 Android Storage Access Framework / 系统文件选择器；导入后进入 Voica 本地录音库并复用现有 canonical audio pipeline 校验/转换
- 导出到 Downloads：使用 Android 合规系统存储接口，不直接依赖任意外部存储路径
- 音频分享：通过 Android Sharesheet 分享；可用目标由系统和已安装 App 决定
- 存储空间管理：分别显示原始录音、canonical 派生音频、模型、转写/AI 总结数据与可清理临时缓存占用
- 清理策略：优先清理可重建派生文件/临时文件，不静默删除用户原始录音
- 原始文件保护：DEVICE_OPUS / DEVICE_WAV / 用户导入原文件继续作为可追溯资产保存，除非用户明确删除
- 导入/导出失败、低存储空间、权限/URI 失效等状态必须可恢复并明确提示

### Stage 12C — 转写 / AI 总结内容管理 + 统一全文搜索

#### 转写文本管理

基于 Stage 8 的多版本 Transcription 和 Stage 10 的时间轴/播放同步继续增强：

- 复制全文
- 复制选中文本
- 复制单个语音段
- 分享全文
- 分享选中片段
- Android Sharesheet 转发到微信、邮件、笔记等已安装 App
- 导出 TXT
- 导出 Markdown
- 正式文档导出可加入 PDF / DOCX
- 转写版本命名、查看与管理
- 删除指定转写版本，保留其他版本及原始录音
- Fast / High Quality / 后续云端版本切换
- 文本内搜索
- 关键词高亮
- 长按文本操作菜单
- 复制/导出时可选择是否包含时间戳、说话人标签
- 文本操作不得破坏 Stage 8 保存的原始转写版本及 model lineage

#### AI 总结 / 纪要管理

基于 Stage 11 的智能总结、预设模板结果和自定义模板结果：

- 修改 AI 总结标题
- 复制整份 AI 总结
- 复制单个章节
- 复制待办 / 决策等结构化内容
- 分享 / 转发整份 AI 总结或选定章节
- TXT / Markdown 导出
- 正式文档导出可加入 PDF / DOCX
- 收藏
- 标签
- 删除
- 历史版本查看
- AI 总结与对应录音、转写版本、使用的总结模式/模板以及输入模式（TRANSCRIPT_TEXT / DIRECT_AUDIO）保持明确关联
- 从 AI 总结跳转到对应转写内容；Stage 10 已有时间轴映射时可继续跳转到相关音频位置
- 分享时支持按实际总结结构选择章节，例如：仅摘要 / 摘要+决策 / 摘要+待办 / 完整内容

#### 统一本地全文搜索

Stage 12 建立统一的本地关键词/全文搜索入口，至少覆盖：

- 录音名称
- 转写正文
- AI 总结正文
- 标签
- 可索引的待办 / 决策 / 关键词等结构化文本

搜索结果按内容类型区分，并可进入对应对象：

- 录音命中 → 打开录音详情
- 转写命中 → 打开具体 transcription version；Stage 10 时间信息存在时定位对应文本/音频位置
- AI 总结命中 → 打开对应总结/章节
- 标签命中 → 打开相应筛选结果

技术方向优先采用 Room FTS 或等效本地全文索引，避免对大量长文本长期依赖 `LIKE '%keyword%'` 全表扫描。

Stage 12 搜索必须：

- 可离线使用
- 不依赖云端 API
- 索引与 Recording / Transcription / AI 总结生命周期保持一致
- 删除/重建内容后不会留下孤立搜索结果
- 对中文与英文关键词均有明确、可测试的匹配行为

### Stage 12 边界

- Stage 12 负责本地录音、转写和 AI 总结的产品化管理、复制、分享、导入导出与关键词全文搜索。
- 不在 Stage 12 实现 BLE 后台/锁屏持续下载、长文件断连恢复或 Foreground Service；这些仍属于 Stage 13。
- 不在 Stage 12 改写 Stage 8 的转写历史版本语义，也不破坏 absolute canonical PCM sample index 时间轴。
- 不在 Stage 12 实现“找一下上个月讨论供应链风险的录音”这类语义理解搜索；此类跨录音语义检索属于 Stage 19。
- 不把 Stage 20 的账号、云同步、跨设备录音库与团队协作提前到 Stage 12。

## Stage 13 — 稳定性与长录音专项

BLE soak、多文件/大文件/断连下载、1h/2h 音频、长转写、RAM/CPU/温度、锁屏/后台/低存储等。

后台下载在 Stage 13 正式实现，范围固定为：

- Android Foreground Service 承载设备文件传输
- App 切到后台后继续下载
- 熄屏/锁屏后继续下载
- 常驻系统通知显示文件名、OPUS/WAV 格式和真实下载百分比
- 通知中支持取消下载
- BLE 后台连接保持与系统限制适配
- 断连后的明确失败/恢复策略
- App 进程被系统回收后的下载状态恢复策略
- 多文件、大文件及 1h/2h 录音下载压力测试
- 录音控制继续高于文件下载优先级

Stage 7 仍不实现 Foreground Service；BLE 后台可靠下载与真实 1h/2h 长时资源压力继续由 Stage 13 负责。

## Stage 14 — Voica V1.0 Release Freeze

Release APK/AAB、R8、权限隐私、Schema/Model Manifest Freeze、完整 Test/Architecture/Handoff。

## Stage 15 — 实时音频链路

TYPE=1 Realtime：BLE Audio → Opus → PCM，先保证音频稳定。

## Stage 16 — 本地实时转写

Streaming VAD / ASR、Interim/Stable/Final、两层标点。

## Stage 17 — 云端文件转写

- CloudAsrEngine
- 多 ASR Provider
- Provider / Model 配置、模型列表与连接测试
- 长录音 / 高精度文件转写
- 明确标识音频将上传给 ASR Provider
- 云端 ASR 输出仍转换为 Voica 统一 Transcription / segment / timestamp 结构
- ASR Provider 与 Stage 11 LLM Provider 解耦；云端转写完成后 AI 总结默认继续只发送转写文本给 LLM

## Stage 18 — 云端实时转写

- BLE Audio → Cloud Realtime ASR
- 明确提示实时音频将发送给 ASR Provider
- Interim / Stable / Final 统一映射到 Voica transcript contract
- 网络中断、重连与 fallback
- ASR Provider 与 LLM Provider 继续解耦

## Stage 19 — 高级 AI 内容助手 + 语义检索 + Audio LLM

在 Stage 12 本地关键词全文搜索和 Stage 11 AI 智能总结基础上增加跨录音 AI 语义能力，并正式实现“Audio LLM 直接音频理解”高级模式。

### 高级 AI 内容能力

- 自动章节
- 关键词 / 主题提取
- 决策 / Action Items 深化
- 单条录音/转写/AI 总结问答
- 多版本 AI 总结 / 纪要
- 跨录音语义搜索
- 自然语言检索，例如“找一下上个月讨论供应链风险的录音”
- 相似录音 / 相似片段发现
- 跨录音主题聚合
- 基于录音、转写与 AI 总结的内容知识问答
- 语义搜索结果必须回链到原始录音 / 转写版本 / AI 总结，不生成不可追溯的孤立答案

### Audio LLM 直接音频理解

提供显式高级入口：直接分析录音（Audio LLM）。

处理链：

`Recording / canonical audio → Audio LLM Provider → structured understanding → AI Summary / insights`

与默认文本总结链并存：

`Recording → ASR → structured transcript → Text LLM → AI Summary`

Audio LLM 模式至少支持：

- 用户主动选择 Audio LLM Provider / Model
- Provider capability 检查与连接测试
- 根据 Provider 限制做时长、大小、格式预检
- 必要时从已验证 canonical audio 生成兼容上传格式，不修改原始录音
- 上传进度、取消、失败重试
- 长音频在 Provider 支持范围内的分块/上传策略；不得破坏 absolute canonical PCM timeline
- 直接生成内容类型、摘要、章节、观点、决策、待办、风险、知识点等结构化结果
- Provider 支持时保留时间引用 / evidence ranges，并映射回 Voica canonical timeline
- 可选择“仅 Audio LLM”或“Audio LLM + 已有转写联合分析”
- 联合分析时必须明确哪些输入发送到云端：音频、转写文本，或两者
- 同一录音可以保留 Text LLM 与 Audio LLM 的多个独立 AI 总结版本，不能互相覆盖
- 每个结果保存 provider、model、input mode、template、生成时间、source recording/transcription 等 lineage
- 允许用户比较“基于转写文本总结”和“直接音频理解”的结果，但不自动指定哪个更正确

### 隐私与成本门禁

Audio LLM 是高级可选模式，默认关闭。

每次首次使用某 Provider/模式时必须明确提示：

- 音频将上传到第三方 Provider
- 可能产生网络流量和 API 费用
- Provider 可能有文件大小、时长、区域、保留策略等限制
- 是否同时上传已有转写文本

不得：

- 因普通“AI 总结”按钮而静默切换到 Audio LLM
- 因 Text LLM 失败而自动上传音频
- 将 Audio LLM Provider 与 ASR Provider 视为同一个接口
- 在用户未确认时上传原始录音

Stage 19 的语义检索不得替代 Stage 12 的离线关键词全文搜索；两者应并存，分别覆盖确定性关键词检索与语义理解场景。

## Stage 20 — Voica V2

云同步、账号、多设备、Speaker Voiceprint、跨录音 Speaker、Web/PC、团队协作等。

## 固定 Stage 流程

`读取上一阶段 Freeze → 检查 GitHub 当前真实状态 → 修订需求 → 用户确认 → 修订开发规划 → 用户确认 → development branch → 开发 → Unit Test/Build/CI → APK → 真机测试 → 修复 → 用户确认“测试通过” → Freeze/Handoff → 最终 CI → 合并 main`
