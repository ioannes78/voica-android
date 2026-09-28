# Voica Android 全项目 Stage 开发路线图

当前状态：**Stage 1 开发中**。

整个 Voica 路线均为**全新独立实现**，不得从 `voice-card-android` 复制、迁移、继承、cherry-pick 或机械改写任何代码。

固定参考：`laidely/kardo@bcec3c5fdbcb34810a6f235e8b5873683f2ab951`，仅用于产品行为、公开协议事实和真实设备行为参考。

## Stage 0 — 项目基线

已完成：
- 建立独立公开仓库 `ioannes78/voica-android`
- 确定项目名 Voica
- 默认语言为简体中文
- 建立 AGENTS、产品需求、架构和参考基线
- 明确与 `voice-card-android` 完全隔离

状态：**已完成**

## Stage 1 — Android 工程基础 + QS668/CB08 协议核心

目标：建立最小、稳定、可测试的 Android 工程与纯 Kotlin 协议核心。

内容：
- Kotlin / Gradle / Jetpack Compose / Material 3 工程
- Application ID、minSdk、targetSdk
- 简体中文 UI 基线
- CRC-16/XMODEM
- SequenceGenerator
- Frame Builder
- AE22 / AE23 独立流式 FrameParser
- TYPE/CMD 常量
- 文件名编码
- 文件列表/容量字段解码
- 2-2 36B 文件导入请求编码
- 2-12 分段请求编码
- 单文件删除请求编码
- Golden tests
- 协议诊断页
- 精简 CI

退出条件：
- Unit tests 通过
- Debug 构建通过
- APK 可安装启动
- 中文诊断页正常

## Stage 2 — BLE 连接 + 设备基础信息

内容：
- BLE 扫描
- 连接/断开
- 串行 GATT 操作队列
- AE20/AE21/AE22/AE23 发现与订阅
- MTU 协商
- 时间同步
- 电量/充电状态
- 容量
- 固件版本
- 断连和重连
- 中文诊断日志

退出条件：重复连接/断开和读取设备信息真机测试通过。

## Stage 3 — 录音控制 + 设备实时状态

内容：
- 获取录音状态
- 开始录音
- 暂停/恢复
- 停止保存
- 当前录音时长
- 当前录音文件名
- AE23 设备按键事件
- App 发起与物理按键发起状态统一

退出条件：App 控制与设备物理按键操作均能稳定同步 UI。

## Stage 4 — 设备文件列表 + 文件名解析

内容：
- 文件列表请求与解析
- 时长/大小/名称
- 排序和刷新
- 短/截断文件名处理
- 2-12 分段探测可用完整文件名
- 已下载状态映射

退出条件：多文件、长文件名、空列表、异常列表和超时测试通过。

## Stage 5 — 文件下载 + 删除 + 原始音频落盘

内容：
- 候选文件名 fallback
- 下载进度/取消/重试/超时
- 断连恢复策略
- TYPE=2/CMD=2 36B 一次完整 GATT Write
- 重复下载保护
- 原始音频可靠落盘
- 单文件设备删除
- 删除二次确认
- 不提供 Delete All

退出条件：重复下载、断线、重试、删除、刷新不产生重复或损坏记录。

## Stage 6 — 本地录音库 + Opus/WAV 音频链路

内容：
- Room 本地元数据
- 原始 Opus 校验
- QS668 原始包结构重新验证
- Ogg/Opus/PCM/WAV 处理
- 16 kHz 标准音频
- 本地浏览/重命名/删除
- 设备断开时独立使用本地库

退出条件：App 重启和设备断开后录音仍可稳定访问。

## Stage 7 — 播放器 + 精确时间轴

内容：
- 播放/暂停
- 精确 seek
- 当前时间/总时长
- 倍速
- 长录音和快速连续 seek
- 为转写同步暴露稳定时间接口

退出条件：真实长录音下播放、seek、倍速稳定。

## Stage 8 — 本地 ASR + VAD + 标点 + 模型管理

目标：建立完整的本地文件转写管线。

核心抽象：
- `VadEngine`
- `AsrEngine`
- `PunctuationEngine`
- `ModelManager`

处理链：
`16 kHz PCM → VAD → SpeechSegment → 长度保护/合并 → ASR → 标点能力判断 → 必要时 PunctuationEngine → Timed Transcript`

VAD：
- 不把架构锁死在 WebRTC VAD
- 对 WebRTC VAD、Silero VAD、sherpa-onnx VAD 进行 Android 实测后选择首个实现
- 支持 minSpeechDuration、minSilenceDuration、speechPadding、maxSegmentDuration 等参数
- V1 文件转写阶段即使用 VAD，减少静音计算和硬切句问题

ASR：
- 中文优先
- 候选包括 SenseVoice、Paraformer、Qwen3-ASR 及其他 Android 可用模型
- 评估准确率、中英混说、速度、内存、模型大小和稳定性
- 长录音分块和峰值内存控制

标点：
- 不假定 ASR 一定自带标点
- AsrEngine 声明自身标点能力
- 模型已输出可靠标点时只做规范化
- 模型无标点或标点不足时调用 PunctuationEngine
- 最终保存带标点 Final Transcript

模型管理：
- 下载/进度/校验/SHA-256
- 切换/删除
- 空间不足/模型损坏处理

退出条件：短/长中文录音转写、VAD 分段和最终标点稳定可用。

## Stage 9 — 说话人分离

内容：
- DiarizationEngine
- 可启用/关闭
- Speaker ID
- 分块长录音处理
- 重叠、零长度、异常时间段归一化
- 同 Speaker 短间隔合并

暂不包含真人姓名、Voiceprint、跨录音 Speaker Matching。

退出条件：真实多人录音 Speaker 分段达到可用水平。

## Stage 10 — 转写时间轴 + 播放同步

内容：
- speaker + ASR + 标点结果整合
- 点击文字 seek
- 播放高亮当前段
- 自动滚动
- 用户手动滚动保护
- 保留真实 start/end，不为排版制造虚假时间戳

退出条件：长录音文字与播放双向同步稳定。

## Stage 11 — AI 会议纪要

内容：
- OpenAI-compatible Provider
- Base URL / API Key / Model
- 支持时自动获取模型列表
- 连接测试
- 中文会议摘要、关键结论、待办、决策、问题
- 本地持久化
- 默认只上传文字，不上传原始音频

退出条件：本地转写 → AI 纪要链路稳定。

## Stage 12 — 产品 UI / UX 完整化

内容：
- 统一录音列表、设备、播放器、转写、Speaker、模型、AI 设置体验
- Material 3
- Dark Mode
- Loading/Error/Empty State
- 简体中文文案统一

退出条件：功能型 UI 收敛为完整产品体验。

## Stage 13 — 稳定性与长录音专项

内容：
- BLE soak test
- 多文件/大文件/断连下载
- 1h/2h 音频
- 30/60/120 分钟 ASR
- RAM/Native Heap/CPU/温度/Crash/ANR
- 锁屏、后台、电话打断、蓝牙变化、低存储、进程被杀

退出条件：核心链路长期运行稳定。

## Stage 14 — Voica V1.0 Release Freeze

内容：
- Release Build / APK / AAB
- R8
- 权限和隐私检查
- API Key 安全
- Database Schema Freeze
- Model Manifest Freeze
- 中文文案检查
- Test Report / Architecture / Protocol / Handoff

版本：`1.0.0`

## Stage 15 — 实时音频链路

内容：
- TYPE=1 Realtime
- BLE 实时音频 → Opus → PCM
- 先保证实时音频稳定，不做实时转写

## Stage 16 — 本地实时转写

内容：
- Streaming VAD（WebRTC/Silero/sherpa-onnx 实测选择）
- Streaming/低延迟 ASR
- Interim / Stable / Final 状态
- 两层标点：
  - 正在变化的尾部尽量不反复改标点
  - VAD 段结束/结果稳定后补标点
  - FINAL 结果再统一规范化后保存

## Stage 17 — 云端文件转写

内容：
- CloudAsrEngine
- 独立于本地 AsrEngine
- 多 Provider
- 超长录音/高精度场景

## Stage 18 — 云端实时转写

内容：
- BLE Audio → Cloud Realtime ASR
- 网络中断和 fallback
- 不污染本地实时链路

## Stage 19 — 高级 AI 会议助手

内容：
- 自动章节
- 关键词
- 决策/Action Items
- 基于录音的问答
- 多版本会议纪要

## Stage 20 — Voica V2

候选：
- 云同步
- 用户账号
- 多设备
- Speaker Voiceprint
- 跨会议 Speaker
- Web/PC
- 团队协作
- 分享和高级搜索

## 固定 Stage 流程

`读取上一阶段 Freeze → 检查 GitHub 当前真实状态 → 修订需求 → 用户确认 → 修订开发规划 → 用户确认 → development branch → 开发 → Unit Test/Build/CI → APK → 真机测试 → 修复 → 用户确认通过 → Freeze → 合并 main`

任何需要真机验收的 Stage，在用户确认前不得标记最终完成。
