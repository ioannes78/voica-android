# Voica Stage 6 Handoff

## 当前结论

Stage 6 已完成开发、自动验证和 QS668/CB08 真机验收。

用户最终明确回复：

**“真机功能测试通过”**

本 Handoff 供完全没有阅读本次聊天的新 Agent 接管 Stage 7。

## 仓库与分支

Repository：

`ioannes78/voica-android`

Stage 6 开发分支：

`stage6-development`

Stage 6 PR：

`#6 Stage 6：本地录音库与 Opus/WAV 音频链路`

Stage 6 起始 main：

`91fd841a9400ad4812ee1ceaa5990a1484ead669`

最终真机候选 HEAD：

`13bd6b6cd166be05a85eae1f77db84c7f8dbd26f`

Candidate CI：

`36744328433` — **success**

APK SHA-256：

`39df6ab657929250a032923e64643d450c37c399ff749fd79ac2c38b70aad38a`

PR 合并后的最终 `main` SHA，Stage 7 接管时必须从 GitHub 重新读取，不能从本文件猜测。

## 版本

- versionCode：17
- versionName：`0.6.0-stage6-alpha3`

## 当前模块

```
:app
├─ :core:ble
│  └─ :core:protocol
├─ :core:database
├─ :core:audio
└─ :engine:opus
   └─ :core:audio
```

## Stage 6 核心新增

### :core:database

- Room 本地录音库
- Recording / AudioAsset / AudioDerivation
- Stage 5 properties → Room legacy import
- migration diagnostics
- 本地 rename / delete
- canonical derivation state machine
- interrupted derivation reconciliation

### :core:audio

- AudioContainerDetector
- WavPcmParser
- RawOpusValidator
- raw Opus packet framing validation
- StreamingPcm16Normalizer
- CanonicalWavWriter
- PCM WAV → canonical WAV
- AudioSource / PcmSource contracts

### :engine:opus

- official libopus 1.6.1
- arm64-v8a
- JNI packet inspect / decoder
- bounded decode buffer
- create / decode / reset / close lifecycle

### :core:ble

- DEVICE_OPUS / DEVICE_WAV 独立下载
- WAV header size probe
- WAV 首包动态 expectedBytes
- OPUS/WAV 真正百分比进度
- Stage 5 properties 退出运行时主链
- Room registration boundary

### :app

- Room 本地录音库 UI
- 逻辑录音重命名
- 独立设备资产验证
- canonical generation coordinator
- OPUS→canonical WAV
- WAV fallback / normalization
- AudioSourceResolver
- PcmSourceResolver
- 标准音频生成/重试/取消 UI

## 最重要真机事实

### OPUS / WAV 双资产

同一设备逻辑录音可分别下载：

- `.opus`
- `.wav`

两者映射到同一 Recording，不制造两个逻辑录音。

### WAV 大小

Stage 4 列表 `sizeBytes` 是 OPUS 大小。

Stage 6 使用：

```
CMD=12 same-name .wav [0,44)
```

读取 RIFF header，按：

`little-endian ChunkSize + 8`

取得 WAV 真实总大小。

Alpha 3 真机功能测试通过。

### 下载进度

OPUS / WAV 都显示：

`percent · receivedBytes / expectedBytes`

WAV probe 尚未得到大小时，完整下载首包可从 RIFF 头取得真实 expectedBytes。

### 独立验证

下载完成后：

- OPUS 自己做 raw framing / libopus packet validation
- WAV 自己做 RIFF / PCM validation

canonical source 选择与另一个资产的验证状态解耦。

### 本地名称

标准：

`noteYYYYMMDD-HHMMSS.opus`

或：

`noteYYYYMMDD-HHMMSS.wav`

逻辑 Recording 默认显示：

`noteYYYYMMDD-HHMMSS`

用户自定义名字不强制扩展名。

## libopus

固定：

- version：1.6.1
- source SHA-256：
  `6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1`
- NDK：28.2.13676358
- CMake：3.22.1
- ABI：arm64-v8a

不得在 Stage 7 为播放器另造一套 Opus 解码链。

## canonical 音频

标准：

`16 kHz / mono / PCM16_LE / RIFF-WAVE`

Stage 7 优先使用 Room 中 VALID canonical WAV。

## Stage 7 必须复用的边界

- `AudioSourceResolver`
- `PcmSourceResolver`
- `PcmSource`

Stage 7 不应：

- 直接访问 BLE 下载路径
- 直接解析 Room 表
- 读取 Stage 5 properties
- 自己再次决定 raw Opus framing
- 为播放器复制一套 Opus decoder

## 已知边界

Stage 6 不支持：

- 后台/锁屏持续 BLE 下载
- Foreground Service
- 公共文件导出
- 分享
- persistent resume
- parallel download

后台可靠下载已经固定在 Stage 13。

## Stage 7 接管顺序

1. 读取 GitHub 最新 `main`、HEAD、branch、open PR、Actions。
2. 阅读根目录 `AGENTS.md`。
3. 阅读：
   - `docs/STAGE_6_TEST.md`
   - `docs/STAGE_6_FREEZE.md`
   - `docs/STAGE_6_HANDOFF.md`
   - `docs/STAGE_6_PROTOCOL_FINDINGS.md`
   - `docs/STAGE_6_REAL_DEVICE_FINDINGS.md`
   - `docs/ARCHITECTURE.md`
   - `docs/ROADMAP.md`
4. 检查当前实际源码与 Room schema。
5. 执行 baseline validation。
6. 先输出《Voica Stage 7 修订需求》。
7. 等用户确认。
8. 再输出《Voica Stage 7 修订开发规划》。
9. 再次等待用户确认后才能编码。

## Seal

Freeze/Handoff 内容提交：

`fcf38ce5049b84568b7ac7898e6738c96aefc019`

Android PR CI：

`36753154631` — **success**

Stage 7 接管时仍必须从 GitHub 重新读取合并后的 `main` HEAD 与最终 Actions，不得把本文件中的开发分支 SHA 当作最终 main SHA。
