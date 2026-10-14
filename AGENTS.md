# Voica 开发规则

## 一、项目唯一事实来源

当前 `ioannes78/voica-android` 仓库是 Voica 项目实现状态的唯一事实来源。

当前已冻结基线：**Stage 16A — 本地 True Streaming ASR**。

Stage 16A Final Freeze/Handoff：

- `docs/STAGE_16A_FREEZE.md`
- `docs/STAGE_16A_HANDOFF.md`

上一阶段 Stage 15 Final Freeze/Handoff：

- `docs/STAGE_15_FREEZE.md`
- `docs/STAGE_15_HANDOFF.md`

当前下一正式产品阶段：**Stage 16A.1 — Phone Microphone Capture / 手机麦克风录音转写**。

权威规划：

- `docs/STAGE_16A_1_PHONE_MICROPHONE_PLAN.md`
- `docs/STAGE_16A_2_TIMELINE_ANNOTATION_PLAN.md`
- `docs/ROADMAP_STAGE_16_18_STREAMING_AMENDMENT.md`
- `docs/ROADMAP_STAGE_16A_PRODUCT_AMENDMENT.md`

Stage 16A.1 开始任何生产代码修改前，必须重新核验当前 `main`、open PR、最新 CI、version、Room schema/migration、production model pin、Stage 16A Freeze/Handoff 与当前源码；不得把本文件中的历史 SHA 当成当前事实。

协议与行为参考：

- QS668 / CB08 真机可重复验证结果：最高优先级
- 官方 Android App `声云语音转写 3.0.9-u` 静态实现：协议/行为主参考，冲突时真机优先
- `nextproto1024/ai-recorder-card-open-protocol@e741ea72207f1a2aae3df4debc5c135728e0170e`
- `laidely/kardo@bcec3c5fdbcb34810a6f235e8b5873683f2ab951`：仅产品行为交叉验证

## 二、与 voice-card-android 完全隔离

硬性规则：

**Voica 不继承、不复制、不迁移、不 cherry-pick、不机械翻译 `voice-card-android` 的任何代码。**

禁止复制或改写其：

- Kotlin/Java/C/C++ 源代码
- 单元测试、Instrumentation 测试
- Gradle 脚本和模块实现
- Android 资源文件
- Room 数据库实体、DAO、Migration 实现
- BLE、音频、ASR、说话人、云端等模块实现
- Stage 1–30 的功能代码
- 可执行实现型配置或脚本

允许把过去已经发生的真机观察事实作为交叉验证线索，但 Voica 必须独立实现并重新测试。

## 三、语言规范

- App 默认界面语言：简体中文
- README、需求、架构、路线图、测试说明、Freeze/Handoff：简体中文
- 用户可见错误信息和状态信息：默认简体中文
- Kotlin 标识符、模块名、协议常量、API 字段：英文技术命名
- 必要英文术语可直接使用，例如 BLE、ASR、Room、Flow

## 四、阶段开发门禁

每个 Stage 开始前必须：

1. 阅读本文件。
2. 阅读当前 ROADMAP、适用的增量 ROADMAP、ARCHITECTURE、协议说明和上一阶段 Freeze/Handoff。
3. 检查 GitHub 当前真实代码，不依赖聊天记忆。
4. 输出该 Stage 的“修订需求”。
5. 等待用户明确确认。
6. 输出“修订开发规划”。
7. 再次等待用户明确确认。
8. 只有确认后才允许创建开发分支和编码。

每个 Stage 结束前必须：

1. 执行适用的 build、unit test、lint/static check。
2. 有用户界面的阶段提供可真机测试 APK。
3. 记录准确 commit SHA、CI/build 证据、已知风险和真机测试清单。
4. 用户明确“测试通过”后才创建 Freeze/Handoff 并合并。
5. CI 成功不能替代真机验收。
6. 未执行的测试只能记录为 `WAIVED BY USER` / `NOT RUN`，不得伪写为 PASS。

## 五、Android 技术基线

当前 Stage 16A Final Freeze 技术基线：

- Kotlin：2.4.20
- Android Gradle Plugin：9.4.0
- Gradle：9.6
- JDK：17
- compileSdk：37.1
- targetSdk：37
- minSdk：26
- Compose BOM：2026.09.00
- Application ID：`io.github.ioannes78.voica`
- QA Application ID：`io.github.ioannes78.voica.qa`
- versionCode：91
- Release versionName：`1.1.0-stage16a`
- ReleaseQa versionName：`1.1.0-stage16a-export-qa`
- sherpa-onnx：1.13.8
- Room schema：13
- migration lineage：1..13，Stage 16A 为 additive 12→13
- ABI：arm64-v8a
- 默认产品语言：简体中文
- production certificate SHA-256：`f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`
- production model channel commit：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifestVersion：7
- production manifestDigest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

Stage 16A.1 如需提升 versionCode/versionName、Room schema 或 model channel，必须作为新阶段明确需求重新确认，不得借 Stage 16A Freeze 静默推进。

当前主要物理模块：

- `:app`
- `:core:protocol`
- `:core:ble`
- `:core:database`
- `:core:audio`
- `:core:model`
- `:core:transcript`
- `:core:ai`
- `:engine:opus`
- `:engine:media`
- `:engine:playback`
- `:engine:sherpa`
- `:engine:llm`

## 六、Stage 16A 已冻结 realtime 事实

### 6.1 Stage 15 canonical seam

QS668 / CB08 realtime 上层唯一输入：

`RealtimeCanonicalAudioSource → RealtimeCanonicalStreamEvent`

Canonical audio 固定为 PCM16 / 16 kHz / mono，媒体时间以 session / epoch / sample truth 为准。

Stage 16A 及后续不得：

- 从 BLE/GATT/Opus 绕过 Stage 15 canonical seam；
- 用 wall clock / protocol SEQ 伪造 media timeline；
- 在 discontinuity / reconnect / pause boundary 后静默拼接 recognizer continuity；
- 为缺失媒体补 silence 制造伪连续。

### 6.2 Local true streaming pipeline

冻结主链：

`Canonical source → StreamingAudioBuffer → StreamingChunkAssembler → Streaming VAD → Local Streaming ASR → PARTIAL/STABLE/FINAL → punctuation → durable FINAL + live UI`

- chunk：1280 samples / 80 ms
- Stage 16A buffer：2.0 s bounded
- overflow：显式 discontinuity + bounded recovery，不反压 BLE/decoder
- PARTIAL/STABLE 不持久化冒充 FINAL
- active session 不热切模型；设置修改只对下一 realtime session 生效

正式 realtime 模型：

- Small Bilingual Zipformer zh-en INT8 — default/light
- Chinese Large Zipformer CTC INT8 — high-quality Chinese

### 6.3 Durable realtime / media

Room v13 已冻结 realtime run / media segment / FINAL utterance / timeline marker。

- clean single-segment realtime 可形成 verified local canonical WAV
- reconnect multi-segment 不得伪拼完整 canonical；使用 `SEGMENTED_CAPTURE`
- terminal 后完整设备 OPUS fallback 成功后进入既有 device media/canonical pipeline
- fallback 失败保留明确 remote/pending，不制造 0B 假本地文件
- normal single-segment 原始 OPUS 按需下载
- realtime transcription 与 file ASR transcription version 独立共存

### 6.4 Lifecycle / QA

Stage 16A S1–S12 最终均已通过真机闭环，包括 Pause/Resume、TYPE3 SAVE、second START、App reconnect、Bluetooth OFF/ON、background、screen-off、model missing、active-session model pin、artificial slowdown。

Large CTC 30 min 真机稳定性 PASS。

未执行长时测试必须保留为用户豁免：

- Small 30 / 60 / 120 min — WAIVED BY USER
- Large 60 / 120 min — WAIVED BY USER

不得改写成 PASS。

详细权威事实：

- `docs/STAGE_16A_FREEZE.md`
- `docs/STAGE_16A_HANDOFF.md`

## 七、Stage 16A.1 手机麦克风阶段边界

Stage 16A.1 目标：在没有录音卡时，通过 Android microphone / AudioRecord 产生 canonical PCM，并复用 Stage 16A streaming ASR 上层。

硬约束：

- 不建立第二套 ASR / stabilizer / punctuation / realtime UI 管线；
- 手机麦克风模式必须保存正式本地录音，不允许只送 ASR 不落音频；
- 录音卡与手机麦克风 audio source 必须显式区分；
- 默认不得同时隐式采集两个音频源；
- RECORD_AUDIO、microphone FGS、Audio Focus、route/device change、来电/其它 App microphone contention、permission revoke、background/screen-off、process recovery 必须重新设计并真机 QA；
- Stage 16A.1 自己的长时 Gate 不自动继承 Stage 16A waiver。

在用户确认 Stage 16A.1 修订需求与开发规划前，不得修改其 production code。

## 八、Stage 13A–16A 历史冻结优先级

从 Stage 13C 起的后续权威增量规划：

- `docs/ROADMAP_STAGE_13C_PLUS.md`
- `docs/ROADMAP_STAGE_16_18_STREAMING_AMENDMENT.md`
- `docs/ROADMAP_STAGE_16A_PRODUCT_AMENDMENT.md`

Stage 14 / Post-V1 P0 / Stage 15 / Stage 16A 的 Final Freeze/Handoff 均属于后续接管必须读取的历史冻结事实。

如旧 `docs/ROADMAP.md`、旧 AGENTS 历史描述或聊天记录与更新后的 Final Freeze/Handoff / 当前 GitHub 代码冲突，以当前仓库和最新阶段 Freeze/Handoff 为准。

## 九、生产发布与 CI 约束

Stage 16A Final Candidate 已通过：

- Android PR CI #1455 / run `38598067323`
- Android Production Gate #315 / run `38598067317`
- production signing / APK / AAB / R8 / security / Sherpa / Room verification

Stage 16A Final source HEAD、artifact hashes 与 waiver 明细读取 `docs/STAGE_16A_FREEZE.md`。

后续 release provenance 必须从真实 Room schema 自动生成，不得重新硬编码历史 schema 版本。

## 十、禁止把 QA/诊断变成产品事实

诊断数据用于回归与定位，不得：

- 引入第二 canonical consumer；
- 因采样/诊断反压实时媒体；
- 把 QA artificial slowdown 暴露给 production 用户；
- 把内部 model id / exception 文案直接显示给普通用户；
- 用 diagnostics snapshot 替代 Room / media lifecycle 的 durable truth。

## 十一、默认下一步

Stage 16A merge `main` 后，默认下一步只做：

`Stage 16A.1 修订需求 → 用户确认 → Stage 16A.1 开发规划 → 用户确认 → 创建分支/编码`

不得跳过需求/规划确认直接开发。
