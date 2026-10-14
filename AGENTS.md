# Voica 开发规则

## 一、项目唯一事实来源

当前 `ioannes78/voica-android` 仓库是 Voica 项目实现状态的唯一事实来源。

当前已冻结基线：**Stage 14 — Voica V1.0**

Stage 14 Final Freeze/Handoff：

- `docs/STAGE_14_FREEZE.md`
- `docs/STAGE_14_HANDOFF.md`

上一阶段 Stage 13C Final Freeze/Handoff：

- `docs/STAGE_13C_FREEZE.md`
- `docs/STAGE_13C_HANDOFF.md`

当前下一开发阶段：**Post-V1 P0 — Canonical Audio Performance & UX**。

当前执行顺序固定为：

`Stage 14 V1.0（已冻结） → Post-V1 P0 → Stage 15`

Post-V1 P0 完成开发、真机验收、Freeze/Handoff 并合并 `main` 后，才进入 **Stage 15 — BLE 实时音频链路**。

Stage 14 已完成 V1.0 Release Freeze、production signing、30/60/120 分钟真实稳定性与最终 `1.0.0` 真机冒烟；任何 Post-V1 P0 或 Stage 15 接管都必须重新读取 GitHub 当前 `main`、HEAD、CI、Room schema、Stage 14 Final Freeze/Handoff、Post-V1 P0 权威规划与 production model channel，不能把本文记录的某个历史 SHA 当成当前状态。

从 **Stage 13C** 开始的后续权威增量规划：

- `docs/ROADMAP_STAGE_13C_PLUS.md`

Post-V1 Canonical WAV 性能 / 进度 UI 优化规划：

- `docs/POST_V1_CANONICAL_AUDIO_OPTIMIZATION_PLAN.md`

如该增量路线图与旧 `docs/ROADMAP.md` 的 Stage 13C 以后编号/边界发生冲突，从 Stage 13C 起以该增量路线图和后续实际 Freeze/Handoff 为准。

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

## 五、Android 技术基线

当前 Stage 14 Final Freeze / V1.0 技术基线：

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
- versionCode：88
- Release versionName：`1.0.0`
- ReleaseQa versionName：`1.0.0-export-qa`
- sherpa-onnx：1.13.8
- Room schema：12
- ABI：arm64-v8a
- 默认产品语言：简体中文
- production certificate SHA-256：`f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`

Stage 15 后续如需提升 versionCode/versionName 或 Room schema，不得据此覆盖 Stage 14 V1.0 冻结事实；接管时必须重新读取当前 `main` 的真实配置。

当前物理模块：

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

依赖主方向：

```
app
├─ core:ble → core:protocol
├─ core:database
├─ core:audio
├─ core:model
├─ core:transcript
├─ engine:opus → core:audio
├─ engine:playback → core:audio
└─ engine:sherpa → core:model / core:transcript
```

## 六、Stage 12B 已冻结产品与数据事实

后续 Stage 不得无明确需求和迁移设计改变：

- Room schema 已由 Stage 12C 从 v5 additive migration 到 v6、Stage 13A 继续到 v7、Stage 13B 继续到 v11、Stage 13C 继续到 v12；Stage 14 继续冻结 v12，后续必须保持完整 1..12 migration lineage
- Recording metadata 搜索/排序/筛选、收藏、逻辑文件夹、标签均复用现有 Recording Library，不建立第二套录音库
- LOCAL_IMPORT + IMPORTED_ORIGINAL 保存导入 provenance；外部 content URI 不作为长期唯一音频事实
- 手机导入支持 WAV / MP3 / M4A-AAC / ADTS AAC / FLAC / Ogg Opus；QS668 raw framed Opus 继续走专用链
- Canonical WAV 是可再生成标准处理音频；原始导入/设备资产不得被 canonical cleanup 静默删除
- 本地删除必须通过 lifecycle coordinator；不得联动 BLE 远端删除
- 导出/分享保持真实 MIME；QS668 raw Opus 不伪装标准 Ogg Opus
- 全局 Mini Player 播放完成隐藏、暂停保留
- 全局转写/说话人/AI Summary 状态在对应详情页不重复；Stage 13B 后 terminal attention / Candidate 通知以 Room durable state 为事实来源
- AI Summary 历史版本显示实际 Provider/Model lineage
- 每次 AI Summary 可临时选择 Provider/Model，不能修改全局默认
- Structured Output Reliability 使用 strict schema 优先 + 有界 fallback/repair；不得降低 evidence validation
- Stage 12C/13A/13B/13C/14 已冻结：人工编辑不得直接覆盖原始 ASR / AI Summary 模型结果

## 六-A、Stage 12C 已冻结内容管理与搜索事实

后续 Stage 不得无明确需求和 migration 设计改变：

- Stage 12C 原始冻结 Room schema = 6，Stage 13A 继续演进到 v7，Stage 13B 最终演进到 v11，Stage 13C 最终演进到 v12；Stage 14 保持 v12，后续不得破坏 1..12 migration lineage。
- 原始 ASR Transcription / Segment / Token / speaker/alignment / absolute canonical sample timeline 保持不可变。
- 人工转写编辑保存为独立 TranscriptionRevision 全量快照；支持段落合并/拆分/整理、历史切换、删除与恢复模型原文。
- 人工修改文本不得伪造 token timestamp；没有可靠 token 边界时只保留来源 sample range。
- 阅读模式与 Stage 10 时间轴模式分离；阅读模式不显示时间戳/逐词高亮，时间轴继续复用原始 sample truth。
- 单个 Transcription version 可删除；若仍被 AI Summary 引用必须阻止删除，不得利用 Room cascade 静默删除 Summary。
- AI Summary 人工编辑保存为独立 AiSummaryRevision；原 structured payload / Provider / Model / Template / input lineage / Evidence 保持不可变。
- USER_EDITED / USER_ADDED 必须与 AI 原始 Evidence 明确区分；人工新增内容不得伪造模型 Evidence。
- RecordingContentSelection 持久化当前 Transcription / AiSummary 已完成版本；重启恢复，删除当前版本后安全回退。
- 文本复制、Android Sharesheet、TXT / Markdown 导出使用当前有效内容；Android 10+ 写入 Downloads/Voica。
- 统一搜索索引是可重建派生数据，不是内容事实来源。
- 统一搜索覆盖 Recording / Folder / Tag / 当前有效 Transcription 文本 / 当前有效 AI Summary 内容。
- 中文搜索使用应用侧 CJK normalization/tokenization + Room FTS4 unicode61；用户输入不得直接作为原始 MATCH 语法。
- 搜索索引在 v6 migration 后标记 REBUILD_REQUIRED，由 SearchIndexRebuilder 启动重建，并在内容生命周期变化时增量刷新。
- 搜索命中可定向打开 Recording / Transcription version / Summary version；Folder / Tag 命中回到录音库筛选。
- Stage 12C 不包含 PDF/DOCX 验收、semantic/vector search、Audio LLM、云同步、后台 BLE/FGS 或 speaker voiceprint。
- QA versionCode 40 / versionName `0.12.4-stage12c-qa1` 已真机验收通过；当前主线冻结事实已由 Stage 14 的 versionCode 88 / Room v12 继续覆盖。

## 六-B、Stage 13A/13B/13C/14 路线门禁

当前顺序为：

`Stage 13A → Stage 13B → Stage 13C → Stage 14 → Stage 15`

- Stage 13A：已完成并 Final Freeze/Handoff；本地 ASR / Diarization 模型矩阵、统一 capability、参数、内容生命周期已冻结。
- Stage 13B：已完成真机验收并 Final Freeze/Handoff；后台执行、Durable Model Install、长任务恢复、Room durable attention / Candidate lifecycle、Android 通知与播放通知 Final Gate 已冻结。权威基线读取 `docs/STAGE_13B_FREEZE.md` 与 `docs/STAGE_13B_HANDOFF.md`。
- Stage 13C：已完成 C0–C8、Final Candidate 真机验收并 Final Freeze/Handoff。权威基线读取 `docs/STAGE_13C_FREEZE.md` 与 `docs/STAGE_13C_HANDOFF.md`。
- Stage 13C 正式产品说话人链继续为：`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering/stitching → transcript alignment`。
- Stage 13C 已冻结 persisted VAD reuse、文件级 `AUTO / 1 / 2 / 3 / 4 / 5+`、显式 1 人 Fast Path、offline ASR 30 s consumer safety partition、ASR/diarization 生命周期解耦与 durable speaker attention。
- Stage 13C 生产 diarization window/overlap 继续为 60 s / 10 s；C9 Fast Diarization 未启用。
- Stage 14：已完成 V1.0 Release Freeze / production signing / security / R8 / model pin / 30/60/120 分钟稳定性 / Final 1.0.0 真机冒烟，并 Final Freeze/Handoff。
- Stage 15：当前下一开发阶段，只负责 BLE realtime Audio → Opus → PCM 与实时媒体时间链，不提前实现完整 streaming ASR 产品 UI。

Stage 13C+ 详细规划统一读取：`docs/ROADMAP_STAGE_13C_PLUS.md`。

## 六-C、Stage 13A 最终模型优先级与 Stage 16–20 路线

### Stage 13A 当前正式产品模型事实

从 Stage 13A Final Freeze 起，下列事实优先于 Stage 8/9 历史冻结文档中的旧模型选择：

文件离线 ASR：

- SenseVoice INT8：快速 / 默认
- Qwen3-ASR 0.6B INT8：高质量
- FireRedASR2：不进入产品矩阵

未来 true streaming ASR：

- Small Bilingual Zipformer INT8：轻量 / 默认
- Chinese Large CTC INT8：高质量
- Chinese Large Transducer：不进入产品矩阵
- 不提供 AUTO 产品路由

说话人：

- Pyannote Segmentation 3.0 INT8 + CAM++
- CAM++ 为当前产品 embedding 模型
- ERes2Net 不再是当前产品 embedding 选择

其它：

- Silero VAD
- CT-Transformer zh-en punctuation

### Post-V1 P0 / Stage 15–20 顺序

- Post-V1 P0：Stage 14 V1.0 之后立即执行，专项优化 Canonical WAV 生成性能、真实生成进度与取消交互；独立于 Stage 15，不修改 BLE realtime 边界。规划目标版本为 `1.0.1` / versionCode `89`，开发开始时必须重新核验并最终确认。
- Stage 15：只负责 BLE realtime Audio → Opus → PCM 与实时媒体时间链。
- Stage 16A：本地 true streaming ASR，必须遵循 `docs/STAGE_16_STREAMING_CONTRACT_V2.md`。
- Stage 16B：本地文件 ASR V2，重点 benchmark SenseVoice INT8 / Fun-ASR-Nano INT8 / Qwen3-ASR 0.6B INT8；最终保留模型数量由真机 benchmark 决定，不为模型数量而堆叠。
- Stage 17：云端文件 ASR / 音频上传转写，多 Provider。
- Stage 18：云端实时 ASR，多 Provider。
- Stage 19A：离线 AI 总结，保留 Gemma 4 E2B + E4B；E4B 默认高质量，E2B 轻量 fallback；Thinking Budget 作为高级设置，开发时重新核验 runtime 直接配置能力。
- Stage 19B：离线 Audio LLM，Gemma 4 E2B 默认、E4B 高质量；Audio LLM 不替代专业 ASR。
- Stage 19C：云端 Audio LLM + 高级 AI / semantic search / cross-recording understanding。
- Stage 19D：全部主要核心功能完成后的全 App UI/UX 最终精修，不新增大规模核心功能。
- Stage 20：Voica V2，包括云同步、账号、多设备、Speaker Voiceprint、跨录音 Speaker、Web/PC、团队协作。

### Provider capability 规则

从 Stage 17 起，同一 Provider Profile 可以共享 credential / region / base metadata，但能力适配必须拆分：

- Text LLM
- File ASR
- Realtime ASR
- Audio LLM

模型列表、连接测试、协议、参数和 capability availability 必须分别处理；不得把不同能力伪装成同一个接口。

API Key 继续使用 Android Keystore + encrypted app-private credential store，不进入 Room 内容 lineage。

### Gemma 4 / Thinking 规则

Stage 19A/19B 当前规划保留两个本地模型：Gemma 4 E2B 与 E4B。

若当时 LiteRT-LM 仍支持 `ThinkingConfig(enableThinking, thinkingTokenBudget)`，高级设置可提供：自动 / 关闭 / 256 / 512 / 1024 / 2048 / 4096 / 自定义安全范围；建议 benchmark 起点为 E2B=512、E4B=1024。

thinking budget 必须与 max output token 联动，必须为最终 Summary 预留足够输出 token。

Stage 19A 开发时必须重新核验 Thinking + constrained JSON/JSON Schema 的兼容性；若仍不稳定，使用 Thinking ON 的分析阶段 + Thinking OFF 的严格结构化阶段，不得降低 Stage 11 evidence/schema validation。

Stage 11 对部分云 Provider structured summary 默认关闭 thinking/reasoning 的历史规则，只针对当时云端兼容性，不得机械套用为 Gemma 4 离线模型的永久禁用规则。

## 六-D、Stage 13B 最终冻结事实

Stage 13B Final Freeze/Handoff 之后，后续阶段必须保护以下事实：

- 最终功能 / 真机验收二进制基线：`458b2e83e99b573bf1104d94f90c282b0d01baa3`；versionCode 64，Room v11，最终代码 CI #914 SUCCESS。
- Stage 13B.4 Durable Model Install 已冻结；进程恢复、已下载 artifact、安装状态与用户动作不得退化为纯 UI 状态。
- AI Summary `AMBIGUOUS_REMOTE_RESULT` 不得自动重发；手动重试必须复用原冻结 request snapshot。
- ordinary FAILED replacement 允许重新选择 Provider/Model/Template；取消选择器不得 resolve，只有新 durable row 创建后才 resolve 旧失败。
- Transcription 进程丢失 reconcile 为 durable `INTERRUPTED`；查看/通知 deep link 不得 resolve。
- Transcription 与 AI Summary 统一 Current / Candidate / History；Candidate dismiss durable 且两类互相隔离。
- Candidate 完成同时投影到 App 内与 Android 系统通知；当前对应详情页只隐藏重复 App 全局条，不得修改 Room durable state。
- stale AI Summary ignore 使用 lineage fingerprint，跨重启保持；lineage 改变可再次提示。
- 播放器关闭必须同步清除 detached 系统播放通知；MediaSession 用户可见错误必须是简体中文，不得暴露内部 enum。
- Room migration 必须保持 v1→v11；schema 必须由真实 Room/KSP 生成，不得手工伪造。
- CI 完整验证当前显式使用 `--no-configuration-cache`，原因是 CI #900 已证明恢复的 Configuration Cache 可能引用缺失 dependency/transform artifact；无新证据不得擅自移除。
- production model channel 在 Stage 13B 未提升；任何 promotion 继续需要独立核验与授权。

Stage 13C 开始前必须重新读取 `docs/STAGE_13B_FREEZE.md`、`docs/STAGE_13B_HANDOFF.md` 与 `docs/ROADMAP_STAGE_13C_PLUS.md`。

## 六-E、Stage 13C 最终冻结事实

Stage 13C Final Freeze/Handoff 之后，后续阶段必须保护以下事实：

- 最终功能 / 真机验收二进制基线：`2afa6b80e3add368c8c7768e6aac4d571c9fddd0`；versionCode 75，Room v12，最终代码 CI #1033 SUCCESS。
- 正式 diarization 链继续为 Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering/stitching → transcript alignment；生产 window/overlap 固定 60 s / 10 s。
- persisted VAD reuse 必须 lineage-safe；不得复用不兼容 canonical/model/config 的 speech truth。
- 文件级说话人数为 `AUTO / 1 / 2 / 3 / 4 / 5+`；同人数允许显式重跑；1 人 Fast Path 仅在用户明确选择 1 人时跳过多人重型链。
- 重新转写保持 `REUSE_ONLY`；有兼容 diarization 时只 realign，无兼容结果时不得静默新跑重型 diarization。
- offline ASR 对 >30 s 连续 speech 仅做 consumer 内部安全分块，原 VAD segment 与 canonical timeline 不变。
- ASR COMPLETED 后正文必须立即可用；diarization/alignment 仅做同一 Transcription 的 speaker enrichment，不得阻塞/替换正文。
- Transcription / Diarization / AI Summary 普通成功各自保留 Android + App 完成通知；speaker FAILED / INTERRUPTED 使用 Room durable attention；用户主动 Cancelled 不是 failure attention。
- Room migration 必须保持 v1→v12；v12 diarization/alignment acknowledgement 不得退化为进程内状态。
- Stage 13C 临时 diarization profiling/exporter 已退出 normal runtime；不得恢复自动 `Downloads/Voica/Diagnostics` JSON 或 app-private profiling JSON。
- C2 exact-range embedding cache 已因真机 0 有效命中移除；C5 未实施；C9 Fast Diarization 未启用。
- production model channel 在 Stage 13C 未提升；任何 promotion 继续需要独立核验与授权。

Stage 14 历史接管要求记录在 `docs/STAGE_13C_FREEZE.md` / `docs/STAGE_13C_HANDOFF.md`；当前后续阶段以 Stage 14 Freeze/Handoff 为最新权威基线。

## 六-F、Stage 14 V1.0 最终冻结事实

Stage 14 Final Freeze/Handoff 之后，后续阶段必须保护以下事实：

- 最终正式产品源 HEAD：`3ecde11b3bafff438b76e691dd812457ed8ddee3`；versionCode 88；Release versionName `1.0.0`；Room v12；ABI arm64-v8a。
- 最终正式签名 APK SHA-256：`62c62d4ad93cef3b58a5443181f16d984e397b94785286136e01e163f9f125fe`；AAB SHA-256：`a1ac71f07ee129c51ca267492275e6991818441a28a4093b60735f4893c186c1`。
- production certificate SHA-256：`f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`；public QA signer 不得用于 production。
- Android PR CI #1162 与 Android Full Release Gate #22 均 SUCCESS；production signing / APK-AAB signature / R8 / security / Sherpa / Room / provenance gate 全部通过。
- RC → Final Version Gate 严格限制产品行为源不漂移；最终只把 versionCode 87→88、versionName `1.0.0-rc3-r2`→`1.0.0`。
- production model channel 继续 pin 到 `be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`，manifestVersion=7，manifestDigest=`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`。
- Stage 14.7B 已完成真实 30:48 / 64:27 / 121:07 稳定性门禁；三组均 PASS，无 crash/ANR/hang/reported OOM，后台/息屏、播放、搜索、导出、结果持久化正常。
- RAM/PSS、CPU、thermal、storage quantitative telemetry 在 14.7B 未 instrumentation，继续记录为 N/A，不得伪造成已完成定量测试。
- 用户已对最终 production-signed `1.0.0` 明确确认“Final 1.0.0 冒烟测试通过”。
- R8/resource shrink、backup disabled、cleartext disabled、FileProvider/credential 隔离、Room v12、Stage 13B/13C durable lifecycle 均为 V1.0 冻结边界。
- Canonical WAV 生成速度与生成 UI 优化已记录在 `docs/POST_V1_CANONICAL_AUDIO_OPTIMIZATION_PLAN.md`，属于 Post-V1 独立 P0 规划，不得未经确认静默混入 Stage 15。

Stage 15 开始前必须重新读取 `docs/STAGE_14_FREEZE.md`、`docs/STAGE_14_HANDOFF.md`、`docs/ROADMAP_STAGE_13C_PLUS.md`、`docs/STAGE_16_STREAMING_CONTRACT_V2.md`，并重新核验 `main` / CI / Room / version / production model channel。

## 六、Stage 2 已冻结 BLE 事实

后续 Stage 不得无证据改变：

- AE20：Service
- AE21：WRITE_NO_RESPONSE
- AE22：Notify
- AE23：Notify
- AE22 与 AE23 使用**独立 FrameParser**
- 两路完整 `ProtocolFrame` 均可提交给单路 pending matcher
- pending matcher 强匹配 TYPE/CMD；sequence 当前仅用于诊断
- 真机观察到设备响应 sequence 不保证回显 request sequence
- Battery Request：0/3
- Battery Response：0/4
- Battery Response 可以从 **AE23** 到达
- Battery body[0] 0..100 为电量百分比，110 为充电中
- Android 主动 `requestMtu(517)`
- 真机 Actual MTU = 517
- MTU <39 不得进入 Ready
- MTU 39..170 仅基础控制能力
- MTU >=171 标记当前已知 168B 数据通道能力
- TYPE=2/CMD=2 的 36B Frame 后续必须一次完整 GATT Write，不得应用层拆分
- GATT 异步操作必须严格串行
- `BluetoothGatt STATE_CONNECTED` 不等于 Voica Ready
- Remote disconnect 有限重连：1s → 2s → 4s，最多 3 次
- 用户主动断开不得自动重连

## 七、Stage 3 已冻结录音事实

后续 Stage 不得无真机证据改变：

- App 卡录音控制：TYPE=3 CMD=2/4/6/8 + body `01`
- 设备物理按键事件：TYPE=3 CMD=1/3/5/7
- 物理事件需发送对应偶数 CMD+`01` acknowledgement，再 reconciliation
- App 主动录音控制写入后不等待 action response，以设备查询/语义证据收敛
- 当前固件 Pause 后 GET_STATE 可持续返回 1，因此 GET_STATE=1 不足以区分 Recording 与 Paused
- App Pause 写成功或物理 CMD=5 为 Paused 强语义证据
- App/物理 Resume 解除 Pause 锁存
- GET_STATE=2 可确认 Idle
- Recording 前台约 1 秒 GET_TIME Poller
- Full sync / 物理事件开始前必须停止 Poller
- Idle 不查询当前 GET_TIME / GET_FILENAME
- 辅助查询失败只进入 Diagnostics，不得伪装成录音操作失败
- Ready 后持久化最后成功设备地址并支持后续 App 自动连接
- 用户主动断开在本 App 进程内抑制自动连接
- Remote disconnect 继续沿用 Stage 2 的 1s → 2s → 4s 重连

## 八、Stage 4 已冻结文件列表事实

后续 Stage 不得无真机证据改变：

- 文件列表请求：TYPE=2/CMD=0
- 文件列表数据：TYPE=2/CMD=1，可多帧
- 文件列表正常完成：TYPE=2/CMD=18
- 正常成功必须收到 CMD=18；timeout 不得伪装成 Empty/Fresh
- 当前 QS668/CB08 真机 CMD=1 / CMD=18 均观察到从 AE22 到达，但业务代码仍按 TYPE/CMD 路由，不硬绑定来源
- 当前真机 CMD=18 body length = 1B；不得要求 DONE body 必须为空
- CMD=1 sequence / CMD=18 sequence 为设备侧序列，不要求回显 CMD=0 request sequence
- 列表 COUNT、duration、size 按 BE32
- 当前真机 filename field = 20B
- 官方 App 证明 filename field 可扩展；解析器必须继续支持动态长度并严格校验
- 标准 raw filename `noteYYYYMMDD-HHMMSS.` 可安全恢复为 `.opus`
- 不得为未知名称盲目补 `.opus`，不得自动生成 WAV variant
- 真机确认第一个 BE32 = duration seconds：11秒→11、24秒→24
- RemoteDeviceFile 必须保留 raw/resolved filename、size、duration、recordedAt 和稳定/临时 identity
- 多个 CMD=1 只能进入本轮 accumulator；CMD=18 到达后一次性发布 Fresh/Empty
- malformed/断线/session generation 变化不得发布 partial Fresh
- Recording/Paused/command transition 时禁止主动刷新文件列表，保护 Stage 3 Poller
- Stage 4 不发送 CMD=2/CMD=12 做文件业务
- 官方 App 已确认 CMD=12 是区间文件传输；Stage 5 才实现
- Stage 5 必须重新验证 2/2、2/12 filename 参数长度；当前旧 fixed-24 builder 不得直接视为冻结下载协议

## 九、Stage 5 已冻结文件传输与删除事实

后续 Stage 不得无真机证据改变：

- Ready 后自动请求设备文件列表；重连与录音完成后自动刷新，重复刷新合并
- 文件下载主链：TYPE=2/CMD=2 → CMD=3 → CMD=4×N → CMD=5
- 下载数据必须走专用可靠传输通道，不能依赖可丢包 SharedFlow
- DATA 必须流式写入 .part，完成后做 size 校验、SHA-256、fsync、原子提交
- 当前 QS668/CB08 下载得到的是设备原始 `.opus` 字节流，不是 RIFF/WAV
- 本地显示为“OPUS 原始流”；不在 Stage 5 主动转码 WAV
- CMD=12 真机范围语义为 `[start, end)`，即 end exclusive
- 设备单录音删除 CMD=8 使用 28B 参数：`00000000 + 完整 filename 固定 24B`
- raw file-list entry 作为 CMD=8 body 已被真机否定，不得恢复
- 成功删除设备逻辑录音会同时删除物理存储同名 `.opus + .wav`
- 删除设备录音与删除本地录音完全独立，不提供 DeleteBoth
- Stage 5 不提供 Delete All
- 破坏性删除必须二次确认；结果不确定时不得自动重发删除命令
- 本地录音使用 app-private / noBackupFilesDir 保存
- Stage 5 不引入 Room；本地 metadata 使用轻量原子文件保存
- Stage 5 不承诺后台/锁屏持续下载，不实现持久化断点续传

## 九-A、Stage 6 已冻结本地库与音频事实

后续 Stage 不得无新真机证据改变：

- Room `voica-recordings.db` 为本地录音运行时事实来源；Stage 5 properties 只作为 legacy import 输入。
- 一条设备录音对应一条逻辑 Recording，可含 DEVICE_OPUS / DEVICE_WAV / CANONICAL_WAV。
- 标准设备录音逻辑 displayName 默认不带 `.opus/.wav`；用户重命名不改变物理文件、remote identity 或 SHA。
- DEVICE_OPUS 与 DEVICE_WAV 必须独立验证；canonical source 选择不得决定另一资产的验证状态。
- Stage 4 `sizeBytes` 继续表示 OPUS 大小。
- WAV 大小使用同名 `.wav` CMD=12 `[0,44)` 读取 RIFF header，并按 little-endian `ChunkSize + 8` 得到真实总大小。
- OPUS / WAV 下载均显示真实 received / expected / percent；WAV 首包也可动态解析真实 expectedBytes。
- 不得恢复 `size % 40 == 0 → RAW_OPUS` 的启发式判断。
- official libopus 固定 1.6.1，source SHA-256 `6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1`。
- canonical 音频固定为 RIFF/WAVE、16 kHz、mono、PCM16_LE。
- 原始设备音频必须保留；派生 canonical 可重建。
- Stage 7 通过 `AudioSourceResolver`，Stage 8 通过 `PcmSourceResolver/PcmSource` 使用音频，不得绕过边界。
- Stage 6 不实现后台/锁屏持续下载；可靠后台下载在 Stage 13 使用 Foreground Service 实现。

## 九-B、Stage 7 已冻结播放器与时间轴事实

后续 Stage 不得无新证据改变：

- 只播放 verified CANONICAL_WAV；raw OPUS/DEVICE_WAV 不直接作为播放主链。
- absolute canonical PCM sample index 是唯一媒体时间真值。
- canonical profile 固定 16000 Hz / mono / PCM16_LE / 2 bytes per frame。
- WAV seek 必须使用真实 pcmDataOffsetBytes，不得假定 44B header。
- 公开 position 使用 presented sample，而不是 source/submitted cursor。
- AudioTimestamp 优先，playback head fallback 必须处理 32-bit wrap。
- seek 使用 Long sample；rapid seek 必须 latest-wins。
- 六档倍速固定 0.5/0.75/1.0/1.25/1.5/2.0，pitch=1.0。
- Audio Focus LOSS pause 且不自动恢复；CAN_DUCK 在 Stage 7 选择 pause。
- App background pause；返回 foreground 不因 background 自动恢复。
- device Recording 或新鲜 START/RESUME hardware edge 必须暂停本地 playback；录音结束不得自动恢复。
- Stage 8 必须通过 PcmSourceResolver/PcmSource 复用 absolute sample timeline。
- Stage 10 必须通过 PlaybackSnapshot.positionSampleIndex / seekToSample / discontinuityGeneration 做文字同步。
- Room schema 仍为 version 1。
- 真实 30min/1h/2h、2h 综合稳定性与 meminfo 未在 Stage 7 真机执行，统一作为 Stage 13 测试债务。


## 九-C、Stage 8 已冻结本地 AI / 转写事实

后续 Stage 不得无新证据改变：

- sherpa-onnx Android runtime 固定为 1.13.8，Stage 8 目标 ABI 为 arm64-v8a。
- Room schema 已从 version 1 显式迁移到 version 2；转写、segment、token 使用独立表并保留历史版本。
- 本地转写统一使用 Stage 7 canonical PCM：16 kHz / mono / PCM16_LE / absolute canonical sample index。
- Silero VAD int8 为 APK 内置基线，sourceType 为 BUILTIN_WITH_OVERRIDE；远程 override 失败时仍可回退到内置基线。
- Stage 8 first-pass 基线使用 Small Bilingual Zipformer zh-en 2023-02-16；Stage 13A 已重新评估并冻结当前正式 ASR 矩阵，当前产品选择以本文件“六-C”与 Stage 13A Final Freeze/Handoff 为准。
- punctuation 使用 CT-Transformer zh-en int8 2024-04-12。
- High Quality second-pass 使用 SenseVoice zh-en-ja-ko-yue int8 2024-07-17。
- Fast pipeline：VAD → Small Bilingual → CT-Transformer → atomic persistence。
- High Quality pipeline：VAD → Small Bilingual first pass → SenseVoice second pass → CT-Transformer fallback/final punctuation → atomic persistence。
- 每次转写创建新的 Transcription 版本；FAST 与 HIGH_QUALITY 不覆盖彼此。
- “转写版本”UI 可列出并切换同一录音的所有已完成版本。
- 模型安装必须 package SHA/file SHA 校验、staging、atomic promotion、native smoke 后才能激活。
- 大模型下载支持 .part 保留与 HTTP Range 断点续传；用户主动取消会确定性删除 .part。
- package SHA、TAR.BZ2/ZIP 解包、installed-file SHA、native smoke 均不得阻塞 Compose 主线程。
- 正式/普通 App 固定读取 production model manifest；Debug/QA candidate override 仅用于未合并候选验收。
- Stage 8 production manifest 的历史四模型事实不能覆盖 Stage 13A 以后新的产品模型决策；任何当前 manifest 状态必须重新从 production model channel 核验。
- QA APK 使用固定测试签名与 `io.github.ioannes78.voica.qa`，仅用于真机验收，不得用于生产发布。
- 真实 30min/1h/2h 长录音压力在 Stage 13B 正式处理；Stage 8 已有 virtual 30/60/120min bounded-read 自动化覆盖。


## 九-D、Stage 9 已冻结说话人分离事实

以下记录保留 Stage 9 当时的历史实现事实；**从 Stage 13A Final Freeze 起，当前产品 speaker embedding 选择以 CAM++ 为准，ERes2Net 不再是当前产品模型。**

- sherpa-onnx Android runtime 继续固定 1.13.8；Stage 9 未引入第二套推理 runtime。
- Room schema 已从 v2 显式迁移到 v3；Stage 8 转写历史完整保留。
- Stage 9 历史 speaker pipeline 使用：Silero VAD → Pyannote Segmentation 3.0 INT8 → ERes2Net Base zh-CN 16 kHz → sherpa FastClustering。
- Stage 9 当时 production model manifest 曾包含：
  - `pyannote-segmentation-3-int8` / role=`DIARIZATION_SEGMENTATION`
  - `3dspeaker-eres2net-base-zh-cn-16k` / role=`EMBEDDING`
- 上述 manifest 条目只能描述 Stage 9 历史状态；当前 production manifest 必须重新核验，不能据历史文档猜测。
- Debug/QA 如果过去保存了 candidate override，可在设置中“恢复 production”后完全退出并重启。
- Pyannote 单模型 Kotlin API 不提供完整 standalone native session，因此其最终 native gate 是 segmentation + embedding bundle smoke；Stage 13A 当前 embedding 已切换为 CAM++ 后，应按当前实现重新核验 smoke 契约。
- 所有 speaker 时间继续使用 16 kHz canonical PCM absolute sample index；不得建立拼接 VAD 伪时间轴。
- 长录音按 bounded windows 处理；窗口读取保持顺序式，不要求 PcmSource random seek。
- cross-chunk speaker identity 必须使用当前冻结 embedding + overlap/temporal evidence；chunk-local speaker label 不得直接持久化为全局 label。
- Stage 9 不持久化 embedding vector / voiceprint / 跨录音全局人物身份；这些仍属于 Stage 20。
- Room v3 新增 diarization run / speaker / turn / transcript alignment / speaker span 数据，不向 Stage 8 TranscriptSegment 直接塞单一 speaker 字段。
- transcript speaker alignment 优先 timed SECOND_PASS token，fallback timed FIRST_PASS；跨 speaker 边界必须拆 span。
- overlap/ambiguous token 不得复制给多个 speaker；无法可靠归属时显式保存 unresolved / overlap ambiguous。
- 同一 completed diarization run 可复用给同一 canonical lineage 的多个 transcription version。
- **直接转写完成后默认可继续说话人分离并完成 Speaker 文本对齐；Stage 13C 已进一步冻结“正文已完成”和“speaker 后处理完成”的产品生命周期解耦，正文不得被 speaker 后处理阻塞。**
- 若同一 canonical lineage 已存在可复用 completed diarization run，则优先复用，只做必要 transcript/speaker alignment。
- “单独说话人分离”保留给补做、重跑和模型专项测试；查看旧历史转写本身不得强制新跑重型 diarization。
- 每次 diarization 重跑创建新的 run，不覆盖旧 run；Speaker 1/2/… 按本 run 首次全局出现顺序编号，局部重命名不跨 run 传播。
- active diarization/alignment 在进程启动 reconciliation 时转 INTERRUPTED；取消/失败不得留下假 COMPLETED。
- Stage 9 真机功能验收已通过；真实 30min/1h/2h 长录音、定量 RTF/PSS/thermal soak 由 Stage 13B/13C 继续完成，不得写成 Stage 9 已完成。


## 九-E、Stage 10 已冻结转写时间轴与播放同步事实

后续 Stage 不得无新证据改变：

- 唯一同步时间真值继续是 16 kHz canonical PCM 的 absolute sample index；不得改用 wall-clock/ms 作为主时间轴。
- Timeline row：存在 completed speaker alignment 时使用 `TranscriptSpeakerSpan`；否则使用 `TranscriptSegment`。
- 播放映射边界固定为半开区间 `[start, end)`；静音/gap 不伪造当前 speaker/token。
- FAST 使用 Small Bilingual Zipformer FIRST_PASS token timestamps；HQ 优先 timed SECOND_PASS，缺失时 fallback timed FIRST_PASS。
- “当前词”实际是 ASR token，不保证等同自然语言分词；不得为中文强行制造无依据词边界。
- punctuation 不拥有独立时间戳；标点字符随 finalText projection 归附邻近 timed token 显示。
- Stage 9 token → finalText projection 已抽为共享纯逻辑；只有 `EXACT` projection 默认允许深色 token 高亮。
- `HEURISTIC` / `UNAVAILABLE` projection 只保留 row/span 浅色高亮，宁可少高亮也不得伪造精确定位。
- token 缺显式 end 时，以后续 timed token start 或 owner row end 推导，并 clamp 到 owner range。
- 点击 row/span/segment → owner start sample；点击可靠 timed token → token start sample；最终统一走 `PlaybackController.seekToSample()`。
- 点击转写文字的产品行为为 seek + play；若目标 recording 尚未加载，则先 load，再 seek，再 play。
- rapid transcript click 使用 latest-wins；Stage 7 seekGeneration/discontinuityGeneration 继续作为 seek/discontinuity 事实。
- playback sync 必须验证 recording、canonical lineage/source asset 与 total sample count；旧 canonical asset 不得继续同步。
- FAST/HQ/同模式历史版本严格按 `transcriptionId` 绑定；切换版本保留当前 playback sample，不自动重播。
- speaker rename 只刷新当前 run-local 显示，不重新 diarization，不改变 playback sample。
- `ASSIGNED_WITH_OVERLAP`、`OVERLAP_AMBIGUOUS`、`UNRESOLVED` 必须保留显式语义，不强制伪造 speaker。
- 自动滚动只在 active row 变化时触发，不在每个约 50 ms token tick 触发。
- 用户手动滚动后进入 `USER_SUSPENDED`；高亮继续更新，但 viewport 不被播放强制抢回；点击“跟随播放”才恢复。
- Timeline 内容按版本一次性构建；播放高频 tick 只更新 active row/cue/follow state，不执行 Room IO、全文重建或 O(N) 扫描。
- 顺序播放使用前向 cursor；随机/反向 seek 使用二分定位。
- Room schema 保持 v3；Stage 10 仅增加按 transcriptionId 批量读取 token，不引入 migration。
- Stage 10 已通过 30/60/120 分钟 virtual timeline 自动化；这不等于真实 30min/1h/2h 真机 soak，后者继续属于 Stage 13B。
- Stage 10 真机功能验收已通过；不得把本次验收扩张为未执行的长录音 PSS/CPU/thermal 定量结论。

## 九-F、Stage 11 已冻结 AI Summary / Provider 事实

后续 Stage 不得无新证据改变：

- 默认 AI 总结链固定为 `selected Transcription → StructuredTranscriptInput → TextLlmProvider → validated AI Summary`。
- 普通“AI 总结”默认只上传转写文本与必要结构化 metadata，不上传原始录音。
- AI 生成内容不得反写成 ASR timing 事实；evidenceRef 必须可回链到真实 transcript/speaker source range。
- Room schema 已从 v3 additive migration 到 v4；AI Summary/template/evidence/checkpoint 持久化进入 Room，API Key 不进入 Room。
- API Key 使用 Android Keystore + AES-GCM；Provider Profile 支持多配置和默认 Provider。
- Text LLM Provider 与 ASR Provider 分离；Audio LLM 当时只冻结能力契约，后续完整本地直接音频理解规划到 Stage 19B，云端 Audio LLM 规划到 Stage 19C。
- Provider presets/contract 包括 OpenAI、Google Gemini/AI Studio、Google Vertex contract、xAI/Grok、DeepSeek、阿里云百炼、火山引擎/豆包、硅基流动、智谱 GLM、Kimi、OpenRouter、Custom OpenAI-compatible。
- Provider 支持时可自动获取模型；始终保留手动模型 ID fallback；大模型目录使用 searchable picker。
- 连接测试使用 synthetic content，不上传真实 transcript；已选模型时必须实际 probe 所选模型。
- native json_schema 可用时优先使用；json_object fallback 也必须把完整 Summary schema 明确提供给模型，再执行本地严格 schema/evidence validation。
- SiliconFlow/Volcengine structured summary 默认关闭 thinking/reasoning，避免推理过程耗尽最终 JSON 输出预算；该规则不等于未来所有本地 reasoning 模型永久禁用 Thinking。
- 单层 ```json code fence 仅作为兼容包装剥离，不得因此放宽 schema/evidence 安全规则。
- Prompt version 2；除非用户明确要求其他语言，人类可读 AI Summary 默认输出简体中文。
- 同一 Transcription 可生成多个 Summary version，不覆盖旧结果。
- 长文本使用 token budget + direct 或 hierarchical map/reduce；支持 Room checkpoint、cancel、interrupted 后继续。
- 进程重启后不得自动静默重发云端 LLM 请求。
- Stage 11 真机已验证 Grok 中文输出、火山 DeepSeek V4.1 Flash 与硅基流动 Qwen3-32B 的结构化总结修复。
- OpenRouter HTTP 429 属于 Provider rate-limit/quota 类状态，不得伪装成连接成功。

## 十、架构规则

- `:core:protocol` 保持纯 Kotlin，不依赖 Android BLE API。
- UI 不得直接解析二进制协议。
- BLE transport、协议、文件、音频、数据库、ASR、说话人、AI 保持边界。
- Session callback 必须具备 generation/stale callback 防护。
- 原始设备音频后续必须先可靠落盘，再转换。
- 删除等破坏性操作必须二次确认。
- ASR/LLM Provider 不得污染 Feature UI 契约。
- 下载、解码、转写、说话人分离等耗时任务必须支持取消与生命周期处理。
- 本地录音在设备断开后仍应可独立使用。

## 十一、测试规则

协议层至少覆盖 deterministic/golden tests：

- CRC-16/XMODEM：`123456789 -> 0x31C3`
- 帧构造与解析
- 通知拆包、粘包
- AE22/AE23 独立解析
- 文件列表字节序
- 36B 文件导入请求
- 时间同步
- Battery 0..100 / 110
- MTU 39 / 171 / 517 边界
- Serialized GATT Queue
- pending-before-write fast response
- AE23 Battery Response 回归

BLE、文件、音频、ASR、Speaker 必须继续做真实设备或真实录音验收。

## 十二、CI 原则

- PR 默认快速 Unit Test + assembleDebug
- 默认不运行 Emulator / Instrumentation
- 使用 concurrency / cancel-in-progress
- 真机 APK 仅在阶段验收需要时上传
- Stage 6 开始前不得为了方便扩大 Stage 5 的范围