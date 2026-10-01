# Voica 开发规则

## 一、项目唯一事实来源

当前 `ioannes78/voica-android` 仓库是 Voica 项目实现状态的唯一事实来源。

当前已冻结基线：**Stage 8**

Stage 8 Freeze/Handoff：

- `docs/STAGE_8_FREEZE.md`
- `docs/STAGE_8_HANDOFF.md`
- `docs/STAGE_8_TEST.md`

下一阶段：**Stage 9 — 说话人分离**

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
- versionCode：23
- versionName：`0.8.0-stage8-alpha4`
- sherpa-onnx：1.13.8
- Room schema：2
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
- `:engine:opus`
- `:engine:playback`
- `:engine:sherpa`

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
