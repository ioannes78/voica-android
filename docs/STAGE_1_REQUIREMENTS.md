# Voica Stage 1 修订需求

## 目标

Stage 1 建立一个全新、可安装、可测试的 Android 基础工程，并独立实现 QS668/CB08 纯 Kotlin 协议核心。

本阶段不实现真实 BLE、文件下载、音频、Room、ASR 或 AI。

## 技术基线

- Kotlin 2.4.20
- Android Gradle Plugin 9.4.0
- Gradle 9.6
- JDK 17
- compileSdk 37
- targetSdk 37
- minSdk 26
- Jetpack Compose BOM 2026.09.00
- Material 3
- Application ID：`io.github.ioannes78.voica`
- versionName：`0.1.0-stage1`
- versionCode：`1`
- 默认语言：简体中文

## 工程

Stage 1 只建立：
- `:app`
- `:core:protocol`

协议模块不得依赖 Android BLE API。

## 协议范围

- CRC-16/XMODEM
- Sequence 0..255 循环
- `5A | SEQ | CRC-LE | LEN-LE | DATA`
- Frame Builder
- 流式 FrameParser
- AE22 / AE23 独立 parser 状态
- Control / Realtime / File / Key 常量
- UTF-8 24B 文件名编码
- 2-2 文件导入请求（完整 36B）
- 2-12 Segment 请求
- 单文件删除请求（4B zero offset + 24B filename）
- 文件列表 BE 解码
- 容量 LE/BE 合理性判断
- 下载候选文件名

## UI

Stage 1 只提供中文最小壳：
- Voica / AI 录音卡
- 版本
- 设备“尚未连接”
- 协议诊断
- 录音 / 设置两个 Tab

## 测试

必须覆盖：
- CRC 标准向量
- Sequence wrap
- 36B 2-2 Golden Frame
- 2-12 frame
- Delete-one frame
- 中文 UTF-8 不截断 code point
- Frame split / coalesced
- 前导噪声
- CRC Error
- Invalid length
- AE22/AE23 独立 buffer
- File List BE
- Capacity LE/BE
- 短 body 防越界

## 本阶段明确不实现

- BLE Scan / BluetoothGatt
- 真实设备连接
- 文件下载/真机删除
- Opus/WAV
- Room
- 播放
- VAD / ASR / 标点 / Speaker
- AI
- 实时转写

## 验收

- `:core:protocol:test` 通过
- `:app:assembleDebug` 通过
- APK 可安装启动
- 中文页面正常
- 协议诊断显示 CRC / parser / 36B 导入帧结果
