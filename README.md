# Voica

Voica 是面向 QS668 / CB08 AI 录音卡的 Android 原生客户端。

本项目为**全新、独立的 Android 项目**。Voica **不继承、不复制、不迁移、不翻译 `voice-card-android` 的任何代码**，包括源代码、测试代码、Gradle 配置、资源文件、数据库实现、模块实现和历史 Stage 代码。

## 协议与行为参考

协议事实优先级：

1. QS668 / CB08 真机可重复验证结果。
2. 官方 Android App `声云语音转写 3.0.9-u` 静态实现，用于协议/行为主参考；与真机冲突时以真机为准。
3. `nextproto1024/ai-recorder-card-open-protocol`，固定参考 commit：`e741ea72207f1a2aae3df4debc5c135728e0170e`。
4. `laidely/kardo`，固定参考 commit：`bcec3c5fdbcb34810a6f235e8b5873683f2ab951`，仅用于产品行为与设备交互交叉验证。
5. Android 官方 BLE API 行为。

`voice-card-android` 不作为 Voica 的代码、架构、测试或 Gradle 来源。

## 产品目标

- 稳定连接 QS668 / CB08 录音卡
- 获取设备电量、容量、固件版本并同步时间
- 浏览、下载和删除设备录音文件
- 保留原始 Opus 数据并生成稳定的 16 kHz PCM/WAV 音频
- 本地播放、精确跳转、倍速和转写同步
- Android 端本地语音转写
- 说话人分离
- AI 会议纪要
- 以稳定性、可测试性和真机可靠性为优先目标

## 技术路线

- Kotlin
- Jetpack Compose
- Kotlin Coroutines / Flow
- Android 原生 BLE
- Room / DataStore
- Android 兼容的本地 ASR 与说话人分离运行时
- OpenAI-compatible AI 接口

## 语言规范

- App 默认用户界面语言：**简体中文**
- 项目需求、架构、路线图、测试说明、Freeze/Handoff 文档：**简体中文**
- Git commit message：优先简体中文，可保留必要英文技术名词
- Kotlin 类名、函数名、模块名、协议常量、API 字段等技术标识按 Android/Kotlin 英文命名规范保留

## 开发原则

- `voice-card-android` 与 Voica 完全隔离，不作为源码来源
- 不进行 Swift → Kotlin 逐行翻译
- BLE、协议、音频、ASR、说话人分离和 UI 分层实现
- 协议与二进制行为必须有 deterministic/golden tests
- 删除等破坏性设备操作必须二次确认
- 长录音处理必须限制内存占用并支持取消
- CI 通过不能替代真机验收

## 当前状态

- Stage 0：已完成
- Stage 1：已完成 / 已真机验收 / 已冻结
- Stage 2：已完成 / 已两轮真机验收 / 已冻结
- Stage 3：已完成 / 已真机验收 / 已冻结
- Stage 4：已完成 / 已真机验收 / 已冻结
- Stage 5：已完成 / 已真机验收 / 已冻结
- Stage 6：已完成 / 已真机验收 / 已冻结
- Stage 7：已完成 / 已用户验收 / 已冻结
- Stage 8：已完成 / 已真机验收 / 已冻结
- Stage 9：已完成 / 已真机验收 / 已冻结
- Stage 10：已完成 / 已真机验收 / 已冻结
- Stage 11：已完成 / 已真机验收 / 已冻结
- Stage 12：已完成 / 已真机验收 / 已冻结
- 下一阶段：**Stage 13A — 本地 ASR / Diarization 引擎增强与参数调优**

Stage 2 已建立：

- `:core:ble`
- BLE 扫描 / 权限
- QS668/CB08 GATT Session
- 严格串行 GATT Operation Queue
- AE20/AE21/AE22/AE23 发现与订阅
- `requestMtu(517)`
- 时间同步
- 电量 / 充电
- 容量
- 固件
- Auth
- 有限自动重连
- 简体中文设备页与 BLE Diagnostics

Stage 2 真机确认 Actual MTU = **517**。

Stage 3 已建立：

- App 卡录音开始 / 暂停 / 继续 / 停止并保存
- 设备物理录音按键事件同步
- 录音状态 / 时长 / 当前大小 / 当前文件名 / 增益
- Recording 时约 1 秒 GET_TIME Poller
- Pause 语义锁存，兼容当前固件 GET_STATE=1 无法区分 Recording/Paused
- 物理事件 acknowledgement + reconciliation
- 最后成功设备记忆与 App 启动自动连接
- 录音状态 Diagnostics 与真机协议证据

Stage 3 真机确认 App 控制与设备物理按键控制均能稳定同步 UI。

Stage 4 已建立：

- TYPE=2/CMD=0 文件列表请求
- TYPE=2/CMD=1 多帧文件列表聚合
- TYPE=2/CMD=18 明确列表完成
- 官方 App 兼容的动态 filename field 严格解析
- 当前 QS668/CB08 真机确认 filename field = **20B**
- 标准 `noteYYYYMMDD-HHMMSS.` 安全恢复为 `.opus`
- 文件录制时间、时长、大小显示
- 真机确认列表第一个 BE32 = **录音时长秒数**
- 最新录制优先排序、手动刷新、Stale/Failed 状态
- 文件列表 Diagnostics

Stage 5 已建立：

- 首次连接 / 重连 / 新录音完成后自动刷新设备文件列表
- TYPE=2/CMD=2/3/4/5 文件下载主链
- 专用可靠文件数据通道
- 下载进度、取消、超时、断线处理
- 原始音频流式 `.part` 落盘、SHA-256、fsync、原子提交
- 真机确认下载内容为原始 `.opus` 字节流
- CMD=12 范围下载真机确认语义为 `[start, end)`
- 单条设备录音删除与二次确认
- 真机确认删除设备录音会同时删除物理同名 `.opus + .wav`
- 本地文件与设备文件独立管理，不提供联合删除
- 不提供 Delete All

Stage 6 已建立：

- Room 正式本地录音库与 Stage 5 legacy metadata 导入
- DEVICE_OPUS / DEVICE_WAV / CANONICAL_WAV 独立资产模型
- 设备 OPUS / WAV 独立下载、独立验证
- 设备列表分别显示 OPUS / WAV 真实大小
- WAV CMD=12 `[0,44)` RIFF header probe
- OPUS / WAV 真实百分比下载进度
- official libopus 1.6.1 JNI/NDK 解码
- raw Opus → PCM → 16 kHz mono PCM16 canonical WAV
- PCM WAV → canonical WAV 流式归一化
- 逻辑 Recording 重命名、删除、崩溃恢复
- `AudioSourceResolver` / `PcmSourceResolver` 稳定接口
- 后台可靠下载明确规划到 Stage 13B

项目文档：

- [产品需求](docs/PRODUCT_REQUIREMENTS.md)
- [系统架构](docs/ARCHITECTURE.md)
- [开发路线图](docs/ROADMAP.md)
- [Stage 2 测试](docs/STAGE_2_TEST.md)
- [Stage 2 Freeze](docs/STAGE_2_FREEZE.md)
- [Stage 2 Handoff](docs/STAGE_2_HANDOFF.md)
- [Stage 3 测试](docs/STAGE_3_TEST.md)
- [Stage 3 Freeze](docs/STAGE_3_FREEZE.md)
- [Stage 3 Handoff](docs/STAGE_3_HANDOFF.md)
- [Stage 4 测试](docs/STAGE_4_TEST.md)
- [Stage 4 Freeze](docs/STAGE_4_FREEZE.md)
- [Stage 4 Handoff](docs/STAGE_4_HANDOFF.md)
- [Stage 4 协议发现](docs/STAGE_4_PROTOCOL_FINDINGS.md)
- [Stage 4 真机发现](docs/STAGE_4_REAL_DEVICE_FINDINGS.md)
- [Stage 5 测试](docs/STAGE_5_TEST.md)
- [Stage 5 Freeze](docs/STAGE_5_FREEZE.md)
- [Stage 5 Handoff](docs/STAGE_5_HANDOFF.md)
- [Stage 5 协议发现](docs/STAGE_5_PROTOCOL_FINDINGS.md)
- [Stage 5 真机发现](docs/STAGE_5_REAL_DEVICE_FINDINGS.md)
- [Stage 6 测试](docs/STAGE_6_TEST.md)
- [Stage 6 Freeze](docs/STAGE_6_FREEZE.md)
- [Stage 6 Handoff](docs/STAGE_6_HANDOFF.md)
- [Stage 6 协议与音频发现](docs/STAGE_6_PROTOCOL_FINDINGS.md)
- [Stage 6 真机发现](docs/STAGE_6_REAL_DEVICE_FINDINGS.md)
- [Stage 7 测试](docs/STAGE_7_TEST.md)
- [Stage 7 Freeze](docs/STAGE_7_FREEZE.md)
- [Stage 7 Handoff](docs/STAGE_7_HANDOFF.md)
- [开发规则](AGENTS.md)

## Stage 11 已建立

- `:core:ai` + `:engine:llm`
- 结构化转写 → Text LLM → AI Summary
- SMART / PRESET / CUSTOM 总结模式
- 多 Provider Profile、模型发现、连接测试
- Google Gemini / xAI Grok / OpenAI-compatible Provider
- Android Keystore + AES-GCM API Key 保存
- strict JSON schema/evidence validation
- 长文本 map/reduce 与 Room checkpoint
- 默认简体中文总结
- Room schema v4
- AI evidence 可回链 Stage 10 sample timeline
- Audio LLM 仅保留能力契约，完整直接音频理解在 Stage 19

## License

Voica 项目许可证尚未确定。


## Stage 7 已建立

- 新增 `:engine:playback`
- verified CANONICAL_WAV → AudioTrack MODE_STREAM
- absolute 16 kHz PCM sample index 作为唯一媒体时间
- 真实 RIFF data offset 的 sample seek
- presented position tracking
- AudioTimestamp / playbackHead fallback
- 32-bit playback head wrap
- 六档倍速且 pitch=1.0
- Audio Focus / noisy / Bluetooth route / app lifecycle
- 单一 PlayerCard
- device recording → local playback 自动暂停
- Stage 8 / Stage 10 稳定 sample timeline contract
- 真实长录音 30min/1h/2h 压力测试债务转入 Stage 13B；Stage 13A 先冻结最终本地语音模型与参数基线
