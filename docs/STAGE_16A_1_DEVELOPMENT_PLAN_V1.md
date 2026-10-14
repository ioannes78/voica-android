# Stage 16A.1 Development Plan V1 — 手机录音 / 手机实时转写

状态：**APPROVED / P0 IN PROGRESS**

确认日期：2026-10-15

开发分支：`stage16a1-phone-microphone`

基线 `main`：`075bc9689b011678685e1046e3590a26d787067f`

## 1. 开发原则

Stage 16A.1 以“产品功能拆开、录音底座共用”为核心：

```text
手机录音
└─ PhoneMicrophoneCapture → Primary Recording Sink → WAV

手机实时转写
└─ PhoneMicrophoneCapture
   ├─→ Primary Recording Sink → WAV
   └─→ Stage 16A realtime ASR
```

必须始终满足：

- 正式录音优先于 ASR；
- ASR 不反压 capture/recording；
- 一个 AudioRecord owner；
- 一个 canonical source pump；
- 手机 realtime 复用 Stage 16A 上层 pipeline；
- 不修改 Stage 16A 历史 Freeze/Handoff 事实；
- GitHub 当前仓库是唯一事实来源。

## 2. P0 — 文档与契约收口

目标：在生产代码修改前冻结 Stage 16A.1 产品边界。

任务：

- 更新 `STAGE_16A_1_PHONE_MICROPHONE_PLAN.md`；
- 新增 `STAGE_16A_1_REQUIREMENTS_V1.md`；
- 新增本开发规划；
- 当前 Stage 状态需要在 `AGENTS.md` 中刷新，但不得破坏已有历史冻结记录；
- 核验 Stage 16A canonical source seam、realtime persistence、recording library、FGS/WakeLock 边界；
- 不修改生产代码。

完成条件：

- 文档明确拆分“手机录音 / 手机实时转写”；
- Room 默认保持 v13；
- 不引入第二套 ASR/录音库；
- 历史 Freeze/Handoff 不被重写。

## 3. P1 — Phone Microphone Platform Foundation

目标：建立与 ASR 无关的 Android microphone capture 基础设施。

任务：

- Manifest 增加 `RECORD_AUDIO`；
- 增加 microphone FGS 权限/type；
- 新建 `PhoneMicrophoneForegroundService`；
- 实现 `PhoneMicrophoneCapture`；
- 优先验证 `MediaRecorder.AudioSource.VOICE_RECOGNITION`，保留受控 `MIC` fallback；
- 以 `AudioRecord.getMinBufferSize()` 为底线并增加合理 headroom；
- 固定输出 canonical PCM16 / 16k / mono；
- 建立 source-neutral capture state / diagnostics；
- 建立独立录音 WakeLock lease；
- permission denial/permanent denial/Settings recovery。

P1 不接 ASR，不先做 realtime UI。

验证：

- 可持续读出 canonical PCM；
- background / screen-off 基础链不因 Activity 生命周期停止；
- permission 与 FGS 错误均为中文且可恢复。

## 4. P2 — Recording Truth / Formal WAV / Library

目标：先把“手机录音”做成独立可靠产品。

任务：

- primary recording sink；
- 复用/抽象 `RealtimePcmWavSegmentWriter` 能力，避免第二套 WAV 格式实现；
- recording staging；
- Start / Pause / Resume / Stop；
- Pause 不写 silence；
- Stop patch RIFF + fsync + atomic finalize；
- 手机录音文件命名与显示名策略；
- `RecordingSourceType.PHONE_MICROPHONE`；
- `CANONICAL_WAV`；
- 进入现有 Recording Library；
- 搜索索引/播放/分享/导出沿用现有 Recording lifecycle；
- abnormal process interruption staging recovery。

关键要求：

- P2 不依赖 Small/Large realtime model；
- P2 完成后即可先执行纯录音 S1–S4。

## 5. P3 — Stage 16A Realtime Integration

目标：在可靠录音底座上增加“手机实时转写”。

任务：

- 实现 phone microphone canonical event source；
- 优先复用现有 `CanonicalRealtimeEventSource` seam，不新增重复的宽泛音频接口；
- 生成 SessionStarted / EpochStarted / PcmFrame / SessionEnded；
- Pause/Resume 产生 `CONTROL_BOUNDARY` epoch；
- 接入 `RealtimeTranscriptCoordinator`；
- 复用 Stage 16A bounded ASR buffer；
- 复用 Small / Large CTC；
- 复用 VAD/stabilizer/punctuation；
- `RealtimeAudioSourceTypeValue.PHONE_MICROPHONE`；
- phone realtime run 与 Recording 建立正确关联；
- pure phone recording 不创建 realtime run。

模型行为：

- 启动前模型不可用 → 不启动 realtime transcription；
- 已开始录音后 ASR runtime 异常 → 正式录音继续，realtime 标记 degraded/failed。

## 6. P4 — UI / UX Integration

目标：对齐录音卡产品结构，同时避免页面复制。

主页/设备产品页：

- `手机录音`；
- `手机实时转写`。

手机录音页：

- 来源；
- 时长；
- 波形；
- Pause/Resume；
- Stop。

手机实时转写页：

- 复用 Stage 16A realtime reader；
- 顶部明确“手机麦克风”；
- 当前 realtime model；
- PARTIAL / STABLE / FINAL；
- waveform；
- Pause/Resume/Stop；
- Mini Live Bar。

不得：

- 为手机 realtime 创建第二套 reader；
- 默认同时采集 recorder-card + phone mic。

## 7. P5 — Background Lifecycle / Notification / Deep Link

目标：正式支持 Home 与 screen-off。

任务：

- microphone FGS notification；
- notification reopen/deep link；
- capture WakeLock 与 ASR WakeLock 分离；
- Activity recreation 不影响录音；
- foreground service terminal cleanup；
- Stop / failure / interruption 幂等收口。

重点验证：

- ASR runtime terminal 不得错误停止仍在进行的 formal recording；
- formal recording terminal 必须最终释放 microphone/FGS/WakeLock。

## 8. P6 — Resilience / Contention / Recovery

目标：覆盖手机麦克风特有故障面。

任务：

- AudioRecord read error；
- short read；
- silenced client；
- microphone ownership contention；
- camera/VoIP 占用；
- basic route change；
- permission revoke；
- unexpected service/process interruption；
- recoverable staging reconciliation；
- second recording state cleanup。

明确不在本阶段做：

- Bluetooth SCO 专项；
- USB 高级路由；
- call recording。

## 9. P7 — QA / Candidate Closure

按单项逐条真机测试；每次只推进当前测试项。

测试矩阵：

```text
S1  手机录音：Start → Stop → WAV 可播放 / Library 正常
S2  手机录音：Pause / Resume，暂停时间不进入 WAV
S3  手机录音：Home/background 持续
S4  手机录音：screen-off 持续
S5  手机实时转写：Small realtime
S6  手机实时转写：Stop → WAV + durable Transcript
S7  手机实时转写：Pause / Resume / CONTROL_BOUNDARY
S8  artificial slowdown 1.2s / 2.5s：ASR 可 overflow，正式录音零丢样
S9  Chinese Large CTC realtime
S10 realtime model missing/unavailable
S11 RECORD_AUDIO 首次拒绝/重试/永久拒绝/Settings recovery
S12 immediate second recording
S13 process interruption → staging recovery，不伪造正常完成
S14 microphone contention / camera/VoIP / basic route change
S15 30-minute phone recording stability，优先 Small realtime + screen-off
```

S1–S4 必须在不安装/不加载 realtime model 的条件下可通过。

S8 核心证据：

```text
capture samples
= primary recording accepted samples
= finalized WAV samples
```

同时允许：

```text
ASR dropped/overflow samples > 0
```

但：

```text
recording dropped samples = 0
```

## 10. Diagnostics Gate

每个 candidate 至少可观测：

- source/mode；
- AudioRecord config/state；
- route；
- capture samples；
- primary recording samples；
- WAV finalized samples；
- realtime ASR offered/consumed/drop；
- pause/resume；
- FGS；
- WakeLock；
- terminal reason。

不能只显示 ASR counters 而无法证明正式录音完整性。

## 11. Database / Version Gate

P0 结论：

- Room 保持 v13；
- 不为 `PHONE_MICROPHONE` 人为制造 v14；
- production model channel 不修改；
- Stage 16A 当前 versionCode/versionName 不在 P0 文档阶段提升。

如果后续代码阶段确需：

- Room migration；
- production model channel promotion；
- model matrix 修改；

必须停止并作为独立变更重新确认。

## 12. Freeze / Handoff Gate

只有同时满足以下条件才允许创建 Stage 16A.1 Freeze/Handoff：

1. 适用 CI 全绿；
2. S1–S15 按用户实际执行/豁免结果准确记录；
3. 用户明确确认 Stage 16A.1 整体测试通过；
4. 不把未执行项目写成 PASS；
5. 记录准确 source HEAD / version / Room / CI / artifact / model pin；
6. 然后才允许合并 `main`。
