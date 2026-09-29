# Voica Stage 2 修订开发规划

状态：已确认（2026-09-29）

## 分支

`stage2-development`

基线：`main@1ef1e520e67ae3db6d074d2980555ebbc9247961`

## 模块

Stage 2 新增唯一主要 Gradle module：`:core:ble`。

依赖方向：

`app → core:ble → core:protocol`

`:core:protocol` 继续保持纯 Kotlin；UI 不接触 BluetoothGatt 或二进制 Frame。

## 实施阶段

1. **M2.1 Protocol Increment**：DeviceTime、Sync Time、BatteryState、Firmware/Auth decoder 和单测。
2. **M2.2 Permission + Scanner**：Android 权限、BLE 可用性、8s Scanner、去重和测试。
3. **M2.3 GATT Queue + Session**：Gatt operation abstraction、严格串行 queue、session generation、service discovery。
4. **M2.4 Notify + MTU**：AE22/AE23 CCCD 串行订阅、requestMtu(517)、39/171 能力分级、独立 parser routing。
5. **M2.5 Device Command + Device Info**：单路 request/response、pending-before-write、Battery/Capacity/Firmware/Auth/Sync Time。
6. **M2.6 Reconnect + Lifecycle**：remote reconnect 1s/2s/4s、Bluetooth Off、前后台。
7. **M2.7 UI + Diagnostics**：Application-scope repository、Device ViewModel、简体中文设备页、bounded diagnostics。
8. **M2.8 Automated Validation**：`:core:protocol:test`、`:core:ble:testDebugUnitTest`、`:app:assembleDebug`。
9. **M2.9 APK + Real Device**：主链路后再测试异常/重连矩阵。
10. **M2.10 Freeze/Handoff**：仅在用户明确“测试通过”后执行。

## 技术冻结点

- 原生 Android BLE API，不引入第三方 BLE framework。
- GATT 操作严格串行。
- Connected ≠ Ready。
- AE22/AE23 parser 永久独立。
- `requestMtu(517)`，MTU>=39 才可 Ready，MTU>=171 标记 data channel。
- 36B frame 永不应用层拆分。
- 基础 request/response 单路串行；pending 必须先于 write 注册。
- 自动重连最多 3 次。
- Stage 2 不使用 Foreground Service。
- 不实现 File/Audio/ASR 等后续 Stage 功能。

## CI

长期 workflow 改为 Android PR CI。PR 默认只执行 Unit Test + assembleDebug，并保留 concurrency/cancel-in-progress。APK 只在需要真机验收的手动 workflow_dispatch 上传。
