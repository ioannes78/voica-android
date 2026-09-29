# Voica

Voica 是面向 QS668 / CB08 AI 录音卡的 Android 原生客户端。

本项目为**全新、独立的 Android 项目**。Voica **不继承、不复制、不迁移、不翻译 `voice-card-android` 的任何代码**，包括源代码、测试代码、Gradle 配置、资源文件、数据库实现、模块实现和历史 Stage 代码。

## 协议与行为参考

协议事实优先级：

1. QS668 / CB08 真机可重复验证结果。
2. `nextproto1024/ai-recorder-card-open-protocol`，Stage 2 固定参考 commit：`e741ea72207f1a2aae3df4debc5c135728e0170e`。
3. `laidely/kardo`，固定参考 commit：`bcec3c5fdbcb34810a6f235e8b5873683f2ab951`，仅用于产品行为与设备交互交叉验证。
4. Android 官方 BLE API 行为。

`voice-card-android` 不作为 Voica 的代码、架构、测试或 Gradle 来源。

## 产品目标

- 稳定连接 QS668 / CB08 录音卡
- 获取设备电量、容量、固件版本并同步时间
- 浏览、下载和删除设备录音文件
- 保留原始 Opus 数据并生成稳定的 16 kHz PCM/WAV 音频
- 本地播放、精确跳转、倍速和转写同步
- Android 端本地语音转写
- 说话人分离
- AI 会议纪要
- 以稳定性、可测试性和真机可靠性为优先目标

## 技术路线

- Kotlin
- Jetpack Compose
- Kotlin Coroutines / Flow
- Android 原生 BLE
- Room / DataStore
- Android 兼容的本地 ASR 与说话人分离运行时
- OpenAI-compatible AI 接口

## 语言规范

- App 默认用户界面语言：**简体中文**
- 项目需求、架构、路线图、测试说明、Freeze/Handoff 文档：**简体中文**
- Git commit message：优先简体中文，可保留必要英文技术名词
- Kotlin 类名、函数名、模块名、协议常量、API 字段等技术标识按 Android/Kotlin 英文命名规范保留

## 开发原则

- `voice-card-android` 与 Voica 完全隔离，不作为源码来源
- 不进行 Swift → Kotlin 逐行翻译
- BLE、协议、音频、ASR、说话人分离和 UI 分层实现
- 协议与二进制行为必须有 deterministic/golden tests
- 删除等破坏性设备操作必须二次确认
- 长录音处理必须限制内存占用并支持取消
- CI 通过不能替代真机验收

## 当前状态

- Stage 0：已完成
- Stage 1：已完成 / 已真机验收 / 已冻结
- Stage 2：已完成 / 已两轮真机验收 / 已冻结
- Stage 3：已完成 / 已真机验收 / 已冻结
- 下一阶段：**Stage 4 — 设备文件列表 + 文件名解析**

Stage 2 已建立：

- `:core:ble`
- BLE 扫描 / 权限
- QS668/CB08 GATT Session
- 严格串行 GATT Operation Queue
- AE20/AE21/AE22/AE23 发现与订阅
- `requestMtu(517)`
- 时间同步
- 电量 / 充电
- 容量
- 固件
- Auth
- 有限自动重连
- 简体中文设备页与 BLE Diagnostics

Stage 2 真机确认 Actual MTU = **517**。

Stage 3 已建立：

- App 卡录音开始 / 暂停 / 继续 / 停止并保存
- 设备物理录音按键事件同步
- 录音状态 / 时长 / 当前大小 / 当前文件名 / 增益
- Recording 时约 1 秒 GET_TIME Poller
- Pause 语义锁存，兼容当前固件 GET_STATE=1 无法区分 Recording/Paused
- 物理事件 acknowledgement + reconciliation
- 最后成功设备记忆与 App 启动自动连接
- 录音状态 Diagnostics 与真机协议证据

Stage 3 真机确认 App 控制与设备物理按键控制均能稳定同步 UI。

项目文档：

- [产品需求](docs/PRODUCT_REQUIREMENTS.md)
- [系统架构](docs/ARCHITECTURE.md)
- [开发路线图](docs/ROADMAP.md)
- [Stage 2 测试](docs/STAGE_2_TEST.md)
- [Stage 2 Freeze](docs/STAGE_2_FREEZE.md)
- [Stage 2 Handoff](docs/STAGE_2_HANDOFF.md)
- [Stage 3 测试](docs/STAGE_3_TEST.md)
- [Stage 3 Freeze](docs/STAGE_3_FREEZE.md)
- [Stage 3 Handoff](docs/STAGE_3_HANDOFF.md)
- [开发规则](AGENTS.md)

## License

Voica 项目许可证尚未确定。
