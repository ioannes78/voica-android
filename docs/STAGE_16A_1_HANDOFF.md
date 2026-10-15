# Stage 16A.1 Final Handoff — 手机录音 / 手机实时转写 → Stage 16A.2 Timeline Annotation

状态：**FINAL HANDOFF — Stage 16A.1 用户整体验收通过；S15 长时测试按 USER WAIVER / NOT RUN 记录**

Freeze 文档提交：`3666e0038453690809ee74ebe52c2603dabedf34`

## 1. 下一阶段

下一正式阶段按当前路线为：

**Stage 16A.2 — Timeline Annotation**

后续仍依次保留：

- Stage 16B — file ASR V2；
- Stage 17 — cloud file ASR；
- Stage 18 — cloud realtime ASR。

Stage 16A.2 开始前不得直接编码，必须重新核验 GitHub 当前真实状态并重新确认需求/开发规划。

## 2. 接管前必须重新核验

开始 Stage 16A.2 前必须重新核验：

1. `main` HEAD；
2. PR #29 是否已 merged；
3. 当前 open PR；
4. 最新 GitHub Actions / CI；
5. `AGENTS.md`；
6. `docs/STAGE_16A_FREEZE.md`；
7. `docs/STAGE_16A_HANDOFF.md`；
8. `docs/STAGE_16A_1_FREEZE.md`；
9. `docs/STAGE_16A_1_HANDOFF.md`；
10. Stage 16 streaming contract / roadmap amendment；
11. app versionCode / versionName；
12. Room version / migration / schema files；
13. production model channel pin；
14. current phone microphone capture / recording / realtime runtime；
15. current canonical sample / epoch / timeline projection implementation。

GitHub 当前仓库始终是唯一事实来源；本 Handoff 的 SHA / CI / artifact 仅作为历史交接线索。

## 3. Stage 16A.1 最终产品候选

Final product / QA source HEAD：

`c7d0a210a434a7c1c7d88c8fa4593964ec479852`

Final identity：

- versionCode：91；
- versionName：`1.1.0-stage16a`；
- Room：v13；
- production model channel：unchanged；
- ABI / Stage 16A model matrix 继承当前主线真实配置。

Final Q2 commits：

- `5d527a920b3bd157dfd06168e7d78a605e03ce3e`；
- `c7d0a210a434a7c1c7d88c8fa4593964ec479852`。

## 4. Final CI / QA evidence

Final source HEAD `c7d0a210...`：

- Android PR CI #1531 / run `38752372736` — **SUCCESS**；
- Fast PR Gate — **SUCCESS**；
- Unit tests and debug compile gate — **SUCCESS**；
- QA APK Gate — **SUCCESS**；
- Build production-like QA APK — **SUCCESS**；
- Verify QA APK — **SUCCESS**；
- Upload QA APK — **SUCCESS**。

QA artifact：

- Artifact ID：`11981714433`；
- digest：`sha256:9e222393a184783f6e4ec661733e04386bd1d3ce98187736ebe46f548754a24f`；
- APK SHA-256：`710645276b88deed1632c9c11b9d80b9c6227a1bff2ef326fa3dcad63a201fc7`。

## 5. 冻结的手机麦克风 capture contract

Stage 16A.1 的 capture truth：

```text
PhoneMicrophoneCapture
→ canonical PCM16 / 16 kHz / mono
→ primary formal WAV writer
→ optional realtime fan-out
```

约束：

- 单一 AudioRecord owner；
- formal recording sink 永远先于 realtime ASR fan-out；
- UI / waveform / ASR 不得创建第二套 AudioRecord；
- `VOICE_RECOGNITION` 优先，MIC 作为受控 fallback；
- read chunk = 1280 samples / 80 ms；
- foreground service / WakeLock 不绑定 Activity/Compose 生命周期。

## 6. Formal recording handoff

正式手机录音冻结：

- 同步写入 `RealtimePcmWavSegmentWriter`；
- Pause 不写 wall-clock silence；
- Resume 新建 `CONTROL_BOUNDARY` epoch；
- Stop 修补 RIFF / fsync / atomic finalize；
- 0 PCM 不创建空录音；
- source：`PHONE_MICROPHONE`；
- canonical role：`CANONICAL_WAV`；
- direct phone canonical asset 必须有 identity derivation；
- `sourceAssetId == outputAssetId`；
- canonical profile：`CANONICAL_PCM16_16000_MONO_WAV_V1`。

Stage 16A.2 做 timeline annotation 时不得破坏该 direct canonical lineage。

## 7. Realtime handoff

手机实时转写复用 Stage 16A pipeline：

```text
AudioRecord
→ formal WAV writer
→ best-effort bounded realtime bridge
→ CanonicalRealtimeEventSource
→ Stage 16A True Streaming ASR
```

约束：

- recording truth > ASR truth；
- ASR overflow/drop/failure 不回滚正式 PCM；
- model missing 必须在 capture 前阻断；
- active run 失败后可保持正式录音；
- Pause / Resume timeline 通过 epoch-local sample → canonical absolute sample projection；
- timeline 证据不足时使用 `SEGMENTED_CAPTURE`，不得伪造连续 canonical 时间轴；
- Stop 先完成正式 WAV，再完成 realtime finalization / attach / projection。

Stage 16A.2 应直接消费这些 canonical sample / epoch truth，不应另建 wall-clock timeline。

## 8. Android microphone contention handoff

Q2 是 Stage 16A.1 必须保留的冻结行为。

Android 可能在另一个高优先级录音客户端（例如相机）抢占麦克风时，让 Voica 的 `AudioRecord.read()` 继续成功返回，但将 client PCM 静音。

冻结规则：

- 读取 `AudioRecordingConfiguration.isClientSilenced`；
- 检查发生在 successful read 后、任何 formal WAV / ASR consumer 前；
- `clientSilenced=true` 时 frame 禁止进入 consumer；
- capture 转 `FAILED / CLIENT_SILENCED`；
- diagnostics 记录 silenced reads / rejected samples / terminal reason；
- 已有媒体按异常中断语义保存；
- 不得把系统注入的静音 PCM 当成真实录音。

Stage 16A.2 若展示 timeline interruption marker，可使用这一异常事实，但不得把抢麦期间伪造为正常录音区间。

## 9. Recovery / persistence handoff

冻结事实：

- `.wav.part` 启动恢复修复有效 RIFF；
- 空 staging 不注册；
- recovered media 显示为 `手机录音（异常恢复）`；
- live abnormal capture 停止后保留已录媒体并显示异常中断 provenance；
- stale `PHONE_MICROPHONE + ACTIVE/FINALIZING` realtime run 启动时转 INTERRUPTED；
- stale run 不猜测绑定 recovered WAV；
- realtime durable text 保留。

## 10. 真机 QA 最终事实

```text
S1–S14 = PASS
S15 = NOT RUN / WAIVED BY USER
```

Q1 / Q2 均已关闭并真机复测通过。

S15 内容：

`30-minute phone microphone + Small realtime + screen-off`

用户明确选择不执行，并确认 Stage 16A.1 整体通过。

后续任何文档必须保持：

`S15 = USER WAIVER / NOT RUN`

不得改写为 PASS。

## 11. 已接受剩余风险

由于 S15 未执行，未被 Stage 16A.1 真机证明：

- 30 分钟手机麦克风 + Small realtime PSS/CPU/thermal/battery 稳定性；
- 30 分钟 screen-off + microphone FGS + WakeLock 的 OEM/Doze 行为；
- 长时间 formal WAV + realtime ASR + durable transcript 同时工作的低频资源问题；
- 30 分钟级 route / microphone contention 稀有边界。

Stage 16A 历史的长时结果不能自动视为 Stage 16A.1 手机麦克风长时 PASS。

## 12. Stage 16A.2 timeline 约束

Stage 16A.2 不得无明确新需求改变以下基础事实：

- 媒体时间来自 canonical sample truth，不来自 wall clock；
- Pause / Resume / client-silenced / process recovery 等边界必须可区分；
- 不得为不可证明区间生成伪 timestamp；
- realtime FINAL / file ASR / edited revision 的 lineage 必须继续区分；
- transcript timeline annotation 不得修改原始模型文本事实；
- timeline annotation 应建立在已有 sample/epoch/marker 数据之上，而不是重新解码媒体作为第二套事实来源。

## 13. voice-card-android 隔离

硬性规则继续有效：

不得复制、迁移、继承、cherry-pick、机械翻译或改写 `ioannes78/voice-card-android` 的实现。

Stage 16A.2 必须继续基于 Voica 当前仓库独立实现。

## 14. 下一阶段工作顺序

严格执行：

```text
重新核验 main / PR #29 merged / CI / version / Room / model pin
→ 阅读 Stage 16A / 16A.1 Freeze/Handoff
→ 核验 canonical sample / epoch / realtime marker / transcript persistence
→ 输出 Stage 16A.2 修订需求
→ 用户确认
→ 输出 Stage 16A.2 修订开发规划
→ 用户确认
→ 再创建分支/编码
```

不得因为本 Handoff 已完成而自动开始 Stage 16A.2 编码。

## 15. merge 条件

Stage 16A.1 用户已明确确认整体验收通过并豁免 S15。

Final Freeze 已提交。完成本 Handoff 后，必须重新核验 PR #29 的最终 HEAD、CI、mergeability；满足门禁后才允许合并到 `main`。合并后必须再次核验 `main` HEAD、versionCode/versionName、Room v13、production model channel 与 open PR 状态。
