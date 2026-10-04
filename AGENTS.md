# Voica 开发规则

## 一、项目唯一事实来源

当前 `ioannes78/voica-android` 仓库是 Voica 项目实现状态的唯一事实来源。

当前已冻结基线：**Stage 12C**

Stage 12C Freeze/Handoff：

- `docs/STAGE_12C_FREEZE.md`
- `docs/STAGE_12C_HANDOFF.md`
- `docs/STAGE_12C_TEST.md`

下一阶段：**Stage 13 — 稳定性与长录音专项**

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
2. 阅读当前 ROADMAP、ARCHITECTURE、协议说明和上一阶段 Freeze/Handoff。
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

当前技术基线：

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
- versionCode：40
- versionName：`0.12.4-stage12c-qa1`
- sherpa-onnx：1.13.8
- Room schema：6
- ABI：arm64-v8a
- 默认产品语言：简体中文

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

- Room schema 已由 Stage 12C 从 v5 additive migration 到 v6；后续必须保持 1..6 migration lineage
- Recording metadata 搜索/排序/筛选、收藏、逻辑文件夹、标签均复用现有 Recording Library，不建立第二套录音库
- LOCAL_IMPORT + IMPORTED_ORIGINAL 保存导入 provenance；外部 content URI 不作为长期唯一音频事实
- 手机导入支持 WAV / MP3 / M4A-AAC / ADTS AAC / FLAC / Ogg Opus；QS668 raw framed Opus 继续走专用链
- Canonical WAV 是可再生成标准处理音频；原始导入/设备资产不得被 canonical cleanup 静默删除
- 本地删除必须通过 lifecycle coordinator；不得联动 BLE 远端删除
- 导出/分享保持真实 MIME；QS668 raw Opus 不伪装标准 Ogg Opus
- 全局 Mini Player 播放完成隐藏、暂停保留
- 全局转写/说话人/AI Summary 状态在对应详情页不重复；Completed/Failed 作为未读任务通知直到用户查看
- AI Summary 历史版本显示实际 Provider/Model lineage
- 每次 AI Summary 可临时选择 Provider/Model，不能修改全局默认
- Structured Output Reliability 使用 strict schema 优先 + 有界 fallback/repair；不得降低 evidence validation
- Stage 12C 已冻结：人工编辑不得直接覆盖原始 ASR / AI Summary 模型结果

## 六-A、Stage 12C 已冻结内容管理与搜索事实

后续 Stage 不得无明确需求和 migration 设计改变：

- Room schema = 6，v5→v6 为 additive migration；CI 固定校验 schema 1..6。
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
- QA versionCode 40 / versionName `0.12.4-stage12c-qa1` 已真机验收通过。

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
- first-pass / future Stage 16 streaming ASR 使用 Small Bilingual Zipformer zh-en 2023-02-16。
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
- production manifest 当前包含 Silero、Small Bilingual、CT-Transformer、SenseVoice 四模型。
- QA APK 使用固定测试签名与 `io.github.ioannes78.voica.qa`，仅用于真机验收，不得用于生产发布。
- 真实 30min/1h/2h 长录音压力仍作为 Stage 13 测试债务；Stage 8 已有 virtual 30/60/120min bounded-read 自动化覆盖。


## 九-D、Stage 9 已冻结说话人分离事实

后续 Stage 不得无新证据改变：

- sherpa-onnx Android runtime 继续固定 1.13.8；Stage 9 未引入第二套推理 runtime。
- Room schema 已从 v2 显式迁移到 v3；Stage 8 转写历史完整保留。
- Stage 9 speaker pipeline 使用：Silero VAD → Pyannote Segmentation 3.0 INT8 → ERes2Net Base zh-CN 16 kHz → sherpa FastClustering。
- production model manifest 已正式包含：
  - `pyannote-segmentation-3-int8` / role=`DIARIZATION_SEGMENTATION`
  - `3dspeaker-eres2net-base-zh-cn-16k` / role=`EMBEDDING`
- 正常运行固定使用 production manifest；Stage 9 candidate URL 只属于未合并候选 QA，Stage 9 Freeze 后不再是运行依赖。
- Debug/QA 如果过去保存了 candidate override，可在设置中“恢复 production”后完全退出并重启。
- Pyannote 单模型 Kotlin API 不提供完整 standalone native session，因此其最终 native gate 是 Pyannote + ERes2Net bundle smoke；bundle smoke 必须在创建 DiarizationRun 前成功。
- ERes2Net 单模型激活必须真实创建 SpeakerEmbeddingExtractor 并得到 finite/non-zero embedding。
- 所有 speaker 时间继续使用 16 kHz canonical PCM absolute sample index；不得建立拼接 VAD 伪时间轴。
- 长录音按 bounded windows 处理；初始策略 60s chunk + 10s overlap，窗口读取保持顺序式，不要求 PcmSource random seek。
- cross-chunk speaker identity 使用 ERes2Net anchor embedding + overlap/temporal evidence；chunk-local speaker label 不得直接持久化为全局 label。
- Stage 9 不持久化 embedding vector / voiceprint / 跨录音全局人物身份；这些仍属于 Stage 20。
- Room v3 新增 diarization run / speaker / turn / transcript alignment / speaker span 数据，不向 Stage 8 TranscriptSegment 直接塞单一 speaker 字段。
- transcript speaker alignment 优先 timed SECOND_PASS token，fallback timed FIRST_PASS；跨 speaker 边界必须拆 span。
- overlap/ambiguous token 不得复制给多个 speaker；无法可靠归属时显式保存 unresolved / overlap ambiguous。
- 同一 completed diarization run 可复用给同一 canonical lineage 的多个 FAST/HIGH_QUALITY transcription version。
- **直接 FAST/HQ 转写完成后默认自动继续说话人分离并完成 Speaker 文本对齐；用户无需预先手动点击说话人分离。**
- 若同一 canonical lineage 已存在 completed diarization run，则直接复用，只做新的 transcript/speaker alignment。
- “单独说话人分离”保留给补做、重跑和模型专项测试；查看旧历史转写本身不得强制新跑重型 diarization。
- 每次 diarization 重跑创建新的 run，不覆盖旧 run；Speaker 1/2/… 按本 run 首次全局出现顺序编号，局部重命名不跨 run 传播。
- active diarization/alignment 在进程启动 reconciliation 时转 INTERRUPTED；取消/失败不得留下假 COMPLETED。
- Stage 9 真机功能验收已通过；真实 30min/1h/2h 长录音、定量 RTF/PSS/thermal soak 仍属于 Stage 13 测试债务，不得写成 Stage 9 已完成。


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
- Stage 10 已通过 30/60/120 分钟 virtual timeline 自动化；这不等于真实 30min/1h/2h 真机 soak，后者继续属于 Stage 13。
- Stage 10 真机功能验收已通过；不得把本次验收扩张为未执行的长录音 PSS/CPU/thermal 定量结论。

## 九-F、Stage 11 已冻结 AI Summary / Provider 事实

后续 Stage 不得无新证据改变：

- 默认 AI 总结链固定为 `selected Transcription → StructuredTranscriptInput → TextLlmProvider → validated AI Summary`。
- 普通“AI 总结”默认只上传转写文本与必要结构化 metadata，不上传原始录音。
- AI 生成内容不得反写成 ASR timing 事实；evidenceRef 必须可回链到真实 transcript/speaker source range。
- Room schema 已从 v3 additive migration 到 v4；AI Summary/template/evidence/checkpoint 持久化进入 Room，API Key 不进入 Room。
- API Key 使用 Android Keystore + AES-GCM；Provider Profile 支持多配置和默认 Provider。
- Text LLM Provider 与 ASR Provider 分离；Audio LLM 只冻结能力契约，完整直接音频理解仍属于 Stage 19。
- Provider presets/contract 包括 OpenAI、Google Gemini/AI Studio、Google Vertex contract、xAI/Grok、DeepSeek、阿里云百炼、火山引擎/豆包、硅基流动、智谱 GLM、Kimi、OpenRouter、Custom OpenAI-compatible。
- Provider 支持时可自动获取模型；始终保留手动模型 ID fallback；大模型目录使用 searchable picker。
- 连接测试使用 synthetic content，不上传真实 transcript；已选模型时必须实际 probe 所选模型。
- native json_schema 可用时优先使用；json_object fallback 也必须把完整 Summary schema 明确提供给模型，再执行本地严格 schema/evidence validation。
- SiliconFlow/Volcengine structured summary 默认关闭 thinking/reasoning，避免推理过程耗尽最终 JSON 输出预算。
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
