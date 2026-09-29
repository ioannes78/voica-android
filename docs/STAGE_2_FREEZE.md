# Voica Stage 2 Freeze

状态：**FROZEN / ACCEPTED**

用户最终验收日期：2026-09-29

## 1. 冻结范围

Stage 2 冻结以下能力：

- Android BLE 权限处理
- BLE Scanner
- 扫描去重、RSSI 更新、timeout
- BluetoothGatt Session
- Session generation / stale callback 防护
- 严格串行 GATT Operation Queue
- AE20/AE21/AE22/AE23 discovery / properties validation
- AE22 / AE23 CCCD notification
- AE22 / AE23 独立 FrameParser
- `requestMtu(517)`
- MTU 39 / 171 capability policy
- Device Ready state contract
- 时间同步
- 电量 / 充电
- 容量
- 固件
- Auth
- TYPE/CMD pending request matcher
- 有限自动重连 1s / 2s / 4s
- Bluetooth Off/On 处理
- App 前后台 / Compose 生命周期
- 简体中文设备页
- BLE Diagnostics
- Stage 2 Unit Tests
- Android PR CI

Stage 2 不冻结、不宣称实现：

- 录音开始/暂停/恢复/保存业务
- 设备文件列表业务
- 文件下载/删除
- Opus/WAV
- Room
- 播放器
- VAD / ASR / 标点
- Speaker
- AI
- Foreground BLE Service

## 2. 技术基线

- versionCode：3
- versionName：`0.2.1-stage2`
- Kotlin：2.4.20
- AGP：9.4.0
- Gradle：9.6
- JDK：17
- compileSdk：37.1
- targetSdk：37
- minSdk：26
- Application ID：`io.github.ioannes78.voica`
- 模块：`:app`、`:core:protocol`、`:core:ble`

依赖方向：

`app → core:ble → core:protocol`

## 3. 真机代码基线

真机实际安装并验收的代码候选：

`0c09609385ed1b0c07d957bd6c37f3371ceb807c`

版本：

`0.2.1-stage2`

Candidate CI：

`36503703348` — success

Candidate APK SHA-256：

`cc96ad74763b5e35fbec3498c8224c2265e12babf518644a2b6d7716bce093cc`

后续提交在 Freeze 前仅包含验收/收口文档，无 Stage 2 生产代码变化。

## 4. 真机冻结事实

QS668/CB08 实测：

- AE20：存在
- AE21：WRITE_NO_RESPONSE
- AE22：Notify enabled
- AE23：Notify enabled
- Requested MTU：517
- Actual MTU：**517**
- 36B atomic capability：通过
- 168B data channel capability：通过
- Battery Request：0/3
- Battery Response：0/4
- Battery Response 可从 **AE23** 到达
- Battery 0..100：电量百分比
- Battery 110：充电中
- Capacity：读取通过
- Firmware：读取通过
- Auth：读取通过
- Time Sync：发送通过
- response sequence 不保证回显 request sequence

因此冻结 matcher 规则：

- TYPE/CMD 为强匹配
- sequence 仅作为 diagnostics
- AE22/AE23 parser 独立
- 两路完整 ProtocolFrame 均可尝试完成当前 pending request

## 5. 连接冻结事实

- `BluetoothGatt STATE_CONNECTED` != Device Ready
- Ready 需要 AE20/21/22/23 + 两路 notification + MTU 完成 + MTU >=39
- Remote disconnect 有限重连：1s → 2s → 4s，最多 3 次
- User disconnect：不重连
- Bluetooth Off：释放 transport
- Bluetooth On：恢复可扫描/连接
- Compose/page switch 不新建重复 GATT
- stale callback 不改变新 session

## 6. 验收

第一轮主链路：**PASS**

第二轮稳定性 / 异常链路：**PASS**

连续连接/断开 >=10 次、设备关机、远距离断开、重连、Bluetooth Off/On、前后台 >=5 次均通过。

用户最终明确回复：

**“测试通过”**

## 7. 下一阶段

Stage 3：**录音控制 + 设备实时状态**

Stage 3 必须从合并后的 `main` 开始，并重新执行：

`项目接管 → 阅读 Stage 2 Freeze/Handoff → 检查 main 真实状态 → 修订 Stage 3 需求 → 用户确认 → 修订开发规划 → 用户确认 → 编码`
