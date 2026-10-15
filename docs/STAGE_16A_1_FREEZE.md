# Stage 16A.1 Final Freeze — 手机录音 / 手机实时转写

状态：**FINAL FREEZE — 用户已明确确认 Stage 16A.1 整体验收通过；S15 30 分钟长时测试经用户明确豁免，按 NOT RUN / USER WAIVER 记录**

冻结日期：2026-10-15

## 1. 冻结范围

Stage 16A.1 冻结两个独立产品入口：

- `手机录音`：纯手机麦克风正式录音，不依赖 realtime ASR model；
- `手机实时转写`：同一手机麦克风正式录音底座 + Stage 16A 本地 True Streaming ASR。

冻结底层架构：

```text
PhoneMicrophoneCapture
        ↓
canonical PCM16 / 16 kHz / mono

RECORD_ONLY
        ↓
Primary Recording Sink → formal WAV

REALTIME_TRANSCRIPTION
        ├─→ Primary Recording Sink → formal WAV
        └─→ CanonicalRealtimeEventSource
             → Stage 16A True Streaming ASR
```

只有一个 `AudioRecord` owner；waveform / UI / ASR 不得建立第二个麦克风消费者。

录音事实优先级冻结为：

`microphone capture truth → primary recording truth → optional ASR truth`

ASR slowdown / overflow / failure 不得损坏正式 WAV。

## 2. Final Freeze 前 GitHub 真实基线

- repository：`ioannes78/voica-android`
- target branch：`main`
- base `main`：`075bc9689b011678685e1046e3590a26d787067f`
- development branch：`stage16a1-phone-microphone`
- PR：#29 `[APK] Stage 16A.1 — Phone Recording & Realtime Transcription QA`
- Final product / QA source HEAD：`c7d0a210a434a7c1c7d88c8fa4593964ec479852`
- versionCode：91
- versionName：`1.1.0-stage16a`
- Room：v13；Stage 16A.1 未增加 migration
- production model channel：unchanged

Q2 final commits：

- `5d527a920b3bd157dfd06168e7d78a605e03ce3e` — reject platform-silenced microphone PCM
- `c7d0a210a434a7c1c7d88c8fa4593964ec479852` — regression test: silenced PCM never reaches recording consumer

## 3. Final CI / QA Artifact

Final source HEAD `c7d0a210...`：

- Android PR CI：#1531 / run `38752372736` — **SUCCESS**
- Fast PR Gate — **SUCCESS**
- Unit tests and debug compile gate — **SUCCESS**
- QA APK Gate — **SUCCESS**
- Build production-like QA APK — **SUCCESS**
- Verify QA APK — **SUCCESS**
- Upload QA APK — **SUCCESS**

Final QA artifact：

- Artifact：`Voica-1.1.0-stage16a-export-qa-apk`
- Artifact ID：`11981714433`
- Artifact digest：`sha256:9e222393a184783f6e4ec661733e04386bd1d3ce98187736ebe46f548754a24f`
- QA APK SHA-256：`710645276b88deed1632c9c11b9d80b9c6227a1bff2ef326fa3dcad63a201fc7`

Production Gate 在 Draft PR 上保持 skipped；Stage 16A.1 未改变 production signing/model-channel identity。

## 4. 冻结的手机录音事实

- Android `RECORD_AUDIO` 权限；永久拒绝可引导 Settings，恢复后无需重启 App；
- microphone foreground service 独立持有正式 capture；
- capture PARTIAL_WAKE_LOCK 与 Stage 16A ASR execution lease 分离；
- canonical capture：PCM16 / 16 kHz / mono；
- `VOICE_RECOGNITION` 优先，受控 fallback 到 `MIC`；
- read chunk：1280 samples / 80 ms；
- formal WAV 使用同步 primary writer，不经过 bounded ASR queue；
- Pause 不写 silence；Resume 建立 `CONTROL_BOUNDARY` epoch；
- Stop 修补 RIFF / fsync / atomic finalize；
- 0 PCM 不注册 44B 空 WAV；
- `RecordingSourceType.PHONE_MICROPHONE`；
- canonical asset 使用 direct identity derivation，不伪造转换。

## 5. 手机实时转写冻结事实

- 复用 Stage 16A source-neutral True Streaming pipeline；
- 手机 realtime 不再额外生成 Stage 16A local-audio 第二份 WAV；
- 顺序固定：

```text
AudioRecord
→ formal WAV writer
→ best-effort realtime bridge
→ Stage 16A streaming ASR
```

- realtime bridge bounded；overflow/drop 不回滚 formal PCM；
- realtime model 在麦克风开始采集前检查；model missing 时阻断，不静默降级为纯录音；
- realtime ASR 运行中失败后，正式录音允许继续；
- Pause/Resume timeline 通过 epoch-local → canonical absolute sample projection；证据不足时降级 `SEGMENTED_CAPTURE`，不得伪造 canonical 对齐；
- Stop 先完成正式 WAV，再 finalization realtime run / transcript attachment / projection。

## 6. Background / Recovery 冻结事实

- microphone FGS 是正式 capture 的后台 owner；
- Home/background、screen-off 已短时真机验证持续录音；
- notification/deep link 能区分 RECORDING / REALTIME 产品入口；
- Activity/Compose recreation 不拥有 AudioRecord；runtime 属于 application scope；
- `.wav.part` 启动恢复只扫描 `recordings/phone/<recordingId>/phone_*.wav.part`；
- 有效 staging 修复 RIFF 后以 `手机录音（异常恢复）` 注册；
- 空 staging 删除，不制造空录音；
- process-death stale `PHONE_MICROPHONE` realtime run 转 INTERRUPTED，文本保留；
- 不猜测把 stale run 绑定到 recovered WAV。

## 7. Q1 / Q2 关闭事实

### Q1 — direct canonical lineage

真机发现手机录音 WAV 可播放，但离线转写提示 canonical PCM 未准备好。

修复后：

- direct phone canonical WAV 注册 identity `AudioDerivation`；
- sourceAssetId == outputAssetId；
- profile：`CANONICAL_PCM16_16000_MONO_WAV_V1`；
- Room v13 database-open data reconciliation 自愈旧 QA PHONE_MICROPHONE 录音；
- 产品层中文化 source/error 文案。

Q1 真机复测：**PASS**。

### Q2 — Android client-silenced microphone truth

真机发现 Voica 录音期间相机抢占麦克风时，Android 仍返回成功 read，但 PCM 被系统静音；旧实现把静音 PCM 当正常内容写入 WAV。

最终修复冻结为：

- `AudioRecord.read()` 成功后、formal WAV / ASR consumer 之前读取 `AudioRecordingConfiguration.isClientSilenced`；
- `clientSilenced=true` 时该 frame 不进入任何 consumer；
- capture 转 `FAILED / CLIENT_SILENCED`；
- diagnostics 记录 `silencedReadCalls / silencedSamplesRejected / terminalReason`；
- 已录内容按异常中断语义保护；
- 用户可 Stop 保存，或 Pause→Resume 重新建立 capture；
- 系统静音数据不得被包装成“正常录音”。

Q2 CI #1531：PASS；Q2 真机复测：**PASS**。

## 8. P7 真机 QA 最终矩阵

```text
S1  手机录音 Start → Stop → WAV / Library                         PASS
S2  手机录音 Pause / Resume，暂停时间不进 WAV                      PASS
S3  手机录音 Home/background                                      PASS
S4  手机录音 screen-off                                           PASS
S5  手机实时转写 Small realtime                                   PASS
S6  realtime Stop → WAV + durable Transcript                      PASS
S7  realtime Pause / Resume / CONTROL_BOUNDARY                    PASS
S8  artificial slowdown 1.2s / 2.5s recording truth              PASS
S9  Chinese Large CTC realtime                                    PASS
S10 realtime model missing / unavailable                          PASS
S11 RECORD_AUDIO 拒绝 / 永久拒绝 / Settings recovery              PASS
S12 immediate second recording                                    PASS
S13 process interruption → staging recovery                        PASS
S14 microphone contention / camera + Q2 retest                     PASS
S15 30-minute Small realtime + screen-off                          NOT RUN / WAIVED BY USER
```

S8 代表性证据：

- artificial slowdown 两次实际触发；
- ASR overflow 1 episode；
- droppedFrames=8；
- droppedSamples=10240 / 640 ms；
- 正式 WAV 从头到尾完整连续；
- 证明 ASR 支路允许有界丢帧，而正式 recording truth 不受 backpressure 破坏。

## 9. S15 用户豁免与剩余风险

用户最终明确：

> S15 不测试，通过。

工程记录必须保持：

`S15 = NOT RUN / WAIVED BY USER`

不得在后续 Freeze/Handoff/roadmap 中改写为“30 分钟实测 PASS”。

因此 Stage 16A.1 接受以下剩余风险：

- 手机麦克风 + Small realtime 连续 30 分钟真实 PSS / CPU / thermal / battery 趋势未验证；
- 30 分钟级 screen-off / OEM Doze / FGS / WakeLock 组合未在 Stage 16A.1 单独 soak；
- 长时间 formal WAV writer + realtime ASR + durable transcript 同时运行的低频资源泄漏边界未真机证明；
- 30 分钟级 route/client-silenced 低频竞争没有专项 soak。

现有短时 background/screen-off、S8 强制 stall、S5/S9 realtime、S14 contention 证据没有暴露对应缺陷，但不能等价为 S15 已执行。

## 10. 冻结边界

后续 Stage 不得无明确新需求退化：

- 一个 AudioRecord owner；
- formal recording truth 永远在 realtime ASR fan-out 之前；
- ASR bounded overflow 不得反压/损坏正式 WAV；
- pause wall-clock 不得合成 silence；
- system client-silenced PCM 不得写入正式媒体；
- model missing 必须在 capture 前阻断；
- 手机正式 WAV 是 canonical recording truth，direct identity lineage 必须存在；
- realtime transcript 与 file ASR versions 继续独立；
- Room v13 migration lineage 不得破坏；
- Stage 16A frozen Small / Large realtime model matrix 保持；
- production model channel 未因 Stage 16A.1 promotion；
- `voice-card-android` 完全隔离规则继续有效。

## 11. 用户最终批准

用户已明确确认 Stage 16A.1 整体验收通过，并明确豁免 S15。

因此允许创建 Final Freeze / Handoff；后续 merge 前仍须重新核验 PR #29 最终 HEAD、CI、mergeability 与文档提交后的 CI 状态。
