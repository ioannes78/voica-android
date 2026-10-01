# Voica Android 系统架构

## 1. 架构原则

Voica 是全新 Android 工程，不从 `voice-card-android` 继承任何代码、模块实现或构建脚本。

系统按协议、传输、数据、音频、AI/ML、Feature UI 分层，避免形成同时处理 BLE、文件、音频、模型和界面的巨型状态对象。

## 2. 当前已落地模块

Stage 7 当前物理模块：

```
:app
├─ :core:ble
│  └─ :core:protocol
├─ :core:database
├─ :core:audio
└─ :engine:opus
│  └─ :core:audio
└─ :engine:playback
   └─ :core:audio
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


## Stage 3 — 录音控制与设备实时状态架构

Stage 3 未新增 Gradle module，继续保持：

```
:app
  ↓
:core:ble
  ↓
:core:protocol
```

### Recording 数据流

```
Compose RecordingCard
  ↓
DeviceViewModel
  ↓
DeviceRepository
  ↓
AndroidDeviceSession
  ↓
DeviceCommandClient / Serialized GATT Queue
  ↓
AE21 WRITE_NO_RESPONSE
  ↓
QS668 / CB08
  ↓
AE22 / AE23 独立 FrameParser
  ↓
RoutedNotification
  ├─ pending matcher
  └─ RecordingFrameRouter
       ↓
RecordingStateReducer
       ↓
StateFlow<RecordingDeviceState>
       ↓
Compose
```

### 当前固件卡录音控制 Contract

当前 QS668/CB08 真机与官方 Android 3.0.9-u 交叉验证：

- Device → App 物理开始：TYPE=3 CMD=1
- App → Device 开始/确认：TYPE=3 CMD=2 body=`01`
- Device → App 物理停止：TYPE=3 CMD=3
- App → Device 停止/确认：TYPE=3 CMD=4 body=`01`
- Device → App 物理暂停：TYPE=3 CMD=5
- App → Device 暂停/确认：TYPE=3 CMD=6 body=`01`
- Device → App 物理继续：TYPE=3 CMD=7
- App → Device 继续/确认：TYPE=3 CMD=8 body=`01`

App 主动控制为 fire-and-forget write，随后通过只读查询收敛状态，不等待一个虚构的 action response。

### Recording 状态证据

`GET_STATE` 仍是核心设备查询，但当前固件存在重要歧义：

- Idle：GET_STATE=2，可作为强设备真值
- Recording：GET_STATE=1
- Paused：设备实际已经暂停时，GET_STATE 仍可能持续返回 1

因此 Paused 使用语义证据：

- App Pause 写入成功，锁存 Paused
- 物理 Pause CMD=5，锁存 Paused
- App/物理 Resume 解除 Pause 锁存并进入 Recording
- Start / Save / Idle 解除 Pause 锁存
- Pause 锁存期间 GET_STATE=1 不允许覆盖 Paused

解析器仍保留 raw=3 → Paused 的兼容能力，但当前真机验收不依赖 raw=3。

### Polling 与同步互斥

只有同时满足以下条件才执行约 1 秒 GET_TIME：

- Device Ready
- App foreground
- RecordingStatus == Recording

Full recording sync 开始前先停止 Poller。收到任何物理录音事件也先停止 Poller，再 acknowledgement + reconciliation。

因此不允许周期 GET_TIME 与 Stop/Pause 后 full sync 并发竞争。

### 辅助读取

- GET_STATE：关键读取，失败可导致 Recording sync Failed
- GET_TIME：Recording/Paused 时辅助读取；Idle 跳过
- GET_FILENAME：Recording/Paused 时辅助读取；Idle 跳过并保留最后文件名
- GET_GAIN：辅助读取
- 辅助读取失败仅进入 BLE Diagnostics，不作为“最近录音操作”错误

### 自动连接

Ready 后保存最后成功设备地址。

后续 App 进入前台时，在蓝牙和权限可用、当前没有 active GATT session 的前提下自动连接该设备。

- 用户主动断开：本 App 进程内抑制自动连接
- App 重新启动：恢复自动连接资格
- Remote disconnect：继续使用 Stage 2 的 1s → 2s → 4s、最多 3 次策略

Stage 3 仍不使用 Foreground Service。


## Stage 4 — 设备文件列表与文件名解析架构

Stage 4 未新增 Gradle module，继续保持：

```
:app
  ↓
:core:ble
  ↓
:core:protocol
```

### Protocol 层

`:core:protocol` 新增并冻结以下职责：

- `FileListDecoder`：严格解析 TYPE=2/CMD=1 body
- `RawDeviceFileEntry` / `FileListChunk`
- 动态 filename field 长度推导
- 严格 UTF-8 / NUL padding 校验
- `DeviceFilenameResolver`
- `RecordingFilenameParser`

当前列表 entry 基础布局：

```text
COUNT_BE32
repeat COUNT:
    durationSeconds_BE32
    sizeBytes_BE32
    filename field
```

当前 QS668/CB08 真机 filename field = 20B，但解析器保留官方 App 已证明的动态长度兼容能力。

### BLE 层

Stage 4 文件列表不是单 request/response，而是独立 session：

```
DeviceRepository.refreshDeviceFiles()
  ↓
FileListSessionCoordinator.start()
  ↓
AndroidDeviceSession.requestFileList()
  ↓
TYPE=2/CMD=0
  ↓
AE22 / AE23 独立 FrameParser
  ↓
FileListFrameRouter
  ├─ 2/1 Data → strict decode → accumulator
  └─ 2/18 Done → atomic finalize
  ↓
RemoteDeviceFileMapper
  ↓
StateFlow<DeviceFileListState>
```

正常成功必须收到 CMD=18。Timeout 是失败保护，不是正常列表结束条件。

同一时刻只允许一个 file-list session；disconnect、transport session generation 变化或 malformed payload 会取消本轮结果，partial entries 不得发布为 Fresh。

### 文件身份与文件名

`RemoteDeviceFile` 同时保留：

- raw filename
- resolved filename
- filename resolution type
- filename field length
- rawTimeValue
- durationSeconds
- sizeBytes
- recordedAt
- identity / identityProvisional

当前标准 20B raw filename：

`noteYYYYMMDD-HHMMSS.`

仅在严格匹配该格式时恢复为：

`noteYYYYMMDD-HHMMSS.opus`

不自动生成 WAV variant，也不把未知 trailing-dot 名称猜成 Opus。

### 时长语义

0.4.1 真机专项：

- 实际 11 秒 → rawTimeValue=11
- 实际 24 秒 → rawTimeValue=24

因此 Stage 4 冻结：

`durationSeconds = rawTimeValue`

协议层仍保留 rawTimeValue，以保留原始字段证据。

### 与 Stage 3 的隔离

只有 Ready + Recording Idle/FRESH + command IDLE 时允许文件列表刷新。

Recording / Paused / command transition 时禁止刷新，Stage 4 不通过停止/重启 Poller 的方式抢占 BLE 队列，因此 Stage 3 Polling/Reconciliation 规则保持不变。

### Stage 5 边界

Stage 4 不实现 2/2、2/12 文件传输。

官方 Android App 已确认：

- 2/12 是 ranged file transfer
- 文件传输后续使用 2/3、2/4、2/5

Stage 5 必须重新验证 filename 参数长度。当前旧 builder 的 fixed-24 实现不能因为 Stage 1 存在就直接视为冻结协议。


## Stage 5 — 文件下载、删除与原始音频落盘架构

Stage 5 继续保持：

```
:app
  ↓
:core:ble
  ↓
:core:protocol
```

### 设备文件操作串行化

设备文件操作由 `DeviceFileOperationCoordinator` 统一互斥：

```
Refresh
Download
RangeProbe
DeleteRemote
```

同一时间最多一个设备文件操作。Recording 状态优先，物理开始录音可以取消当前文件传输。

### 文件下载

```
RemoteDeviceFile
  ↓
CMD=2 request
  ↓
CMD=3 start / actual filename
  ↓
CMD=4 data × N
  ↓
专用 ReliableFileTransferConsumer
  ↓
StreamingDownloadWriter
  ↓
.part
  ↓
size / SHA-256 / fsync
  ↓
atomic rename
  ↓
LocalRecordingArtifact
  ↓
CMD=5 terminal status
```

CMD=4 DATA 不通过普通 observer SharedFlow 作为唯一数据通道，任何无法可靠交付的数据都会使传输失败。

Stage 5 真机确认当前设备下载得到的是 raw Opus 字节流而不是 RIFF/WAV；因此原始 `.opus` 直接保存，转换留给 Stage 6。

### Range transfer

CMD=12 使用 start/end 范围。当前 QS668/CB08 真机通过与完整本地下载逐字节比对确认：

```
[start, end)
```

end 为 exclusive。

### 设备删除

单文件删除使用：

```
TYPE=2 / CMD=8
body:
  00 00 00 00
  filename fixed 24B
```

标准 `noteYYYYMMDD-HHMMSS.opus` 正好为 24 ASCII bytes。

raw file-list entry 作为 CMD=8 参数已被当前真机否定。

成功删除设备逻辑录音后，设备物理存储同名 `.opus` 与 `.wav` 会一起消失。

设备删除与本地删除为两个独立生命周期：

- DeleteRemote 不删除本地 artifact
- DeleteLocal 不发送 BLE 删除命令
- 不提供 DeleteBoth
- 不提供 Delete All

### 本地存储

Stage 5 使用 `noBackupFilesDir/recordings`：

- temp `.part`
- completed raw audio
- 轻量 metadata properties
- SHA-256
- stable local id
- stale temp cleanup

Stage 5 不引入 Room；Stage 6 才进入正式本地录音库与音频转换链。


## Stage 6 — 本地录音库与音频链路架构

### Room 本地库

```
BLE download artifact
      ↓
DownloadedAssetRegistry
      ↓
RecordingLibraryRepository
      ↓
Room
├─ RecordingEntity
├─ AudioAssetEntity
├─ AudioDerivationEntity
├─ LibraryMetaEntity
└─ MigrationDiagnosticEntity
```

Stage 5 properties 仅作为一次性 legacy import 输入；Stage 6 新下载不再把 properties 当运行时事实来源。

### 逻辑 Recording 与 AudioAsset

一条设备逻辑录音：

```
Recording
├─ DEVICE_OPUS
├─ DEVICE_WAV
└─ CANONICAL_WAV
```

displayName 与实际物理扩展名解耦。标准 `noteYYYYMMDD-HHMMSS.opus/.wav` 默认显示为 `noteYYYYMMDD-HHMMSS`。

### 独立资产验证

```
DEVICE_OPUS
→ size/SHA
→ container
→ framing
→ libopus packet inspect
→ VALID / UNSUPPORTED / INVALID

DEVICE_WAV
→ size/SHA
→ RIFF/WAVE
→ PCM/sampleRate/channels/bits
→ VALID / UNSUPPORTED / INVALID
```

资产验证与 canonical source selection 分离。

### canonical 生成

```
优先 DEVICE_OPUS
  ↓
RawOpusValidator
  ↓
libopus 1.6.1
  ↓
PCM
  ↓
StreamingPcm16Normalizer
  ↓
CanonicalWavWriter
  ↓
16k mono PCM16 WAV
```

没有可用 OPUS 时，可用 DEVICE_WAV：

- 已为 canonical：直接登记
- PCM16 非 canonical：流式 downmix/resample 后生成 canonical

### 文件大小与进度

Stage 4 list `sizeBytes` 继续是 OPUS 大小。

WAV：

```
CMD=12 same-name .wav [0,44)
→ RIFF ChunkSize + 8
→ exact wavSizeBytes
```

若列表 probe 未取得大小，完整下载首包继续解析 RIFF 头并动态填充 expectedBytes。

OPUS/WAV 均使用真实 receivedBytes / expectedBytes 驱动确定型进度。

### 音频消费边界

Stage 7：

`AudioSourceResolver.resolvePlaybackSource(recordingId)`

Stage 8：

`PcmSourceResolver.resolvePcmSource(recordingId)`

PcmSource 固定：

- 16000 Hz
- mono
- PCM16_LE
- streaming
- absolute sample index

### 生命周期

- 原始设备音频先可靠落盘，再处理
- canonical 转换串行、可取消
- `.part` + fsync + atomic commit
- interrupted derivation 启动时 reconciliation
- 本地库位于 App private `noBackupFilesDir/recordings`
- Stage 6 后台时取消 BLE 文件传输
- Stage 13 才引入 Foreground Service 可靠后台下载


## Stage 7 — 播放器与精确时间轴架构

### Playback 主链

```
Recording ID
      ↓
AudioSourceResolver
      ↓
Verified CANONICAL_WAV
      ↓
PlaybackAudioSourceDescriptor
      ├─ pcmDataOffsetBytes
      ├─ pcmDataSizeBytes
      ├─ bytesPerFrame
      └─ totalSampleCount
      ↓
SeekableAudioHandle.readAt()
      ↓
absolute sampleIndex → byteOffset
      ↓
bounded PCM streaming
      ↓
AudioTrack MODE_STREAM
      ↓
AudioTimestamp / playbackHead fallback
      ↓
presentedSampleIndex
      ↓
PlaybackSnapshot
      ↓
PlaybackViewModel / Compose PlayerCard
```

### 唯一媒体时间

Stage 7 冻结：

`absolute canonical PCM sample index`

为 Voica 唯一媒体时间真值。

固定 canonical profile：

- 16000 Hz
- mono
- PCM16 little-endian
- 2 bytes/frame

`timeUs`、UI Slider 比例和 wall clock 都是派生值。

### Position 与 Seek

播放器区分：

- source cursor
- submitted sample
- presented sample

公开 position 使用 presented sample。

Seek 基于：

`pcmDataOffsetBytes + sampleIndex * bytesPerFrame`

不得假定 WAV header 为 44 bytes。

Seek/load/restart/rebase 通过 generation/discontinuity 防止旧 callback 污染新时间轴。

### 生命周期与音频焦点

- LOSS → pause / no auto-resume
- transient loss → policy-controlled resume
- CAN_DUCK → Stage 7 选择 pause
- noisy output → pause
- app background → pause
- Stage 7 不提供 MediaSession/Foreground Service/background playback

### 设备录音互锁

AppContainer 同时观察 DeviceRepository recording state 与 PlaybackController state。

当设备进入 Recording 或收到新鲜 START/RESUME hardware edge：

`Playing → Paused`

录音结束后不自动恢复播放。

BLE 依赖不进入 `:engine:playback`。

### Stage 8 / 10 边界

Stage 8：

`PcmSourceResolver → PcmSource → absolute sample index`

Stage 10：

`PlaybackSnapshot.positionSampleIndex + seekToSample + discontinuityGeneration`

两条链共享 16 kHz canonical absolute sample coordinate。
