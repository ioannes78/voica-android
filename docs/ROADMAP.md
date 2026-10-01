# Voica Android 全项目 Stage 开发路线图

当前状态：**Stage 8 已完成、用户验收通过并冻结；下一阶段为 Stage 9。**

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

- DiarizationEngine
- Speaker ID
- 长录音分块
- 时间段归一化与同 Speaker 合并

## Stage 10 — 转写时间轴 + 播放同步

- speaker + ASR + 标点整合
- 点击文字 seek
- 播放高亮
- 自动滚动与用户滚动保护

## Stage 11 — AI 会议纪要

- OpenAI-compatible Provider
- Base URL / API Key / Model
- 模型列表与连接测试
- 中文会议摘要、结论、待办、决策和问题
- 默认只上传文字

## Stage 12 — 产品 UI / UX 完整化

Material 3、Dark Mode、Loading/Error/Empty State、中文文案统一。

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

CloudAsrEngine、多 Provider、长录音/高精度场景。

## Stage 18 — 云端实时转写

BLE Audio → Cloud Realtime ASR，网络中断/fallback。

## Stage 19 — 高级 AI 会议助手

自动章节、关键词、决策/Action Items、录音问答、多版本会议纪要。

## Stage 20 — Voica V2

云同步、账号、多设备、Speaker Voiceprint、跨会议 Speaker、Web/PC、团队协作等。

## 固定 Stage 流程

`读取上一阶段 Freeze → 检查 GitHub 当前真实状态 → 修订需求 → 用户确认 → 修订开发规划 → 用户确认 → development branch → 开发 → Unit Test/Build/CI → APK → 真机测试 → 修复 → 用户确认“测试通过” → Freeze/Handoff → 最终 CI → 合并 main`
