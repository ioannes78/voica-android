# Kardo 参考基线

## 1. 固定参考版本

- 仓库：`laidely/kardo`
- Commit：`bcec3c5fdbcb34810a6f235e8b5873683f2ab951`
- Commit 日期：2026-08-24
- 平台：iOS / macOS
- 技术栈：Swift / SwiftUI
- 固定版本未声明项目级 License

Voica 只把该仓库作为**产品行为、公开协议事实和设备行为参考**。

不直接复制 Swift 源码，不进行机械 Swift → Kotlin 翻译。

同时，Voica **不从 `voice-card-android` 继承任何实现代码**。

## 2. Kardo → Voica 功能映射

| Kardo 模块/能力 | 可观察行为 | Voica Android 目标 |
|---|---|---|
| CoreBluetooth | AE20 服务、AE21 写、AE22/AE23 Notify | Android BluetoothGatt |
| KardoProtocol | 帧、CRC、解析、字段解码 | 全新 Kotlin 协议层 |
| KardoRecorder | 请求响应、列表、下载、删除、控制 | Coroutines 设备会话层 |
| KardoOgg | QS668 裸 Opus 包装 | 独立 Android 音频实现 |
| KardoLibrary | 本地录音持久化 | Room + App 文件存储 |
| KardoAudioPlayer | WAV seek / 倍速 | Media3 / AudioTrack 评估实现 |
| KardoSpeechEngine | 本地 ASR + 说话人分离 | Android 兼容本地引擎 |
| KardoSegmentMerge | 真实时间段归一化/合并 | Kotlin 纯函数重新实现 |
| KardoLlm | OpenAI-compatible 会议纪要 | Provider-neutral HTTP 客户端 |
| SwiftUI | 录音列表、详情、设置 | Jetpack Compose |

## 3. 需要独立复现并验证的协议事实

- 帧格式：`5A | SEQ | CRC16-LE | LEN-LE | DATA`
- CRC：对 `LEN + DATA` 计算 CRC-16/XMODEM
- 标准 CRC 向量：`123456789 -> 0x31C3`
- GATT：AE20 / AE21 / AE22 / AE23
- AE22 与 AE23 必须保持独立流式解析状态
- TYPE=2/CMD=2 文件导入请求为完整 36B 帧
- 文件列表中存在短/截断文件名行为
- 列表数值字段按实际设备行为验证字节序
- 下载候选可包含 `base.opus`、`base.wav` 和原列表名
- 单文件删除以真实固件行为为准，不盲信协议文字描述
- 原始 Opus 包结构必须结合真实录音文件重新验证

这些内容必须在 Voica 自己的 Kotlin 测试中重新表达和验证。

## 4. Apple 平台专属设计不得照搬

以下实现属于平台特定方案：

- SwiftUI / iOS Liquid Glass
- CoreBluetooth 生命周期
- AVAudioEngine
- MLX / MLXSwift
- CoreML Sortformer
- UserDefaults
- Apple 平台内存缓存控制方式

Voica 必须使用 Android 侧适合的实现。

## 5. Android 对应技术方向

- Jetpack Compose
- Android BluetoothGatt
- 串行化 GATT 操作队列
- Coroutines / StateFlow
- Room / DataStore
- Android Opus / PCM 处理
- Android 兼容 ASR 运行时
- Android 兼容说话人分离运行时
- Media3 / AudioTrack 按实际时序要求选择

## 6. 冲突处理原则

当以下三者发生冲突：

1. 公开协议说明
2. Kardo 可观察行为
3. CB08/QS668 真机行为

Voica 以**可重复验证的真实设备行为**为最终依据，并把结论记录到测试和协议文档中。
