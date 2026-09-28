# Voica

Voica 是面向 QS668 / CB08 AI 录音卡的 Android 原生客户端。

本项目为**全新、独立的 Android 项目**。Voica **不继承、不复制、不迁移、不翻译 `voice-card-android` 的任何代码**，包括源代码、测试代码、Gradle 配置、资源文件、数据库实现、模块实现和历史 Stage 代码。

Voica 以公开项目 [laidely/kardo](https://github.com/laidely/kardo) 的可观察产品行为、公开协议事实和设备交互结果作为参考基线，固定参考提交：

`bcec3c5fdbcb34810a6f235e8b5873683f2ab951`

Voica 在 Android 平台上重新设计和独立实现。

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

当前处于规划基线阶段，尚未开始生产功能代码开发。

项目文档：

- [产品需求](docs/PRODUCT_REQUIREMENTS.md)
- [Kardo 参考基线](docs/KARDO_REFERENCE_BASELINE.md)
- [系统架构](docs/ARCHITECTURE.md)
- [开发路线图](docs/ROADMAP.md)
- [开发规则](AGENTS.md)

## Kardo 参考项目

参考仓库：`laidely/kardo`  
固定参考提交：`bcec3c5fdbcb34810a6f235e8b5873683f2ab951`

该参考仓库在固定基线中未声明项目级许可证，因此 Voica 仅把其作为产品行为、公开协议事实和设备行为参考，不直接复制或机械翻译其中的 Swift 实现。

## License

Voica 项目许可证尚未确定。
