# Voica Stage 2 修订需求 V2

状态：已确认（2026-09-29）

## 目标

Stage 2 只实现 **BLE 连接 + 设备基础信息**，建立 Android BLE Transport、Scanner、BluetoothGatt Session、严格串行 GATT Queue、AE20/AE21/AE22/AE23 通道、Device Session/Repository 和简体中文设备页。

本阶段不实现文件下载、音频、录音控制、Room、VAD、ASR、标点、说话人、AI 或云端能力。

## 协议事实优先级

1. QS668/CB08 真机可重复验证行为。
2. `nextproto1024/ai-recorder-card-open-protocol`，Stage 2 固定参考 commit：`e741ea72207f1a2aae3df4debc5c135728e0170e`。
3. `laidely/kardo`，固定参考 commit：`bcec3c5fdbcb34810a6f235e8b5873683f2ab951`，仅用于产品行为交叉验证。
4. Android 官方 BLE API 行为。

`voice-card-android` 不作为代码、架构、测试或 Gradle 来源。仅允许把过去真机已观察事实作为交叉验证线索，Voica 必须独立实现。

## 冻结协议事实

- AE20 Service：`0000ae20-0000-1000-8000-00805f9b34fb`
- AE21 Write：`0000ae21-0000-1000-8000-00805f9b34fb`
- AE22 Notify：`0000ae22-0000-1000-8000-00805f9b34fb`
- AE23 Notify：`0000ae23-0000-1000-8000-00805f9b34fb`
- CCCD：`00002902-0000-1000-8000-00805f9b34fb`
- AE21 预期 `WRITE_NO_RESPONSE`；AE22/AE23 预期 `NOTIFY`，真机连接后必须验证 properties。
- AE22/AE23 永远使用独立 FrameParser。
- TYPE=2/CMD=2 完整请求为 36B，不允许应用层拆写。
- 时间同步：TYPE=0/CMD=0，7B 参数为 year LE16、month、day、hour、minute、second。
- 电量：0-3 请求，0-4 响应；body[0] 0..100 为百分比，110 表示充电中。
- 容量：0-1 请求，0-2 响应，公开协议声明 remaining KB / total KB 为 LE u32；保留 Stage 1 LE/BE 合理性兼容。
- 固件：0-10 / 0-11。
- Auth：0-12 / 0-13，ASCII/HEX；不作为 Ready 前置认证。

## Android 权限

API 31+ 使用 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`。API 26-30 兼容 legacy Bluetooth 权限及 `ACCESS_FINE_LOCATION`。权限拒绝、撤销、无 BLE、Bluetooth Off 均不得导致崩溃。

## Scanner

- 默认单次扫描约 8 秒，可手动停止。
- 同一 session 按 Android device address 去重并更新 RSSI/name/lastSeen。
- 不采用严格 AE20-only 过滤；广播 AE20、名称 QS668/CB08 用于候选排序，最终以 GATT service discovery 确认。
- 连接、Bluetooth Off、App 后台时停止 active scan。

## Connection / Ready

`STATE_CONNECTED` 不等于 Device Ready。Ready 至少要求：

`Link Connected → Discover AE20 → Validate AE21/22/23 → Enable AE22 → Enable AE23 → Request MTU → MTU >=39`。

GATT setup 严格串行，任何时刻最多一个异步 GATT operation。

## MTU

- Android 主动 `requestMtu(517)`。
- 最终以 `onMtuChanged` actual negotiated MTU 为准。
- `MTU <39`：不允许进入完整 Ready。
- `39..170`：36B 原子控制帧能力可用，当前已知 168B 数据通道能力不足。
- `MTU >=171`：标记当前已知完整 CB08 数据通道能力可用。
- 517 为目标/最佳协商状态，不伪造实际结果。

Diagnostics 必须记录 requested/actual MTU、36B atomic support、168B data support。

## GATT Queue / Session

DiscoverServices、notification CCCD、RequestMtu、AE21 Write 等操作全部进入严格串行队列。每次连接使用新的 Session generation；旧 session callback 不得改变新 session。Disconnect/Bluetooth Off 必须取消 active/queued operation、pending device request，并 reset parser。

## Device Command

发送链路固定：Device Command → `:core:protocol` → 完整 ByteArray → GATT Queue → AE21。

基础 request/response 单路串行。必须先注册 pending request，再执行 GATT write，防止 fast-response race。Stage 2 默认按 expected TYPE/CMD 匹配，同时记录 request/response sequence，是否把 sequence 提升为强校验由真机证据决定。

## Reconnect

用户主动断开不重连。Remote disconnect 在前台、蓝牙开启且权限有效时最多重试 3 次，建议 1s → 2s → 4s。Bluetooth Off、权限撤销、协议形状不兼容、MTU<39、手动连接新设备时停止旧重连。

## UI / Diagnostics

简体中文设备页至少展示扫描、连接阶段、电量/充电、容量、固件、MTU、AE20/21/22/23 状态和断开/刷新/同步时间。Debug 区域至少记录 session、GATT stage、MTU、properties、queue、last status/error、重连次数、AE22/AE23 独立 frame/CRC/invalid counters 和最近 TX TYPE/CMD/SEQ。

## 验收门禁

必须通过 Stage 1 回归单测、Stage 2 Unit Test、Debug Build、GitHub Actions CI，并生成 APK 做 QS668/CB08 真机验收。只有用户明确回复“测试通过”后，才能 Freeze/Handoff/Merge main。
