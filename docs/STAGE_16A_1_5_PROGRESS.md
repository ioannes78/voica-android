# Stage 16A.1.5 Progress

状态：**QA2 IMPLEMENTATION / DEVICE QA PENDING**

日期：2026-10-16

## 当前基线

- repository：`ioannes78/voica-android`
- base main：`cd984fc7910092777190547b0e584dc74a260f32`
- development branch：`stage16a1_5-home-notification-ux`
- PR：`#30`
- versionCode：91
- versionName：`1.1.0-stage16a`
- Room：v13，未新增 migration
- production model channel：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`，未修改
- QA1 real-device candidate：`114f0c5507c929da32e89a33905aa87fa4c5d33c`
- H1：未通过；根据真机截图进入 QA2 presentation/resource 修订
- Stage 16A.1 S15：`30-minute phone microphone + Small realtime + screen-off` = `NOT RUN / WAIVED BY USER`

## 已确认边界

- Stage 16A.1.5 位于 16A.1 与 16A.2 之间；
- notification 部分只改 Android notification、App global state/attention、Global/Attention bars 及 presentation glue；
- 未修改 recording / realtime / transcription / diarization / AI Summary / canonical / playback / model install 业务 lifecycle；
- 未修改 FGS acquire/release ownership、WakeLock、AudioRecord ownership、WorkManager/UIDT ownership；
- Room 保持 v13；
- production model channel 未修改；
- 历史 Freeze/Handoff 未改写。

## P0 — Docs / Baseline

- Requirements V1：APPROVED
- Development Plan V1：APPROVED
- branch 已从重新核验的 `main` 创建
- PR #30 已创建
- `docs/ROADMAP_STAGE_16A_PRODUCT_AMENDMENT.md` 已插入 Stage 16A.1.5
- `AGENTS.md` 顶部当前阶段仍为旧 Stage 16A.1；由于当前 GitHub contents 写接口只支持整文件替换，而 AGENTS 含大量历史冻结规则，未冒险做整文件重写；**Freeze 前必须用安全的增量编辑方式刷新顶部 current-state section**
- `docs/ROADMAP_STAGE_16_18_STREAMING_AMENDMENT.md` 仍保留旧 16A.1→16A.2 时点文字；当前顺序以本阶段 Requirements、Development Plan 与 `ROADMAP_STAGE_16A_PRODUCT_AMENDMENT.md` 为准，Freeze 前应做最小同步修订

## P1 — Unified State Projection

已完成纯 presentation model/resolver：

- `AppActivityKind`
- `AppActivitySource`
- `AppActivityLifecycleClass`
- `AppActivityAction`
- `AppActivityFact`
- `AppActivityProjectionResolver`

特点：

- 不拥有业务 truth；
- 不 import AudioRecord / Service / Room DAO / BLE / ASR runtime；
- 只把现有 truth 归类为 Primary / Secondary / Background / Attention / Playback。

## P2 — Existing Destination Adapter

已完成最小 typed destination seam：

- recorder-card realtime
- phone recording / phone realtime
- recording detail + playback/transcript/summary destination

未引入新全 App Navigation；model install 精确 model 页面导航未扩张，继续使用现有安全入口。

## P3 — Homepage IA

已完成：

- Idle 首页从四张同权入口卡收口为 `录音` / `实时转写`；
- source 作为 `录音卡 | 手机` 属性；
- 无录音卡时自动使用手机 source；
- active 时只显示一个 Primary Activity；另一并发 source 为 compact Secondary；
- 手机录音 Pause/Resume/Stop 可直接在首页调用现有 controller；
- recorder recording/realtime 和 phone realtime 控制全部调用既有 ViewModel/controller；
- 手机启动继续经过既有 microphone permission policy/request store；
- 未新增 AudioRecord owner 或 recording/realtime lifecycle。

## P4 — App Global Status

已完成：

- QA4 durable attention host 不再绘制第二条独立全局栏；
- `VoicaApp` 的 running tasks 与 durable completion/failure/interrupted/candidate 统一进入同一 `GlobalTaskStatusBar`；
- 删除旧 process-local `dismissedTaskKeys/terminalTaskNotices` terminal 队列；
- exact result page visible 时 suppress 对应 global row；
- completion 只在 exact 页面实际可见后 acknowledge，不再因收到导航 request 提前确认；
- Mini Player 保持独立，仅用于 playback。

## P5 — Notification Registry

已集中登记并锁定现有 9 个 physical channel ID：

- `voica-device-session`
- `voica-phone-microphone`
- `voica-playback`
- `voica-media-processing`
- `voica-ai-summary`
- `model-install`
- `voica-task-result`
- `voica-task-attention`
- `voica-diarization-attention`

没有新增、删除或改名 channel ID；没有修改 channel importance 以重置用户设置。

## P6 — Terminal Presentation / Swipe Semantics

已完成 `NotificationPresentationStore` + 非导出 dismiss receiver：

- swipe system notification != acknowledge business result；
- App 内 durable attention 可继续存在；
- 同一 logical result/generation 被 swipe 后不会因 Flow/process recreation 立即重发；
- completion / transcription attention / AI Summary attention / candidate / diarization attention 使用稳定 logical presentation key；
- failure/interrupted system notification 改为可 swipe，但业务 durable attention 不变；
- Candidate exact page visit 只清系统 Candidate notification，不修改 candidate version/business truth；
- Running FGS acquire/update/release 未修改。

## P7 — Canonical / Model Install / Playback Adapters

### Playback

- 系统播放通知有 recordingId 时复用既有 typed recording deep link，直接进入该录音 PLAYBACK 页面；
- 无 recordingId 时安全 fallback 到 MainActivity；
- MediaSession、playback runtime、FGS promote/detach/remove lifecycle 未修改。

### Canonical WAV

- 新增只读 adapter，读取现有 `CanonicalAudioCoordinator.progress` 与 `RecordingLibraryItem.derivations`；
- active progress → BACKGROUND_RUNNING；
- recoverable failure → INTERRUPTED_ATTENTION；
- permanent failure → FAILED_ATTENTION；
- READY/CANCELLED 不制造 attention；
- 未修改 canonical coordinator / generation lifecycle；
- adapter 已进入统一 `AppActivityFact` seam；当前未为此再次高风险整文件覆写 `VoicaApp.kt`，真机 H11 继续验证现有 canonical 详情/进度体验。

### Model Install

- `model-install` physical channel 改为引用 registry 常量，字符串不变；
- durable journal → `AppActivityFact` adapter；
- download / verify / interrupted / recoverable / terminal failure 保留现有语义；
- 未修改 WorkManager / UIDT / journal / resume/cancel lifecycle；
- 未扩大到精确 model 页面导航。

## P8 — Automated / CI

已新增或扩展测试覆盖：

- unified projection priority / grouping
- typed destination
- Homepage Primary/Secondary policy
- Mini Player visibility
- running + durable global task consolidation
- exact visible destination suppression
- notification channel ID compatibility
- notification presentation dismiss persistence
- playback exact deep link
- canonical projection
- model install journal projection

历史 CI 事实：

- #1553：新增 helper 访问 private notification-id constants 导致 compile fail；只做可见性最小修复，数值/行为未改变
- #1554：SUCCESS
- #1555：SUCCESS
- #1557：SUCCESS
- #1566 / run `38826193596`：Fast PR Gate SUCCESS，包含 `c359535...` 全部 implementation + tests
- QA1 candidate `114f0c5507c929da32e89a33905aa87fa4c5d33c`：Fast PR Gate + production-like releaseQa Gate SUCCESS

## QA1 — H1 真机反馈

H1 未通过，用户确认进入 QA2。QA1 不再作为最终 Stage 16A.1.5 candidate。

用户确认 QA2 仅做 presentation/resource 修订：

1. 扫描设备列表移出主页 feed，使用 modal bottom sheet；
2. 删除 QA1 新增固定强调色，恢复 App 原有 `MaterialTheme.colorScheme`；
3. 不缩字体，压缩设备状态卡无效垂直留白；
4. 不缩字体，压缩录音来源/模式 segmented selector 高度；
5. 波形恢复主题色；已有真实 PCM/audio-activity truth 的手机录音与 realtime 显示真实动态波形，普通录音卡录音没有 realtime PCM 时不伪造动态波形；
6. Active 卡左上移除重复时间，中央只保留一份 duration；恢复文件名与当前大小；
7. 使用用户重新上传的 Voica / QS668 品牌资产；
8. 删除 Idle 主卡无功能 `•••`。

## QA2 — H1 Refinement

当前 QA2 已实现的 presentation 变更：

- 新增 `HomeDeviceScanSheet`：compatible recorder 优先、其它蓝牙设备折叠、重扫；扫描/连接仍调用既有 ViewModel/BLE API；
- Home feed 不再直接插入扫描结果；
- source/mode selector 改回 `MaterialTheme.colorScheme.primary/onPrimary`，不再固定 QA1 青绿色；
- selector 视觉高度 46dp → 40dp，字体规格不变；
- 设备状态卡仅压缩 padding/spacing/设备图尺寸/分隔线高度，字体规格不变；
- `AppActivityFact` 增加 presentation-only `elapsedMs/mediaDisplayName/mediaSizeBytes/audioActivityLevels/waveformActive`；
- recorder recording 使用现有 `RecordingDeviceState.durationSeconds/filename/currentSizeBytes`；
- phone recording 使用现有 `PhoneMicrophoneRecordingSnapshot.durationMs/displayName/writtenSamples/finalizedSizeBytes` + controller `audioActivityLevels`；
- realtime 使用现有 `Stage16ARealtimeUiState.audioActivityLevels`；
- 手机录音与 realtime 有真实 levels 时复用 `RealtimeAudioActivityWaveform`；普通录音卡录音无 realtime PCM truth 时保留静态主题色 waveform；
- Active 卡左上不再从 subtitle 显示 duration；中央是唯一 duration；左上恢复 filename/current size；
- Idle 主卡无功能 `•••` 已删除；
- `VoicaBrandHeader` 与设备状态卡已切到新的 brand/device resource seam。

QA2 不修改任何业务 lifecycle。新增的 live-media 字段仅为可重建 presentation truth，不参与 controller/runtime 决策。

### QA2 自动化 Gate

- PR CI run `38843451207` 首次 Fast Job 因 GitHub Runner 下载 NDK `28.2.13676358` ZIP 损坏失败：`Error reading Zip content from a SeekableByteChannel`；Unit/Debug 未执行，判定基础设施失败；
- 同 run 失败 Job 重跑后 Android/NDK toolchain 安装 SUCCESS；
- 重跑 Fast Job `116788919388`：repository contracts / Unit Tests / Debug compile 全部 SUCCESS。

## 静态范围审计

Stage 16A.1.5 实现确认没有修改：

- Room schema / migration
- production model channel
- AudioRecord/capture runtime
- BLE protocol/transport
- ASR engine/model pipeline
- transcription/diarization/AI Summary coordinator business lifecycle
- canonical coordinator business lifecycle
- playback controller/runtime lifecycle
- model install WorkManager/UIDT ownership

QA2 相对 QA1 的 diff 限定在：首页 Compose/presentation、presentation model fields、首页 projection adapter、drawable resource seam、相关单测和本 Progress 文档。

## 下一 Gate

1. 完成 QA2 品牌资源最终收口；
2. 固定 QA2 candidate SHA；
3. PR #30 更新为 `[APK]` QA candidate；
4. 触发 Fast PR Gate + production-like `releaseQa` APK Gate；
5. 两个 Gate 同 SHA SUCCESS 后生成新的 QA2 APK；
6. 从 H1 重新开始真机验收；
7. 真机严格一次一项；CI PASS 不代替真机 PASS；
8. H1–H20 完成/明确 waiver 后，才进入 Freeze/Handoff/merge。

## 真机 QA 清单

- H1 Idle Homepage
- H2 recorder recording
- H3 phone recording
- H4 phone realtime
- H5 recorder realtime
- H6 simultaneous phone + recorder
- H7 Mini Player
- H8 offline transcription background
- H9 diarization background
- H10 AI Summary background
- H11 canonical generation
- H12 model install
- H13 completion attention → view → clear
- H14 failed/interrupted attention
- H15 notification deep links
- H16 POST_NOTIFICATIONS denied
- H17 Home/background/screen-off notification lifecycle
- H18 process kill/restart recovery
- H19 notification swipe dismiss
- H20 multiple background tasks / aggregation

Stage 16A.1 S15 remains `NOT RUN / WAIVED BY USER` and is not reopened by this presentation-only stage.
