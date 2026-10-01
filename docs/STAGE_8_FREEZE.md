# Voica Stage 8 Freeze

状态：**FROZEN / 用户验收通过**

日期：2026-10-02

## 1. Freeze 结论

Stage 8 — 本地 ASR + VAD + 标点 + 模型管理完成并冻结。

用户最终明确确认：

**“Stage 8 真机测试通过”**

## 2. 最终实现与候选

Stage 8 最终实现 HEAD：

`189ea18dd11a45227d541b133c4795e38ffa85ea`

最终 QA：

- versionCode：23
- base versionName：`0.8.0-stage8-alpha4`
- QA Application ID：`io.github.ioannes78.voica.qa`
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- Room schema：2

候选 CI：

`36920166807` / #248 — **success**

## 3. 冻结模块

```
:app
├─ :core:ble → :core:protocol
├─ :core:database
├─ :core:audio
├─ :core:model
├─ :core:transcript
├─ :engine:opus → :core:audio
├─ :engine:playback → :core:audio
└─ :engine:sherpa → :core:model / :core:transcript
```

## 4. Runtime Freeze

- sherpa-onnx 1.13.8
- arm64-v8a
- minSdk 26
- native-load real-device gate passed

## 5. Model Freeze

production model-channel：

- repo：`ioannes78/voica-model-channel`
- main commit：`2817873b74d87ff1d4bfa54266e082ede450ba06`
- manifest digest：`ceff5a281acd1166a75e53c9112dd88e93c9e0266d6a545651159a97ebc0154b`
- validation CI #13：success

冻结模型：

- Silero VAD int8 — 2025-07-11
- Small Bilingual Zipformer zh-en — 2023-02-16
- CT-Transformer zh-en punctuation int8 — 2024-04-12
- SenseVoice zh-en-ja-ko-yue int8 — 2024-07-17

正式 App 使用 production manifest。Candidate URL 只允许 Debug/QA 在未合并候选验收时使用。

## 6. Audio / Timeline Freeze

Stage 8 不建立新的媒体时间真值。

所有 VAD/ASR/tokens/segments 继续使用：

**absolute canonical PCM sample index**

输入继续通过：

- `PcmSourceResolver`
- `PcmSource`

固定 canonical：

- 16 kHz
- mono
- PCM16_LE

## 7. Fast Pipeline Freeze

```
canonical PCM
→ Silero VAD
→ Small Bilingual streaming ASR
→ CT-Transformer punctuation
→ TranscriptionRepository.persistCompleted()
```

## 8. High Quality Pipeline Freeze

```
canonical PCM
→ Silero VAD
→ Small Bilingual first pass
→ SenseVoice second pass
→ punctuation finalization
→ TranscriptionRepository.persistCompleted()
```

SenseVoice second pass 只在 HIGH_QUALITY 任务打开。

## 9. Persistence / Version Freeze

Room schema = 2。

每次任务创建新的 Transcription UUID。

FAST 与 HIGH_QUALITY：

- 不覆盖彼此
- 各自保留 model lineage
- 各自保留 segment/token
- 可通过“转写版本”列表切换查看

Completed persistence 使用事务；UI Completed 状态在 `persistCompleted()` 成功后发布。

## 10. Model Install Freeze

下载模型必须经历：

```
HTTPS
→ .part
→ optional HTTP Range resume
→ package size/SHA
→ staging extract
→ per-file size/SHA
→ atomic promotion
→ native smoke
→ confirm good / activation
```

异常下载可保留 `.part` 续传；用户主动取消删除 `.part`。

SHA、解包、promotion、native smoke 不得阻塞 Compose 主线程。

## 11. Update Policy Freeze

- production manifest 自动检查默认开启
- 只有 Silero small override 可自动下载/验证/切换
- Small Bilingual / CT-Transformer / SenseVoice 必须用户确认下载/更新
- 已运行任务持有 exact revision lease；新激活只影响未来任务
- active lease 模型 revision 不允许删除

## 12. QA Signing Freeze

Stage 8 新增 test-only QA build：

- package：`io.github.ioannes78.voica.qa`
- fixed QA signing identity
- CI 验证 signer certificate digest
- QA key 禁止用于 production release

原因：Hosted Runner 临时 debug keystore 会导致不同 CI APK 签名漂移。

## 13. 真机证据

已通过：

- native sherpa runtime
- Small Bilingual download/integrity/native smoke
- CT-Transformer download/integrity/native smoke/Fast E2E
- SenseVoice download/integrity/native smoke/HQ E2E
- 大模型下载/解包稳定性
- cancellation/retry/process interruption
- active-model delete guard
- playback/BLE regression
- mixed zh/en/number functional check
- FAST/HIGH_QUALITY independent-version UI
- production manifest restore/four-model recognition

详见 `docs/STAGE_8_TEST.md`。

## 14. 测试债务

真实长录音压力仍明确延期 Stage 13：

- 30min
- 1h
- 2h
- RAM/CPU/temperature
- ADB meminfo / soak

Stage 8 自动化已有 virtual 30/60/120min bounded-read 证据。

## 15. 后续边界

Stage 9：说话人分离。

Stage 10：转写时间轴与播放同步。

Stage 16：本地实时转写；必须复用 Stage 8 streaming ASR contract，不另造不兼容接口。

## 16. Freeze 前证据

实现候选 CI：

`36920166807` / #248 — **success**

Freeze/Handoff 文档提交与 Seal CI 由 Stage 8 最终收口提交/CI 记录，并以合并后的 `main` 为下一阶段唯一事实来源。
