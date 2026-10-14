# Stage 16A Final Freeze — 本地 True Streaming ASR

状态：**FINAL FREEZE — 用户已明确确认 Stage 16A 整体测试通过；剩余长时 Gate 经用户明确豁免并接受剩余风险**

冻结日期：2026-10-15

## 1. 冻结范围

Stage 16A 冻结 QS668 / CB08 canonical realtime audio 上层的本地 true streaming ASR 产品链：

```text
Stage 15 RealtimeCanonicalAudioSource
→ StreamingAudioBuffer
→ StreamingChunkAssembler
→ Streaming VAD
→ Local True Streaming ASR
→ PARTIAL / STABLE / FINAL
→ punctuation
→ durable realtime transcript / media segment persistence
→ realtime reader / Mini Live Bar / notification / lifecycle recovery
```

Stage 16A 不重新拥有 BLE/GATT/Opus transport，不从 BLE 绕过 Stage 15 canonical seam。

本阶段正式 realtime 模型：

- Small Bilingual Zipformer INT8：默认 / 中英双语；
- Chinese Large Zipformer CTC INT8：中文高质量。

手机麦克风、Timeline Annotation、云端 realtime ASR 不在 Stage 16A 核心范围，分别按后续 Stage 16A.1 / 16A.2 / Stage 18 规划执行。

## 2. Final Freeze 前 GitHub 真实基线

用户最终确认前重新核验：

- repository：`ioannes78/voica-android`
- target branch：`main`
- Stage 15 `main` 基线：`c16ae69bfb3c106ae7ed0f832a38f34b78e70394`
- development branch：`stage16a-local-true-streaming-asr`
- PR：#28 `[CANDIDATE] Stage 16A — Local True Streaming ASR`
- Final product / CI source HEAD：`5ea86894893b06aab8bd18f4a9a2bae42395cf69`
- 用户最终真机修复候选：`4b1fd4b759c0a826bb49a3e1620e714baea237df`
- `4b1fd4b7 → 5ea86894` 只有 1 个 CI 元数据提交，唯一修改文件为 `ci/generate-release-provenance.sh`，无 production app/core/engine 代码变化
- versionCode：`91`
- release versionName：`1.1.0-stage16a`
- releaseQa versionName：`1.1.0-stage16a-export-qa`
- production applicationId：`io.github.ioannes78.voica`
- QA applicationId：`io.github.ioannes78.voica.qa`
- Room：v13；schema 1..13 在库；12→13 additive migration 已冻结
- sherpa-onnx：1.13.8
- ABI：arm64-v8a
- production model channel commit：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：`7`
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

## 3. Final CI / Production Gate

Final source HEAD `5ea86894893b06aab8bd18f4a9a2bae42395cf69`：

- Android PR CI：#1455 / run `38598067323` — **SUCCESS**
- Fast PR Gate — **SUCCESS**
- Android Production Gate：#315 / run `38598067317` — **SUCCESS**
- Production preflight — **SUCCESS**
- Production signed candidate / release — **SUCCESS**
- production APK / AAB build — **SUCCESS**
- R8 / package / version / release security / Sherpa package / Room schema / production signer verification — **SUCCESS**

Final production artifacts：

- production certificate SHA-256：`f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`
- release APK SHA-256：`b995bbb55d74e2342f764592fbc07485219d6e51f5c05fe1d5f389dacbc9997d`
- release APK size：`30,930,155 B`
- release AAB SHA-256：`17b1962810332f8281007c0b654e9e01576a598f6492e36c46d42e94ad2c4369`
- release AAB size：`26,411,586 B`
- candidate APK artifact id：`11910788227`
- candidate AAB artifact id：`11911912637`
- provenance artifact id：`11910743545`

Final provenance 已验证：

```text
source_head_sha=5ea86894893b06aab8bd18f4a9a2bae42395cf69
version_code=91
release_version_name=1.1.0-stage16a
room_schema=13
abi=arm64-v8a
production_signed=true
```

Freeze 前发现旧 provenance script 仍硬编码 `room_schema=12`；已在 `5ea86894` 修正为从真实 Room schema 生成，Final Production Gate #315 重新通过。该修复只属于 release/CI 元数据，不改变 Stage 16A production 行为。

## 4. 冻结核心架构

### 4.1 Canonical ownership

Stage 16A 只消费 Stage 15 canonical realtime seam：

`RealtimeCanonicalAudioSource → RealtimeCanonicalStreamEvent`

产品侧只能有一个 canonical production consumer，再向 ASR、local audio persistence、diagnostics 等 fan-out。

禁止：

- 多个 production consumer 竞争 destructive `receive()`；
- 从 BLE/GATT/Opus 直接送 recognizer；
- 使用 protocol SEQ / wall clock 伪造媒体时间；
- discontinuity 后用 silence 补齐伪连续 timeline。

### 4.2 Streaming buffer / chunk

Final 配置冻结：

- canonical PCM：16 kHz / mono / PCM16；
- chunk：1280 samples / 80 ms；
- Stage 16A buffer：100 frames / 2.0 s；
- buffer bounded，不反压 Stage 15 / BLE；
- overflow 必须显式形成 discontinuity；
- S10-R2 后 overflow episode 使用 recovery watermark / bounded headroom 机制，避免 recursive finalize storm；
- 不合成 silence。

### 4.3 Hypothesis / punctuation

- PARTIAL：live mutable tail，不作为 FINAL 持久化；
- STABLE：产品稳定正文；
- FINAL：当前 utterance 权威结果；
- FINAL 后下一 utterance 必须重新建立 recognizer continuity；
- punctuation 失败不得使 realtime ASR 整体失败；
- FINAL durable persistence 与 realtime UI projection 分离。

### 4.4 Lifecycle

已冻结：

- START / STOP；
- PAUSE / RESUME；
- TYPE3 SAVE / `DEVICE_RECORDING_ENDED`；
- second START；
- app disconnect/reconnect；
- Bluetooth OFF/ON；
- background / notification reopen；
- screen-off；
- model missing；
- active-session model identity pinning；
- ASR artificial slowdown / bounded overflow recovery。

STOP / DEVICE end 统一做幂等 finalization；transport close 不停止录音卡本机录音。

Reconnect 只有在确认同设备、同一次仍 active 的 physical recording、用户 realtime intent 仍 active、generation 已推进时才自动恢复；恢复后新 canonical session / local segment / recognizer continuity，不伪拼断联期间缺失媒体。

## 5. 本地媒体 / durable realtime 冻结事实

Room v13 additive realtime domain 已冻结：

- realtime run；
- realtime media segment；
- FINAL utterance；
- timeline marker。

本地媒体规则：

- 单 segment clean run：direct canonical WAV 可作为 verified 本地标准音频；
- reconnect / multi-segment：不得把多个手机片段伪装成完整 canonical 文件；
- segmented realtime text 使用 `SEGMENTED_CAPTURE` 语义，不声称 canonical-aligned playback coordinates；
- terminal 后优先从同一 remote identity 获取完整设备 OPUS 作为 fallback；
- 成功后进入现有 DEVICE_OPUS → canonical audio 链；
- fallback 失败时保留明确 remote/pending 状态，不显示 0B 假本地文件；
- normal single-segment realtime STOP 不自动下载原始 OPUS，用户分享/导出原始 OPUS 时按需下载。

最终 diagnostics terminal snapshot 冻结为真实 sink 终态：正常 STOP 后应能显示 `closed=true / activeSession=n/a / finalized=1`，不得保留已完成媒体的旧 active snapshot。

## 6. 真机 QA 最终矩阵

```text
S1  Small START → multi utterance → STOP                 PASS
S2  Large CTC START → realtime → STOP                    PASS
S3  Pause / Resume                                       PASS
S4  TYPE3 SAVE / DEVICE_RECORDING_ENDED                  PASS
S5  second START                                         PASS
S6  App disconnect / reconnect                           PASS
S7  Bluetooth OFF / ON                                   PASS
S8  background + notification return + STOP              PASS
S9  screen-off                                           PASS
S10 artificial ASR slowdown 1.2s / 2.5s                  PASS
S11 selected realtime model missing                      PASS
S12 active-session model change / next START model       PASS
R1  S11 Simplified Chinese missing-model UI              PASS
R2  terminal localAudio diagnostics                      PASS
```

已确认的代表性结果：

- Small S1：canonical 106.800s，nativeRTF 0.2074，无 Stage16/15 drop；
- Large S2：canonical 86.320s，nativeRTF 0.3257；
- S6 reconnect：`localSegments=2 / completeLocalWav=false / remoteFallback=DOWNLOADED / libraryReconciliation=COMPLETED_DEVICE_SOURCE`，可播放/转写/导出；
- S9 screen-off：97.120s，Stage16/Stage15 时长一致，buffer overflow/drop=0，wake lock acquire/release 正常；
- S10-B 2.5s 强制 stall：2 个 bounded overflow episodes，总 ASR-side drop 1.06s，无 finalize storm，STOP / lifecycle 正常；
- S12：active Small run 中改为 Large 不热切；下一 START 正确使用 Large；
- R2：Small 75.280s，`localAudio closed=true degraded=false activeSession=n/a finalized=1`，Stage15 全链无异常。

## 7. 长时 / 性能 Gate

真实执行：

### Large Chinese CTC — 30 min

**PASS**。

关键证据：

- canonicalAudio=`1805.840s`；设备 raw duration=`1805s`；
- nativeRTF=`0.5493`；
- buffer peak=`1360ms`；overflowEvents=`0`；droppedFrames=`0`；droppedSamples=`0`；
- Stage15 malformed/overflow/drop/decode/source anomalies 全为 0；
- PSS：`92,370KB → 428,440KB`，未见相对短 run 的继续线性增长证据；
- thermal=`NONE`；battery 约 -5%；
- durable lifecycle 完成。

### 用户明确豁免

下列测试 **未执行，不得在后续文档写成 PASS**：

```text
Small 30 min      = NOT RUN / WAIVED BY USER
Small 60 min      = NOT RUN / WAIVED BY USER
Small 120 min     = NOT RUN / WAIVED BY USER
Large 60 min      = NOT RUN / WAIVED BY USER
Large 120 min     = NOT RUN / WAIVED BY USER
```

用户明确确认：

> 剩余 Small 30/60/120 和 Large 120 全部豁免，接受剩余风险。

此前 Large 60 分钟也已明确“不测试，通过”；工程记录按 **USER WAIVER** 保存，不伪造成实测 PASS。

## 8. 已接受剩余风险

因为上述长时 Gate 被豁免，Stage 16A 没有真机证明：

- Small 模型 30/60/120 分钟的 PSS / CPU / thermal / latency 慢性漂移；
- Large CTC >30 分钟到 60/120 分钟的持续稳定性；
- 超长 session 下低频 buffer overflow、OEM scheduling、Doze / Bluetooth stack 行为；
- 1–2 小时级 realtime Room/file persistence 与 transcript UI 更新的低频边界；
- 长时运行后极低频 native recognizer / JNI 资源问题。

现有短矩阵、screen-off/background、人工 2.5s stall 与 Large 30-min 证据没有显示对应生产缺陷，但不等价于被豁免的 soak 已执行。

## 9. 冻结边界

Stage 16A.1 及后续不得无明确新需求退化：

- Stage 15 canonical seam 是 QS668/CB08 realtime 唯一媒体输入事实；
- canonical media timeline 基于 sample truth，不基于 wall clock；
- discontinuity / reconnect / pause boundary 不得静默拼接；
- active realtime session 不热切模型；设置只对下一 session 生效；
- Small / Large CTC 是 Stage 16A frozen realtime product matrix；
- normal one-segment 和 reconnect multi-segment 的媒体完整性语义必须继续区分；
- device recording independence 必须保持；
- realtime FINAL durable history 不覆盖文件 ASR version；
- Room v13 必须继续保留完整 migration lineage；
- production model channel pin 未 promotion；
- `voice-card-android` 完全隔离规则继续有效。

## 10. 用户最终批准

用户最终明确回复：

> Stage 16A 整体测试通过，确认 Freeze / Handoff / merge。

因此允许创建 Final Freeze / Handoff，并在核验最终 PR/head 后合并到 `main`。
