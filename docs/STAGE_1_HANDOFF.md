# Voica Stage 1 Handoff

## 当前结论

Stage 1 已完成自动测试、Debug 构建和用户真机验收，可以作为 Stage 2 的唯一上游基线。

## 仓库

- Repository：`ioannes78/voica-android`
- Stage 1 开发分支：`stage1-development`
- PR：`#1`
- 已验证实现 SHA：`cc0d4aa19fe02d8bddcf6fa4d4a2d5d08403110e`
- 最终成功 CI Run：`36470063093`

合并后应以 `main` 的实际 HEAD 为下一阶段起点，不得只依赖本文件记录的开发分支 SHA。

## 已有模块

### `:app`

包含：

- Compose App 壳
- Material 3
- 简体中文资源
- 录音 / 设置两个最小 Tab
- 协议诊断 UI

Stage 1 UI 只是诊断壳，不代表最终产品 UI。

### `:core:protocol`

包含：

- `Crc16Xmodem`
- `SequenceGenerator`
- `ProtocolConstants`
- `ProtocolFrame`
- `ProtocolCodec`
- `FrameParser`
- `NotificationParsers`
- `DeviceDecoders`

这是 Stage 2 BLE 层应调用的协议核心。Stage 2 不应在 BLE 模块里复制一套协议打包/解析实现。

## Stage 2 关键边界

Stage 2 目标是 BLE Transport / Session，而不是继续扩展文件下载或音频。

建议新增逻辑边界：

- `core:ble`
- BLE scanner
- BluetoothGatt session
- serialized GATT queue
- service / characteristic discovery
- AE22 / AE23 notification subscription
- MTU negotiation
- connection state
- device basic info repository

Stage 2 应通过 `:core:protocol` 构造请求和解析完整协议帧。

## Stage 2 真机重点

至少验证：

- 扫描发现 QS668/CB08
- 首次连接
- 主动断开
- 异常断开
- 重连
- 连续多次连接/断开
- AE20/AE21/AE22/AE23 正确发现
- AE22/AE23 通知独立进入各自 parser
- MTU 协商
- 时间同步
- 电量/充电状态
- 容量
- 固件版本
- 中文诊断日志

## 不要提前做

Stage 2 不应提前加入：

- 设备文件下载
- Opus 解码
- Room
- ASR
- VAD
- 标点
- Speaker
- AI

这些按 ROADMAP 后续 Stage 执行。

## CI 原则

继续保持低成本：

- PR 快速 unit test + assembleDebug
- 不默认运行 Emulator/Instrumentation
- concurrency cancel-in-progress
- 真机功能由 APK 验收，不用高成本 CI 模拟替代

## 下一步固定流程

`读取 Stage 1 Freeze/Handoff → 检查 main 真实状态 → 输出 Stage 2 修订需求 → 用户确认 → 输出 Stage 2 修订开发规划 → 用户确认 → 创建 stage2-development → 开发`
