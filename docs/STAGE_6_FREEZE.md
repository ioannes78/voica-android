# Voica Stage 6 Freeze

状态：**FROZEN / 真机验收通过**

日期：2026-10-01

## 1. Freeze 结论

Stage 6 — 本地录音库 + Opus/WAV 音频链路已完成：

- 开发
- Unit Test
- Debug Build
- Native libopus build
- GitHub Actions CI
- 多轮 QS668/CB08 真机测试
- Alpha 2 问题修正
- Alpha 3 真机验收

用户最终明确回复：

**“真机功能测试通过”**

## 2. 最终候选

最终真机候选 HEAD：

`13bd6b6cd166be05a85eae1f77db84c7f8dbd26f`

版本：

- versionCode：17
- versionName：`0.6.0-stage6-alpha3`

Candidate CI：

`36744328433` — **success**

APK SHA-256：

`39df6ab657929250a032923e64643d450c37c399ff749fd79ac2c38b70aad38a`

## 3. 冻结模块

Stage 6 当前物理模块：

```
:app
├─ :core:ble
│  └─ :core:protocol
├─ :core:database
├─ :core:audio
└─ :engine:opus
   └─ :core:audio
```

## 4. 本地录音库 Freeze

正式本地库由 Room 管理。

数据库：

`voica-recordings.db`

schema version：

`1`

Stage 5 `.properties` 只作为 legacy migration 输入；Stage 6 新下载不再以 properties 作为运行时事实来源。

一条设备录音对应一条逻辑 Recording，可拥有：

- DEVICE_OPUS
- DEVICE_WAV
- CANONICAL_WAV

标准设备录音 displayName 默认不带 `.opus/.wav`。

用户重命名只修改逻辑 displayName，不修改：

- remote identity
- 原设备 filename
- 物理文件路径
- SHA-256

## 5. 音频资产 Freeze

DEVICE_OPUS 与 DEVICE_WAV 必须独立完成：

- size / file existence
- SHA-256 integrity
- format validation

canonical source 选择不得决定另一个资产是否被验证。

优先 canonical source：

1. DEVICE_OPUS
2. DEVICE_WAV fallback

原始设备资产为权威源，派生 canonical WAV 可重建。

## 6. WAV Freeze

设备列表中的 Stage 4 `sizeBytes` 继续代表 OPUS 大小。

WAV 大小：

```
CMD=12 same-name .wav [0,44)
→ RIFF/WAVE header
→ little-endian ChunkSize
→ totalSize = ChunkSize + 8
```

若列表 probe 不可用，完整 WAV 下载首包可再次解析 RIFF 头取得真实总长度。

OPUS / WAV 下载均使用真实 received / expected 字节计算百分比。

## 7. Opus Freeze

不得恢复 Stage 5 的：

`sizeBytes % 40 == 0 → RAW_OPUS`

Stage 6 必须先建立 packet boundary，再通过 libopus packet semantics / decode 验证。

Ogg 不是 Stage 6 强制中间格式。

官方 libopus：

- version：1.6.1
- SHA-256：
  `6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1`

## 8. Canonical PCM/WAV Freeze

固定输出：

- RIFF/WAVE
- PCM format 1
- 16000 Hz
- mono
- signed PCM16 little-endian
- blockAlign 2
- byteRate 32000

转换必须：

- 流式
- bounded memory
- 支持取消
- `.part` / fsync / atomic commit
- 失败时保留原始设备音频
- 幂等避免重复 canonical 资产

## 9. Stage 7 / Stage 8 接口 Freeze

Stage 7 通过 `AudioSourceResolver` 取得可播放音频。

Stage 8 通过 `PcmSourceResolver` / `PcmSource` 取得：

- 16 kHz mono PCM16_LE
- 流式 chunk
- absolute sample index

后续 Feature 不得直接读取 legacy properties、BLE 文件名或 Room 内部表结构来绕过接口。

## 10. 生命周期 Freeze

Stage 6：

- App-private / noBackupFilesDir 为权威本地音频库
- 普通文件管理器不可直接访问属于预期行为
- App 进入后台时当前 BLE 文件传输主动取消
- Stage 6 不实现 Foreground Service

可靠后台/锁屏持续下载冻结为 Stage 13 范围。

## 11. Stage 1–5 回归

不得改变既有已冻结事实：

- BLE / MTU / GATT 串行
- 录音控制
- 文件列表
- 2/2→2/3→2/4×N→2/5
- CMD=12 `[start,end)`
- CMD=8 fixed-24 filename delete
- 本地/设备删除独立
- OutcomeUnknown destructive safety

## 12. 下一阶段

下一阶段：

**Stage 7 — 播放器 + 精确时间轴**

Stage 7 必须从最新 `main` 重新 baseline validation，不得直接从聊天记忆继续。

## 13. Seal

Freeze/Handoff 内容提交 SHA 与最终 seal CI 将由 seal commit 补充。
