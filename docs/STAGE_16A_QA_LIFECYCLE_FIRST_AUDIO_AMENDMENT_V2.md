# Stage 16A QA 生命周期与首段转写收口修订规划 V2

状态：**APPROVED — 2026-10-13 用户确认；进入实现**

开发分支：`stage16a-local-true-streaming-asr`

PR：#28

## 1. 修订目标

本修订在 Stage 16A 既定范围内收口四个真机 QA 暴露的问题：

1. realtime START 后 ASR 受 VAD activation gate 影响，开头 PCM 可能永久漏识别；
2. TYPE3 hardware SAVE 后后端结束但产品 UI 没有进入正式录音详情；
3. App disconnect/reconnect 后设备录音继续，但 realtime ASR 没有基于同一物理录音自动恢复；
4. realtime local WAV 已生成但没有进入正式 Recording Library 生命周期。

## 2. 硬边界

- 不修改 Stage 15 BLE parser / Opus decoder / canonical sample truth；
- 不新增第二个 canonical destructive consumer；
- 不补静音、不伪造跨断线连续媒体；
- 不修改 production model channel；
- 不替换 Small Bilingual / Large Chinese CTC 正式模型；
- Room v13 已能表达 realtime run / segment / recordingId，本修订默认不升级 Room；
- PR 保持 Draft，全部 QA Gate 通过且用户明确“测试通过”前不得 Freeze/Handoff/main merge。

## 3. R0 — First Audio / First Token

ASR 不再等待 Silero VAD `speechDetected` 才开始接收音频。

新的主链为：

`canonical PCM → ASR continuously accepts from first chunk`

同时：

`canonical PCM → Silero VAD → speech/boundary/finalization policy`

VAD 只决定 utterance boundary / FINAL 时机，不决定 recognizer 是否听到录音开头。

必须记录：

- firstPcmSampleIndex
- firstAsrAcceptedSampleIndex
- firstVadSpeechSampleIndex
- firstNonEmptyPartialSampleIndex
- first PCM → ASR accept latency
- first PCM → VAD speech latency
- first PCM → first non-empty PARTIAL latency
- skippedSamplesBeforeFirstAsrAccept

S0 硬门禁：

`skippedSamplesBeforeFirstAsrAccept = 0`

首个 token 可以存在模型固有 latency，但最终文本不得因为启动 gate 丢失句首。

## 4. R1 — Physical Recording Anchor

TYPE1 realtime START 成功后，产品层必须主动收敛 TYPE3 recording truth，获得：

- device identity
- device filename / physical recording identity
- transport generation
- realtime runId

形成 authority anchor。不得依赖一次偶然的 RecordingDeviceState emission。

## 5. R2 — Reconnect Recovery

transport close 前冻结 authority anchor；重连 Ready 后主动刷新 recording state。

只有同时满足：

- same device
- same recording identity
- recording still active
- new transport generation
- realtime intent still active
- user did not terminate realtime

才允许调用 local-only confirmed-existing-recording attach。

恢复不发送第二个 recorder START/RESUME；恢复后形成新 canonical session / local media segment / recognizer continuity。

## 6. R3 / R4 — Recording Library reconciliation

STOP / hardware SAVE 完成后：

1. realtime local audio finalize；
2. refresh device file list；
3. 用 START 时保存的 physical filename 找到正式 remote file identity；
4. 创建或复用主 Recording；
5. 回填 realtime run `recordingId`；
6. 单一、完整、无断联 local WAV 注册为该 Recording 的 `CANONICAL_WAV`；
7. 多 reconnect segment 不拼接、不补静音，继续以 realtime media segment 形式保留真实边界；
8. 后续下载同一 remote OPUS 必须通过相同 remote identity 复用同一 Recording，不得产生重复录音记录。

## 7. R5 — Terminal UI

App STOP 与 hardware SAVE 使用同一产品终止流程：

`ACTIVE → FINALIZING → FINAL/local WAV → Recording reconciliation → COMPLETED → Recording detail`

如果 library reconciliation 失败，不得假装成功或删除已生成 realtime 数据；诊断必须保留失败原因。

## 8. R6 — Diagnostics

完整 QA 诊断需能够观察：

- First Audio startup metrics；
- authority anchor；
- previous/current transport generation；
- recording sync requests/failures；
- pending reconnect；
- recovery attempts/attached/failures/decision；
- resolved remote identity / recordingId；
- local segment count；
- complete local WAV registration；
- library reconciliation result。

## 9. R7 — 自动化测试

至少覆盖：

- VAD 延迟激活时第一 canonical sample 仍进入 ASR；
- `skippedSamplesBeforeFirstAsrAccept == 0`；
- VAD FINAL 后下一 chunk 新 recognizer continuity 无 gap；
- explicit physical anchor 在 repository state stale 时仍能支撑 reconnect；
- same device/same recording/new generation 才恢复；
- mismatch / recording ended / same generation 禁止恢复；
- terminal run 回填 recordingId；
- 完整单 segment local WAV 注册到 Recording Library；
- future same remote identity download 不重复建立 Recording。

## 10. 真机 QA 顺序

实现 + CI 后只按一项一次测试：

1. S0 First Audio；
2. S4 hardware SAVE 产品闭环重测；
3. S6 App disconnect/reconnect 重测；
4. S7 Bluetooth OFF/ON；
5. 后续原 Stage 16A QA Gate。

当前 QA 状态：

- S1 PASS
- S2 PASS
- S3 PASS
- S4 REOPEN-FAIL
- S5 PASS
- S6 FAIL
- S7 PAUSED
- S0 WAITING FOR AMENDMENT CANDIDATE
