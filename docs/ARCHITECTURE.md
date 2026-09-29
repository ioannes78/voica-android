# Voica Android 系统架构

## 1. 架构原则

Voica 是全新 Android 工程，不从 `voice-card-android` 继承任何代码、模块实现或构建脚本。

系统按协议、传输、数据、音频、AI/ML、Feature UI 分层，避免形成同时处理 BLE、文件、音频、模型和界面的巨型状态对象。

## 2. Stage 2 已落地模块

当前物理模块：

```
:app
  ↓
:core:ble
  ↓
:core:protocol
```

### `:core:protocol`

纯 Kotlin/JVM：

- CRC-16/XMODEM
- ProtocolCodec
- ProtocolFrame
- SequenceGenerator
- AE22/AE23 FrameParser
- 时间同步编码
- Battery/Capacity/Firmware/Auth 解码
- 既有 File/Key TYPE/CMD 基础协议

禁止依赖 Android BLE API。

### `:core:ble`

Android BLE Transport / Device Session：

- BlePermissionPolicy
- AndroidBleScanner
- BleScanAccumulator
- AndroidGattBackend
- GattOperationQueue
- AndroidDeviceSession
- NotificationRouter
- DeviceCommandClient
- DefaultDeviceRepository
- ReconnectPolicy
- BLE Diagnostics

### `:app`

- VoicaApplication / AppContainer
- MainActivity
- DeviceViewModel
- Compose Device Screen
- Runtime permission launcher
- 中文设备信息与 BLE Diagnostics

Stage 2 暂未拆独立 `:feature:device`，但 UI/Repository/Transport 边界已保留，后续按复杂度再抽离。

## 3. Stage 2 BLE 数据流

发送：

```
Compose
  ↓
DeviceViewModel
  ↓
DeviceRepository
  ↓
AndroidDeviceSession
  ↓
DeviceCommandClient
  ↓
:core:protocol ProtocolCodec
  ↓
完整 ByteArray
  ↓
Serialized GATT Queue
  ↓
AE21 WRITE_NO_RESPONSE
```

接收：

```
BluetoothGattCallback
  ↓
UUID Router
  ├─ AE22 → 独立 FrameParser
  └─ AE23 → 独立 FrameParser
  ↓
ProtocolFrame
  ↓
pending matcher / device-state observer / diagnostics
```

AE22 与 AE23 **解析状态永远独立**，但两路解析完成的 `ProtocolFrame` 都可以交给当前单路 pending request matcher。

## 4. Connection State

Stage 2 显式区分：

- Unavailable
- PermissionRequired
- BluetoothOff
- Idle
- Scanning
- Connecting
- LinkConnected
- DiscoveringServices
- Subscribing AE22
- Subscribing AE23
- NegotiatingMtu
- Ready
- Disconnecting
- Disconnected
- ReconnectWaiting
- Error

`BluetoothGatt STATE_CONNECTED` 不等于 Device Ready。

Ready 至少要求：

`Link → AE20/21/22/23 验证 → AE22 Notify → AE23 Notify → MTU 完成 → MTU >=39`

## 5. GATT 串行化

所有异步 GATT operation 通过严格串行队列：

- DiscoverServices
- CCCD notification enable
- RequestMtu
- WriteCharacteristic

同一时间最多一个 active operation。

Queue 必须：

- callback 类型匹配
- descriptor/characteristic UUID 匹配
- timeout
- late callback 防护
- close/cancel
- disconnect 不继续 drain

每次连接有独立 session generation；旧 GATT callback 不得污染新 session。

## 6. MTU Contract

Android 主动：

`requestMtu(517)`

Stage 2 真机确认：

`Actual MTU = 517`

能力分级：

- MTU <39：不进入 Ready
- MTU 39..170：基础控制 / 36B atomic capability
- MTU >=171：当前已知 168B CB08 数据帧能力
- 517：当前真机最佳状态

后续 TYPE=2/CMD=2 36B Frame 必须一次完整 AE21 Write，禁止应用层拆成 20B+16B。

## 7. Request / Response

Stage 2 基础设备请求单路串行：

1. 生成 sequence
2. 注册 pending
3. 执行 GATT write
4. AE22/AE23 parser 输出 ProtocolFrame
5. 按 expected TYPE/CMD 完成 pending

真机确认：

- response sequence **不保证**与 request sequence 相同
- 因此 Stage 2 强匹配 TYPE/CMD
- sequence 保留为 diagnostics，不作为强制匹配条件

真机还确认 Battery Response 0/4 可以从 **AE23** 返回。

## 8. 生命周期与重连

- Compose recomposition 不创建 GATT
- Repository 由 Application-scope AppContainer 持有
- App 后台停止 active scan
- 已建立连接不因普通页面切换断开
- Remote disconnect：1s → 2s → 4s，最多 3 次
- User disconnect：不自动重连
- Bluetooth Off：停止 scan/reconnect、释放 GATT
- Bluetooth On：恢复为可扫描状态
- stale session callback 被忽略

Stage 2 不使用 Foreground Service。

## 9. 后续模块规划

后续按 Stage 引入：

- core-model（必要时）
- core-audio
- core-database
- engine-opus
- engine-vad
- engine-asr
- engine-punctuation
- engine-speaker
- engine-ai
- feature-recordings
- feature-transcript
- feature-settings

不为未来功能提前创建空模块。

## 10. 设备文件与本地录音身份

设备文件与本地 Recording 必须分离：

- 设备文件存在不代表已下载
- 成功下载后映射稳定本地 Recording
- 重试不得静默制造重复本地记录
- 删除设备文件不得自动删除本地副本
- 删除本地副本不得自动删除设备文件

## 11. 音频落地原则

后续音频顺序：

1. 先可靠保存设备原始字节
2. 校验原始格式/包结构
3. 再生成播放/ML 标准音频
4. 转换幂等
5. 本地音频不依赖重新连接设备恢复

## 12. 本地转写处理链

```
16 kHz Mono PCM
      ↓
VadEngine
      ↓
SpeechSegment
      ↓
长度保护 / 合并
      ↓
AsrEngine
      ↓
Raw Transcript
      ↓
标点能力判断
      ├─ ASR 已有可靠标点 → 规范化
      └─ 无/弱标点 → PunctuationEngine
      ↓
Final Timed Transcript
```

## 13. 中文与安全

- 默认 UI 简体中文
- 普通用户文案集中在资源系统
- API Key 不写仓库、不输出普通日志
- 破坏性设备操作必须明确确认
