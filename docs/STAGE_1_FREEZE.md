# Voica Stage 1 Freeze

状态：**FROZEN / ACCEPTED**

用户真机验收日期：2026-09-29

## 1. 冻结范围

Stage 1 仅冻结以下能力：

- 全新 Android / Kotlin / Compose 工程基础
- 简体中文 UI 基线
- `:core:protocol` 纯 Kotlin QS668/CB08 协议核心
- CRC-16/XMODEM
- SequenceGenerator
- 协议 Frame Builder
- AE22 / AE23 独立流式 FrameParser
- TYPE / CMD 常量
- UTF-8 24B 文件名编码
- 文件列表与容量基础字段解码
- TYPE=2/CMD=2 36B 文件导入请求编码
- TYPE=2/CMD=12 Segment 请求编码
- 单文件删除请求编码
- deterministic / golden unit tests
- 中文协议诊断页
- 精简 GitHub Actions CI

Stage 1 **不冻结、也不宣称实现**：

- BLE Scan / BluetoothGatt
- 真机连接和 GATT 队列
- 文件实际下载/删除
- Opus/WAV 音频链路
- Room
- 播放器
- VAD / ASR / 标点 / Speaker
- AI
- 本地或云端实时转写

## 2. 技术基线

- Kotlin：2.4.20
- Android Gradle Plugin：9.4.0
- Gradle：9.6
- JDK：17
- compileSdk：37.1
- targetSdk：37
- minSdk：26
- Compose BOM：2026.09.00
- Activity Compose：1.13.0
- Application ID：`io.github.ioannes78.voica`
- versionCode：1
- versionName：`0.1.0-stage1`
- 默认产品语言：简体中文

## 3. 冻结实现基线

验证实现提交：

`cc0d4aa19fe02d8bddcf6fa4d4a2d5d08403110e`

开发分支：

`stage1-development`

Pull Request：

`#1 Stage 1：Android 工程基础与 QS668/CB08 协议核心`

## 4. 自动验证证据

最终成功 CI：

- Workflow：`Stage 1 CI`
- Run ID：`36470063093`
- `:core:protocol:test`：通过
- `:app:assembleDebug`：通过
- APK artifact 上传：通过

Artifact：

- 名称：`Voica-0.1.0-stage1-debug`
- ID：`10991173612`
- digest：`sha256:5d4e9acb616dd836c3573fb66e7a97f8ce40e5d4728d80e034c752e41edd1519`

## 5. 真机验收

用户确认以下项目全部通过：

- APK 安装
- App 启动
- 简体中文默认界面
- 录音 / 设置 Tab
- CRC-16/XMODEM 诊断
- FrameParser 诊断
- 2-2 导入帧 36 bytes
- 无启动闪退

因此 Stage 1 满足退出条件。

## 6. 固定架构事实

后续 Stage 不得破坏以下已冻结原则：

1. `:core:protocol` 不依赖 Android BLE API。
2. UI 不直接解析二进制协议帧。
3. AE22 与 AE23 必须保持独立 FrameParser 状态。
4. TYPE=2/CMD=2 按完整 36B 请求处理。
5. UTF-8 固定长度文件名编码不得截断 code point。
6. Voica 不复制、迁移或 cherry-pick `voice-card-android` 的任何实现。

## 7. 下一阶段

Stage 2：**BLE 连接 + 设备基础信息**

Stage 2 开始前必须重新读取：

- `AGENTS.md`
- `docs/ROADMAP.md`
- `docs/ARCHITECTURE.md`
- 本文件
- `docs/STAGE_1_HANDOFF.md`

然后检查 GitHub `main` 当前真实状态，再执行 Stage 2 的需求确认与开发规划。
