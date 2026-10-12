# Stage 15 Final Freeze — BLE 实时音频链路

状态：**FINAL FREEZE — 用户真机验收通过；60 / 120 分钟长时 Gate 经用户明确豁免并接受剩余风险**

冻结日期：2026-10-12

## 1. 冻结范围

Stage 15 只冻结 QS668 / CB08 的 BLE realtime media foundation：

```text
TYPE=1 realtime control / audio
→ GATT notification parse / CRC / classify
→ 160B realtime transport block
→ 4 × fixed 40B Opus packet
→ persistent Opus decode
→ PCM16 16 kHz mono
→ canonical realtime session / epoch / sample timeline
→ bounded consumer seam
→ recording / realtime lifecycle reconciliation
```

Stage 15 **不包含** Stage 16A streaming ASR 产品逻辑、实时转写 stabilizer、实时转写 UI、实时标点或模型路由。

## 2. 冻结前 GitHub 真实基线

Final Freeze 写入前重新核验：

- repository：`ioannes78/voica-android`
- target branch：`main`
- `main` HEAD：`d82210cf2b8ce25df92f4f07df200019c501f8ea`
- development branch：`stage15-ble-realtime-audio`
- Final Freeze 写入前 branch HEAD：`cbdc3f6a44e5c3eb9d68b415603af29b9473dba7`
- PR：#27 `[APK] Stage 15 — BLE realtime audio pipeline`
- PR 状态：open / draft / mergeable / not merged
- latest pre-freeze Android PR CI：#1324 / run `38190133961` — SUCCESS
- versionCode：`90`
- versionName：`1.1.0-stage15`
- releaseQa：`1.1.0-stage15-export-qa`
- Room：v12
- Room schema：1..12 均在库；`MIGRATION_11_12` 存在
- production model channel commit：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：`7`
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

Final Freeze / Handoff 文档提交后必须重新以新的最终 HEAD 跑 Final CI；上述 #1324 只是候选代码与 QA1 的预冻结 CI 证据。

## 3. 最终真机候选身份

Stage 15.9 QA1 后用于 S5–S8 与后续稳定性验证的 production-like releaseQa：

- BuildConfig / PR merge-candidate commit：`99c2d809bf7830cbf95bfd4306e4e5f04051119d`
- source branch HEAD at build：`cbdc3f6a44e5c3eb9d68b415603af29b9473dba7`
- applicationId：`io.github.ioannes78.voica.qa`
- version：`1.1.0-stage15-export-qa (90)`
- APK SHA-256：`18121240a83e8df9231f51a7c1824a9fa1d0eea5ecbf4a3eaec6e52180a131b5`
- CI artifact：`Voica-1.1.0-stage15-export-qa-apk`
- artifact id：`11721457371`
- artifact digest：`sha256:a7338a6cd7a493b2f03673460a8fe475cae8b380191e9673b3fadd93f05910c8`

该候选之后没有 production code 变更；Freeze/Handoff 仅记录最终验收与交接事实。

## 4. 已冻结协议 / 媒体事实

### 4.1 TYPE1 / Opus framing

真实 QS668 / CB08 已确认：

- TYPE1 realtime START / STOP / PAUSE / RESUME 可用；
- TYPE1/CMD1 transport body 固定 160B；
- 160B = 4 × 40B Opus packet；
- 每 40B packet = 20 ms mono Opus，48 kHz / 960 samples encoded shape；
- 16 kHz mono decoder 正常每 packet 输出 320 samples；
- 每 160B block 正常贡献 1280 canonical samples；
- protocol SEQ 在 realtime audio 中恒为 0，不可作为排序、丢包、去重或 timeline 真值；
- canonical timeline 只按实际 decoder 输出 sample count 推进，不按 wall clock 或 protocol SEQ 补时间。

### 4.2 Ingress / decode

正式链：

```text
GATT notification
→ existing FrameParser / CRC
→ TYPE1/CMD1 classify
→ RealtimeAudioConsumer
→ bounded RealtimeAudioIngress
→ RealtimeOpusPacketizer
→ persistent Opus decoder
```

冻结规则：

- realtime audio 不走可丢 generic observer 主链；
- GATT callback 不执行 JNI decode / PCM / Room / ASR；
- encoded queue bounded；overflow / malformed / generation close 必须形成 continuity boundary；
- invalid/decode failure 不做 PLC，不伪造缺失媒体时间；
- decoder recovery 创建明确新 epoch。

### 4.3 Canonical realtime timeline

Canonical profile 固定：

- PCM16
- 16 kHz
- mono
- sessionId
- epochId
- epoch-local absolute sample index

Lifecycle event 冻结为：

- `SessionStarted`
- `EpochStarted`
- `PcmFrame`
- `ConsumerDiscontinuity`
- `SessionEnded`

新 session / reconnect 不得与旧媒体拼成伪连续 timeline；新 epoch 首帧从 sample index 0 开始并携带明确 reason。

### 4.4 Consumer backpressure / STOP drain

Canonical consumer queue bounded：

- 32 PCM frames
- 10,240 samples

慢 consumer 不得反压 GATT / decoder；overflow 时丢 stale PCM、保留 live edge，并发 `ConsumerDiscontinuity`。

STOP drain：

- STOP 写成功后不立即关闭 canonical session；
- quiet window = 250 ms；
- hard cap = 1500 ms；
- drain wall clock 只决定 finalize 时点，不推进 sample timeline；
- 正常结束 reason=`STOP`；
- hard cap 超时必须显式 `drainTimedOut=true`。

## 5. TYPE3 / TYPE1 设备生命周期冻结事实

真机矩阵确认：

- TYPE3-only Recording 会创建设备文件；
- TYPE1 START 也会改变设备 recording lifecycle，并创建设备文件；
- TYPE3 Recording 与 TYPE1 START 可短暂 coexist；
- TYPE1 STOP 会把设备 Recording 收敛为 Idle，因此 TYPE1 / TYPE3 并非完全独立 lifecycle；
- TYPE1-only START → Recording，STOP → Idle，单 session +1 设备文件；
- realtime active 时 production TYPE3 SAVE 可使设备 Recording → Idle。

Stage 15.8 冻结 `RealtimeRecordingLifecycleReconciler`：

- 只信任 FRESH `Recording/Paused → Idle`；
- runtime 仅在 ACTIVE/PAUSED 时触发 reconciliation；
- best-effort existing STOP；STOP 写失败时允许本地 external-termination drain fallback；
- canonical end reason=`DEVICE_RECORDING_ENDED`；
- exactly one `SessionEnded`；
- TYPE3-only 不启动 realtime side effect；
- explicit realtime STOP 不形成 reconciliation recursion。

## 6. Stage 15.9 Final Gate / QA1 冻结事实

Final Gate 仅在 debug / `.qa` 内部入口可见，正式 production release 不显示；Activity `exported=false`，无 MAIN/LAUNCHER。

QA1 修复的是诊断统计，不是 production media path：

- baseline / timer / heap / canonical observer 移入 process-lifetime QA session；
- reconnect / Activity recreate 不丢同轮 baseline；
- transport generation-owned counters 跨 generation 分段封存累计；
- 新 generation 归零 counters 不再直接减旧 generation baseline；
- 单测固定覆盖 generation1=100 blocks + generation2=40 blocks → final=140。

S5 / S6 真机已验证：

- generation transition 正确；
- cross-generation delta 为正；
- old session ended / new session started；
- no media timeline stitching；
- anomaly delta=0。

## 7. 真机验收矩阵

最终 short matrix：

```text
S1 START / STOP                         PASS
S2 PAUSE / RESUME                       PASS
S3 second START                         PASS
S4 TYPE3 SAVE reconciliation            PASS
S5 app disconnect / reconnect           PASS
S6 Bluetooth OFF / ON                   PASS
S7 background >= 3 min                  PASS
S8 screen-off >= 3 min                  PASS
```

S7：3m33s，same generation，canonical duration 205.360s，anomaly=0。

S8：4m32s，same generation，canonical duration 266.800s，anomaly=0。

## 8. 稳定性验收

### 8.1 Development Stability Gate

13m23s：**PASS**。

关键结果：

- generationTransitions=0
- start=2 / stop=2
- sessions=2 / source started=2 / ended=2
- acceptedBlocks=9881
- opusPackets=39524
- decodeSuccess=39524 / decodeFailures=0
- canonicalSamples=12,647,680
- canonical duration=790.480s
- all overflow / malformed / drop / gap / overlap / invalid epoch / end mismatch / drain timeout = 0
- heap start 6.676 MiB / peak 11.248 MiB
- ingress peak 2 blocks / 320B
- source peak 4 frames / 1280 samples
- restart recovery after long run：PASS

### 8.2 Candidate sustained run

29m50s：**ACCEPTED AS 30-MIN CANDIDATE EVIDENCE**。

关键结果：

- same candidate APK
- generationTransitions=0
- acceptedBlocks=22,226
- opusPackets=88,904
- decodeSuccess=88,904 / decodeFailures=0
- canonicalSamples=28,449,280
- canonical duration=1778.080s
- anomalyDelta=0
- heap start 7.612 MiB / current 10.080 MiB / peak 11.014 MiB
- ingress peak 1 block / 160B
- source peak 4 frames / 1280 samples
- maxBlockProcessingMs=27.793
- maxArrivalToEmitLagMs=28.345

该轮比名义 30:00 少 10 秒，且未重复执行本轮末尾 restart；用户明确接受其作为 30 分钟 Candidate 证据。相同 candidate 的 restart recovery 已在 13m23s Development Gate 中通过。

不得把 29m50s 伪写为实际运行满 30:00。

### 8.3 60 / 120 分钟 Gate

```text
60 min Stability Gate: WAIVED BY USER
120 min Final Soak Gate: WAIVED BY USER
```

用户最终明确回复：

> 测试通过，60/120分钟测试跳过并接受风险

因此 Stage 15 允许收口，但 **60 / 120 分钟不得标记为 PASS 或已执行**。

## 9. 已接受剩余风险

由于 60 / 120 分钟 Gate 被明确豁免，以下内容未被 Stage 15 真机长时 soak 证明：

- 1 小时以上 BLE notification 持续稳定性；
- 长时间 background / Doze 对 connected FGS / BLE lifecycle 的影响；
- 2 小时级 persistent Opus decoder 稳定性；
- 2 小时级 heap / queue / latency 慢性漂移；
- 极低频、仅在超长运行后出现的 OEM process / Bluetooth stack 行为。

现有 13m23s + 29m50s 证据没有显示 memory queue leak、backlog、decode failure 或 canonical timeline anomaly，但不能等价为 60 / 120 分钟 soak。

## 10. 冻结边界

Stage 16A 及后续不得无明确新需求退化下列事实：

- Stage 16 只消费 canonical realtime seam，不直接消费 BLE/GATT/Opus；
- 不使用 protocol SEQ 或 wall clock 伪造音频 timeline；
- reconnect / control / decoder recovery / consumer backpressure 必须保留明确 boundary；
- discontinuity 不得跨 unrelated epoch/session 静默拼接 ASR session；
- TYPE3 / realtime lifecycle reconciliation 继续保持幂等；
- Room v12 与完整 1..12 lineage 不因 Stage 15 改变；
- production model channel 在 Stage 15 不 promotion；
- `voice-card-android` 隔离规则继续有效。

## 11. Final CI / merge gate

本 Freeze 创建后仍必须完成：

1. 创建 `docs/STAGE_15_HANDOFF.md`；
2. 以 Freeze/Handoff 最终 HEAD 跑 Android PR CI；
3. Fast PR Gate + production-like QA APK Gate 均 SUCCESS；
4. PR #27 转 Ready；
5. Android Production Gate SUCCESS；
6. merge 到 `main`；
7. merge 后重新核验 `main` HEAD / version / Room / production model pin。

只有上述完成后 Stage 15 才算 GitHub 主线最终收口，Stage 16A 才正式解锁。
