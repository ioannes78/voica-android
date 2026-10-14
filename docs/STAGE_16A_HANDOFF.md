# Stage 16A Final Handoff — 本地 True Streaming ASR → Stage 16A.1 手机麦克风录音转写

状态：**FINAL HANDOFF — Stage 16A 用户整体验收通过；长时豁免已记录；允许 merge 后进入下一阶段**

Freeze 文档提交：`b17ebb7e39423b8e954090084358adf791888cc1`

## 1. 下一阶段

下一正式产品阶段按当前路线图为：

**Stage 16A.1 — Phone Microphone Capture / 手机麦克风录音转写**

权威规划：

- `docs/STAGE_16A_1_PHONE_MICROPHONE_PLAN.md`

Stage 16A.2 Timeline Annotation、Stage 16B 文件 ASR V2、Stage 18 cloud realtime 继续留在后续规划，不应在 Stage 16A.1 未确认需求/规划前提前混入。

## 2. 接管前必须重新核验

Stage 16A.1 开始任何需求修订、开发规划或编码前，必须重新核验 GitHub 当前：

1. `main` HEAD；
2. PR #28 是否已 merged；
3. 当前 open PR；
4. 最新 GitHub Actions / CI / Production Gate；
5. `AGENTS.md`；
6. `docs/STAGE_16A_FREEZE.md`；
7. `docs/STAGE_16A_HANDOFF.md`；
8. `docs/STAGE_16A_1_PHONE_MICROPHONE_PLAN.md`；
9. `docs/STAGE_16_STREAMING_CONTRACT_V3.md`；
10. `docs/ROADMAP_STAGE_16_18_STREAMING_AMENDMENT.md`；
11. app versionCode / versionName；
12. Room version / migration / schema files；
13. production model channel pin / manifest version / digest；
14. Stage 15 canonical source/runtime；
15. Stage 16A StreamingAudioBuffer / chunk / VAD / recognizer / stabilizer / punctuation / durable realtime persistence / UI runtime。

GitHub 当前仓库始终是唯一事实来源；本 Handoff 的 SHA / CI / artifact 只作为 Stage 16A 历史交接线索。

## 3. Stage 16A 最终产品候选

Final product / CI source HEAD：

`5ea86894893b06aab8bd18f4a9a2bae42395cf69`

用户最终真机修复候选：

`4b1fd4b759c0a826bb49a3e1620e714baea237df`

两者之间仅有 1 个 CI provenance 元数据提交，唯一修改文件为：

`ci/generate-release-provenance.sh`

没有 Stage 16A production app/core/engine 行为变化。

Final identity：

- versionCode：`91`
- release versionName：`1.1.0-stage16a`
- releaseQa：`1.1.0-stage16a-export-qa`
- Room：v13，schema 1..13
- sherpa-onnx：1.13.8
- ABI：arm64-v8a
- production model channel commit：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：7
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

## 4. Final CI / signing evidence

Final source HEAD `5ea86894...`：

- Android PR CI #1455 / run `38598067323` — **SUCCESS**
- Fast PR Gate — **SUCCESS**
- Android Production Gate #315 / run `38598067317` — **SUCCESS**
- Production preflight — **SUCCESS**
- Production signed candidate / release — **SUCCESS**

Final signed artifacts：

- production certificate SHA-256：`f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`
- APK SHA-256：`b995bbb55d74e2342f764592fbc07485219d6e51f5c05fe1d5f389dacbc9997d`
- APK size：`30,930,155 B`
- AAB SHA-256：`17b1962810332f8281007c0b654e9e01576a598f6492e36c46d42e94ad2c4369`
- AAB size：`26,411,586 B`
- APK artifact id：`11910788227`
- AAB artifact id：`11911912637`
- provenance artifact id：`11910743545`

Final provenance 已确认 `room_schema=13`。

## 5. Stage 16A frozen input/output contract

### Input

Stage 16A 的 QS668/CB08 realtime 输入唯一来自：

```text
RealtimeCanonicalAudioSource
→ RealtimeCanonicalStreamEvent
```

Canonical audio：

- PCM16
- 16 kHz
- mono
- session / epoch / canonical sample truth

Stage 16A.1 增加手机麦克风时，应实现新的 canonical source adapter，并复用 Stage 16A 上层 pipeline；不得把手机 AudioRecord 接成第二套不兼容 ASR 管线。

### Streaming pipeline

冻结为：

```text
CanonicalRealtimeEventSource
→ RealtimeTranscriptCoordinator
→ StreamingAudioBuffer
→ StreamingChunkAssembler
→ Streaming VAD
→ Realtime ASR session
→ HypothesisStabilizer
→ punctuation
→ durable FINAL persistence + live UI
```

当前 chunk：1280 samples / 80ms。

当前 Stage 16A buffer：2.0s bounded buffer。

## 6. Frozen realtime model matrix

正式产品 realtime models：

- Small Bilingual Zipformer zh-en INT8 — light/default；
- Chinese Large Zipformer CTC INT8 — high-quality Chinese。

不得在 Stage 16A.1 静默：

- 恢复 Large Transducer；
- 把 SenseVoice / Qwen3 文件 ASR 伪装成 true streaming；
- 增加 AUTO routing；
- 在 active session 中热切模型。

如果 Stage 16A.1 要调整模型矩阵，必须作为新需求确认并重新 QA。

## 7. Durable realtime / media handoff

Room v13 已引入 durable realtime domain：

- run；
- media segment；
- FINAL utterance；
- timeline marker。

核心媒体语义：

- single-segment clean run：verified local canonical WAV 可直接成立；
- reconnect multi-segment：手机 segment 不能伪拼成完整 device recording；
- segmented realtime text 允许连续阅读，但不伪造 canonical-aligned seek；
- terminal 后完整设备 OPUS fallback 成功时进入标准 device media/canonical pipeline；
- fallback 失败保持明确 remote/pending；
- normal one-segment original OPUS 按需下载，不在 STOP 后强制自动下载；
- realtime transcription 与后续 file ASR transcription version 独立共存。

Stage 16A.1 手机麦克风模式中，手机自身是正式录音设备，因此规划要求同时持久化正式本地录音，不允许只做 ASR samples 而无可播放源音频。

## 8. Lifecycle handoff

已真机冻结：

- START / STOP；
- PAUSE / RESUME；
- TYPE3 SAVE / device recording ended；
- second START；
- App disconnect/reconnect；
- Bluetooth OFF/ON；
- background；
- screen-off；
- notification return；
- model missing；
- model setting only applies next run；
- bounded artificial slowdown recovery。

Stage 16A.1 复用 realtime runtime 时不得把 ASR engine 生命周期重新绑定 Activity / Compose lifecycle。

Stage 16A.1 新增 microphone ownership 时需重新设计/测试：

- RECORD_AUDIO permission；
- microphone foreground service；
- Audio Focus / route change；
- phone call / other app microphone contention；
- permission revoke；
- process/background/screen-off；
- 正式本地音频 writer 与 canonical sample truth。

## 9. 真机 QA 历史结论

Stage 16A S1–S12：**最终全部 PASS**，包括各轮失败后的修复重测。

最终补充：

- S11 missing-model 中文产品文案 R1：PASS；
- terminal localAudio diagnostics R2：PASS；
- Stage15 BLE / Opus / canonical regression counters 在最终相关真机轮次保持无异常；
- reconnect multi-segment full OPUS fallback 可播放 / 转写 / 导出；
- screen-off wakelock acquire/release 正常；
- 2.5s artificial stall bounded recovery，没有 finalize storm。

详细证据见 PR #28 comments 与 `docs/STAGE_16A_FREEZE.md`。

## 10. 长时测试事实与 waiver

实际执行：

```text
Large CTC 30 min = PASS
```

未执行、用户明确豁免并接受风险：

```text
Small 30 min  = WAIVED BY USER
Small 60 min  = WAIVED BY USER
Small 120 min = WAIVED BY USER
Large 60 min  = WAIVED BY USER
Large 120 min = WAIVED BY USER
```

后续不得把这些 waiver 改写成 PASS。

Stage 16A.1 自己的 microphone 长时录音/转写稳定性不能自动继承这些 waiver；是否执行或豁免必须在该阶段重新确认。

## 11. 已接受风险

Stage 16A 未通过真实 soak 证明：

- Small 30/60/120 分钟长期资源趋势；
- Large >30 分钟的长期资源趋势；
- 1–2 小时级 OEM/Doze/Bluetooth/realtime UI/Room/JNI 低频边界。

这些是未执行测试带来的剩余风险，不是当前已复现 production defect。

## 12. Stage 16A.1 工作顺序

严格按现有开发门禁：

```text
重新核验 main / PR #28 merged / CI / version / Room / model pin
→ 阅读 Stage 16A Freeze/Handoff
→ 阅读 Phone Microphone Plan
→ 核验 current canonical source abstraction 与 realtime runtime
→ 输出 Stage 16A.1 修订需求
→ 用户确认
→ 输出 Stage 16A.1 修订开发规划
→ 用户确认
→ 再创建开发分支/编码
```

不得直接因为本 Handoff 已存在就自动编码 Stage 16A.1。

## 13. voice-card-android 隔离

硬性规则继续有效：

不得复制、迁移、继承、cherry-pick、机械翻译或改写 `ioannes78/voice-card-android` 的实现。

只能使用 Voica 当前仓库、官方协议、真机行为事实与明确允许的参考来源独立实现。

## 14. 最终 merge 条件

用户已明确回复：

> Stage 16A 整体测试通过，确认 Freeze / Handoff / merge。

因此本 Handoff 创建后，重新核验 PR #28 的最终 HEAD、mergeability 与最终 CI/Production Gate 历史后，允许 merge 到 `main`；merge 后必须重新核验 `main` HEAD / version / Room / production model pin。
