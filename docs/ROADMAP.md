# Voica Android 全项目 Stage 开发路线图

当前状态：**Stage 2 已完成、两轮真机验收通过并冻结；下一阶段为 Stage 3。**

整个 Voica 路线均为全新独立实现，不得从 `voice-card-android` 复制、迁移、继承、cherry-pick 或机械改写任何代码。

协议与行为参考：

- QS668 / CB08 真机可重复验证结果：最高优先级
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

目标：

- 获取录音状态
- 开始录音
- 暂停/恢复
- 停止保存
- 当前录音时长
- 当前录音文件名
- AE23 设备按键事件
- App 发起与物理按键发起状态统一

Stage 3 开始前必须先读取 Stage 2 Freeze/Handoff 并重新检查 `main` 当前真实状态。

退出条件：App 控制与设备物理按键操作均能稳定同步 UI。

## Stage 4 — 设备文件列表 + 文件名解析

内容：

- 文件列表请求与解析
- 时长/大小/名称
- 排序和刷新
- 短/截断文件名处理
- 2-12 分段探测可用完整文件名
- 已下载状态映射

## Stage 5 — 文件下载 + 删除 + 原始音频落盘

内容：

- 候选文件名 fallback
- 下载进度/取消/重试/超时
- 断连恢复策略
- TYPE=2/CMD=2 36B 一次完整 GATT Write
- 重复下载保护
- 原始音频可靠落盘
- 单文件设备删除
- 删除二次确认
- 不提供 Delete All

## Stage 6 — 本地录音库 + Opus/WAV 音频链路

内容：

- Room 本地元数据
- 原始 Opus 校验
- QS668 原始包结构重新验证
- Ogg/Opus/PCM/WAV 处理
- 16 kHz 标准音频
- 本地浏览/重命名/删除

## Stage 7 — 播放器 + 精确时间轴

内容：

- 播放/暂停
- 精确 seek
- 当前时间/总时长
- 倍速
- 长录音和快速连续 seek
- 为转写同步暴露稳定时间接口

## Stage 8 — 本地 ASR + VAD + 标点 + 模型管理

处理链：

`16 kHz PCM → VAD → SpeechSegment → ASR → 标点能力判断 → PunctuationEngine（必要时）→ Timed Transcript`

不把 VAD 锁死在 WebRTC；WebRTC、Silero、sherpa-onnx 通过 Android 实测后选择。

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
