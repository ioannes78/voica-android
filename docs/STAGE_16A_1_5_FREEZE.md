# Stage 16A.1.5 Final Freeze — Home & Notification UX Consolidation

状态：**FINAL FREEZE — 用户已明确确认 Stage 16A.1.5 全部真机 QA 通过；Stage 16A.1 S15 既有用户豁免继续保持 NOT RUN / WAIVED BY USER**

冻结日期：2026-10-16

## 1. 冻结范围

Stage 16A.1.5 冻结 Voica 的 Homepage 信息架构、App 内全局 activity / attention presentation、Android notification presentation glue，以及录音卡/手机普通录音与 realtime 的统一产品展示。

本阶段是 presentation consolidation，不重新拥有 recording、realtime、ASR、BLE、Audio I/O、canonical media、playback、model install、WorkManager/FGS/WakeLock 或 Room business lifecycle。

冻结后的 Home IA：

`Header → Device status → source → mode → current task card → recent content`

核心产品语义：

- recorder ordinary recording：`正在录音 · QS668 / CB08 · HH:mm:ss`；
- phone ordinary recording：`正在录音 · 手机 · HH:mm:ss`；
- recorder realtime：`实时转写中 · QS668 / CB08 · HH:mm:ss`；
- phone realtime：`实时转写中 · 手机 · HH:mm:ss`；
- pause 使用 `录音已暂停` / `实时转写已暂停`；
- Home 或 exact realtime reader 已明确展示同一 activity 时，只 suppress 同一 activity 的 App 内 live strip；
- realtime presentation 优先于其 backing recording truth，避免 recorder realtime 被错误显示成普通录音。

## 2. Final Freeze 前 GitHub 真实基线

Final Freeze 授权后重新核验：

- repository：`ioannes78/voica-android`
- target branch：`main`
- base `main`：`cd984fc7910092777190547b0e584dc74a260f32`
- development branch：`stage16a1_5-home-notification-ux`
- PR：#30
- Final implementation / QA source candidate：`c6183862eff62b407541f6e52f001e60c5da0864`
- versionCode：91
- versionName：`1.1.0-stage16a`
- Room：v13；schema `13.json` 已重新核验，Stage 16A.1.5 未增加 migration
- production model channel commit：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：7
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

说明：Freeze / Handoff / roadmap / progress 文档提交会使 development branch HEAD 晚于 `c618386...`；`c618386...` 是已完成自动化与真机验收的 production behavior candidate。后续文档提交不得改变 app/core/engine 行为。

## 3. Final CI / QA Artifact

Final implementation candidate `c6183862eff62b407541f6e52f001e60c5da0864`：

- Android PR CI：#1640 / run `38895364665` — **SUCCESS**
- Fast PR Gate — **SUCCESS**
- Unit tests + debug compile — **SUCCESS**
- QA APK Gate / production-like `releaseQa` — **SUCCESS**
- APK verify — **SUCCESS**
- APK upload — **SUCCESS**
- Android Production Gate #500：PR policy 下 **SKIPPED**，不是失败

QA artifact：

- artifact：`Voica-1.1.0-stage16a-export-qa-apk`
- artifact id：`12044161560`
- artifact digest：`sha256:702707a60961386a4e5417c6133ae71bcc90853f9a72f9455bd51d9605160bac`
- extracted APK size：`31,011,452 bytes`
- extracted APK SHA-256：`fb8320874c8d6d15de44160f0d1c1cfae14c7d8ccf857cfcf4c9d57576987ea2`

CI PASS 仅作为自动化 Gate；最终 Freeze 依据还包括下方完整真机 QA。

## 4. 冻结的 Homepage / Recording UX

### 4.1 Device status

设备状态卡固定为四列：

`电量 | 可用空间 | 录音文件 | 固件`

- `录音文件` 使用现有 device file truth；
- 点击进入现有设备录音文件页面；
- 不保留重复的独立“设备录音 XX 个文件”行。

### 4.2 最近内容

Home 底部 `最近内容`：

- 展示最近 3–5 个统一 recording session；
- 本地 rich library + 当前 device files 做 presentation union；
- exact `sourceRemoteIdentity == RemoteDeviceFile.identity` 做远端/本地关联；
- 不做全局 filename 去重；
- remote-only 显示 `仅在录音卡`，支持行内下载及 `下载并打开 / 仅下载`；
- 下载继续复用既有 download / verify / register chain，不建立新 lifecycle owner；
- `已转写` 只读取既有 completed-transcription truth；
- 排序使用 recording/event 创建时间，不因后续转写/编辑更新时间把旧录音顶到前面；
- `查看全部` 进入现有完整录音库，不维护第二套录音库 UI。

### 4.3 Ordinary recording

录音卡普通录音 Home card：

- 状态、Gain、设备名、文件名、实际可用文件大小、中央 elapsed、暂停/继续、停止；
- 无 realtime PCM truth 时不伪造动态 waveform。

手机普通录音：

- Home 直接控制；
- 保留真实 PCM waveform；
- 不新增独立 active phone recording 二级页；
- 已完成 recording detail 继续保留。

## 5. 冻结的 Realtime UX

录音卡与手机 realtime detail 统一为：

- 标题 `实时转写`；
- source + **实际运行模型**；
- 明显居中的 `HH:mm:ss` elapsed；
- 真实 PCM/audio-activity waveform；
- live transcript reader；
- Pause/Resume 使用 Primary；Stop 使用 Error；
- 正式产品 UI 不再显示 Sx-A / Sx-B artificial latency 按钮；底层 QA 调试能力未删除。

Home realtime card：

- READY：显示 configured realtime model；
- RUNNING：显示实际 runtime `modelLabel`；
- runtime truth 优先于后来变化的设置；
- loading truth 可显示 `模型加载中…`；
- recorder source 才显示 Gain。

Recorder realtime STOP 冻结行为：

- Stop 仍完成 ASR/audio/transcript/library/notification/task finalization；
- 只取消 Stop 后自动跳 recording detail；
- 保持当前 realtime 页；
- 不扩大到 phone realtime 的其他导航语义。

## 6. Gain / Timestamp / Theme 冻结

Gain Bottom Sheet：

- 保持 `SET_GAIN → GET_GAIN → device truth`；
- pending target 期间保持打开；
- reconciliation 后 readback 与 target 一致才自动关闭；
- error / mismatch 保持打开；
- 选择当前同值允许直接关闭且不发送冗余 command。

时间显示：

- visible recording timestamp：`yyyy-MM-dd HH:mm:ss`；
- explicit instant/offset 转本地时区；
- zone-less wall clock 不做错误时移；
- 仅 presentation，不修改存储事实。

按钮主题：

- Pause/Resume：Primary filled / onPrimary；
- Stop：Error filled / onError；
- disabled：Material disabled semantics。

## 7. App Global Status / Attention 冻结

Stage 16A.1.5 统一后的规则：

- running tasks + durable completion/failure/interrupted/candidate 统一走现有 global status presentation；
- durable attention host 不再绘制第二条独立重复 global bar；
- exact result page visible 时 suppress 对应 result row；
- completion 只有在 exact result page 真正 visible 后才 acknowledge；
- Mini Player 仅用于 playback；
- live recording/realtime strip 读取既有 runtime truth，不拥有 lifecycle；
- Home 已展示当前 activity 时不重复 global live strip；
- exact realtime reader 只 suppress 匹配 source/activity；
- defensive multi-activity projection 保留，但正常 Home 产品 UI 在已有 primary active activity 时不会再次展示第二个 Start 入口。

QA4-10 原测试曾要求“先启动录音卡 realtime，再通过正常 Home UI 启动手机普通录音”。源码核验确认该路径在产品 UI 中不可达：active primary card 会替代 idle source/mode/start controls。该测试设计被修正，不记为 App FAIL；最终 QA4-10 使用停止清理 → 下一任务切换的实际可达流程并通过。

## 8. Notification Registry / Swipe Semantics 冻结

现有 9 个 physical notification channel ID 保持不变：

1. `voica-device-session`
2. `voica-phone-microphone`
3. `voica-playback`
4. `voica-media-processing`
5. `voica-ai-summary`
6. `model-install`
7. `voica-task-result`
8. `voica-task-attention`
9. `voica-diarization-attention`

冻结约束：

- 不新增、删除、改名 channel ID；
- 不通过重置 importance 覆盖用户设置；
- system notification swipe != business acknowledge；
- presentation dismissal 可持久化，避免同一 logical result/generation 立即重新通知；
- durable App attention 继续存在；
- failure/interrupted system notification 可 swipe，business durable attention 不变；
- running FGS acquire/update/release ownership 不变；
- playback notification exact deep link、model-install journal adapter、canonical projection 都保持既有 business lifecycle。

## 9. 真机 QA 最终矩阵

### V4 Homepage / UX

```text
V4-1  Idle Home structure                                  PASS
V4-2  recorder ordinary recording Home card                PASS
V4-3  recorder realtime Home card                          PASS
V4-4  recorder realtime STOP stays on realtime page        PASS
V4-5  Gain set / readback / auto-close                     PASS
V4-6  device recordings count / entry                      PASS
V4-7  recent remote-only + transcription state/download    PASS
V4-8  timestamp presentation                               PASS
V4-9  phone ordinary recording Home control                PASS
V4-10 phone realtime Home card                             PASS
V4-11 View All → complete existing library                 PASS
V4-12 dark launcher icon distinction                       PASS
V4-13 status-bar / notification icon                       PASS
V4-14 realtime STOP then immediate manual open             PASS
V4-15 downloaded recent row open + session dedupe          PASS
V4-16 recent quantity / recorded-time ordering             PASS
```

### Notification / Global State

```text
H13 completion → view → clear                              PASS
H14 cancel / abnormal termination presentation             PASS
H15 notification deep links                               PASS
H16 POST_NOTIFICATIONS denied                             PASS
H17 background + screen-off 1–2 min                       PASS
H18 app task recreation / recovery                        PASS
H19 terminal notification swipe semantics                 PASS
H20 multi-task global aggregation                         PASS
```

### QA4 Final Refinement

```text
QA4-1  recorder realtime detail                           PASS
QA4-2  phone realtime detail                              PASS
QA4-3  Home recorder realtime configured/actual model     PASS
QA4-4  Home phone realtime configured/actual model        PASS
QA4-5  phone ordinary recording live strip                PASS
QA4-6  recorder ordinary recording live strip             PASS
QA4-7  phone realtime strip / View / suppression          PASS
QA4-8  recorder realtime strip / View / suppression       PASS
QA4-9  pause/resume wording + elapsed                     PASS
QA4-10 stop cleanup + next-task switching                 PASS
```

## 10. Stage 16A.1 S15 既有用户豁免

Stage 16A.1 的以下 Gate 在本阶段 **没有重新执行**：

`30-minute phone microphone + Small realtime + screen-off`

工程事实继续固定为：

`S15 = NOT RUN / WAIVED BY USER`

不得因为 Stage 16A.1.5 的短时 background/screen-off 或 notification QA 通过而把 S15 改写成 PASS。

## 11. Hard Boundary / 不得回归

Stage 16A.1.5 未修改且后续不得将本阶段 Freeze 解释为已授权修改：

- BLE protocol / transport / QS668 command semantics；
- recording / realtime ownership；
- AudioRecord source / route policy；
- AudioTrack route / recovery；
- Stage 16A / 16A.1 canonical audio truth；
- ASR inference / model engine；
- VAD / punctuation / diarization；
- model-install execution；
- WorkManager / FGS / WakeLock ownership；
- Room v13 business schema / migration lineage；
- Android system-notification business lifecycle；
- production model channel；
- `voice-card-android` 完全隔离规则。

## 12. 下一阶段边界

下一预留阶段：

**Stage 16A.1.6 — Android Audio I/O Compatibility**

状态：**RESERVED / NOT STARTED**。

该阶段用于独立处理：

- phone actual recording level；
- phone realtime input source；
- MIC / VOICE_RECOGNITION policy；
- USB / wired / usable Bluetooth microphone；
- Bluetooth output；
- QS668 BLE GATT 与 AudioRecord/AudioTrack coexistence；
- library playback while BLE connected；
- AudioTrack route recovery。

不得把 Stage 16A.1.6 的 Audio I/O 问题回灌到本 Freeze；开始 16A.1.6 前必须重新核验 `main` / PR / CI / source / Room / model channel，并重新完成需求与开发规划确认门禁。

Stage 16A.1.6 完成后再进入 Stage 16A.2 Timeline Annotation。

## 13. 用户最终批准

用户最终明确回复：

> 确认 Final Freeze / Handoff

因此允许创建 Stage 16A.1.5 Final Freeze / Handoff 文档；文档提交后的最新 branch HEAD 必须重新通过 CI，随后重新核验 PR #30 / main / mergeability 后才允许 merge 到 `main`。
