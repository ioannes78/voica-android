# Voica Android 系统架构草案 V1

## 1. 架构原则

Voica 是全新 Android 工程。

**不从 `voice-card-android` 继承任何代码、模块实现或构建脚本。**

系统按“协议、传输、数据、音频、AI/ML、Feature UI”分层，避免形成一个同时处理 BLE、文件、音频、模型和界面的巨型状态对象。

## 2. 总体分层

```
Jetpack Compose UI
        |
Feature ViewModel
        |
UseCase / Repository
        |
+-------------------+-------------------+-------------------+
| BLE / Device      | Audio / Library   | AI / ML           |
|                   |                   |                   |
| Recorder Session  | Playback          | ASR               |
| GATT Transport    | Conversion        | Diarization       |
| Protocol          | Room / Files      | Meeting Notes     |
+-------------------+-------------------+-------------------+
```

## 3. 初步模块规划

- `app`：应用入口、导航、依赖装配
- `core-model`：公共领域模型和接口
- `core-protocol`：QS668/CB08 二进制协议
- `core-ble`：Android BLE Transport / Session
- `core-audio`：音频格式和时间轴契约
- `core-database`：Room
- `engine-opus`：设备 Opus 包装/解码
- `engine-asr`：本地 ASR 抽象与实现
- `engine-speaker`：说话人分离
- `engine-ai`：AI 会议纪要客户端
- `feature-device`：扫描、连接、设备状态
- `feature-recordings`：设备文件、本地录音、传输
- `feature-transcript`：转写、时间轴、同步播放
- `feature-settings`：模型、AI 服务和诊断设置

Stage 1 可以根据实际 Gradle 复杂度合并部分物理模块，但逻辑边界必须保留。

## 4. 状态模型

以下状态必须显式建模：

- BLE 连接状态
- GATT 初始化状态
- 文件列表加载状态
- 文件下载状态
- 音频转换状态
- 转写状态
- 说话人分离状态
- AI 纪要状态

不允许使用一个全局可变 Singleton 承担整个应用状态。

## 5. 设备文件与本地录音身份

设备文件身份与本地录音身份必须分离：

- 设备上存在文件，不代表本地已经下载
- 同一个设备文件成功下载后应映射到稳定的本地 Recording
- 重试下载不能静默产生多个相同本地录音
- 删除设备端文件不能自动删除本地副本
- 删除本地副本不能自动删除设备端文件

## 6. 音频落地原则

处理顺序：

1. 先可靠保存设备原始字节
2. 校验原始格式/包结构
3. 再生成播放/ML 使用的标准本地音频
4. 转换必须幂等
5. App 重启后不能依赖重新连接录音卡恢复本地音频
6. 原始音频与派生音频生命周期要明确

## 7. 转写时间轴

真实时间段属于核心数据，不是 UI 临时数据。

数据库应保存：

- start
- end
- speaker
- text
- 必要的转写版本/模型元数据

UI 的段落合并、文本排版和高亮是派生显示，不得破坏原始时间信息。

## 8. 中文产品基线

- 默认 UI 为简体中文
- 中文状态文本集中管理，禁止在业务逻辑层散落大量硬编码用户文案
- Kotlin 技术命名继续使用英文
- 后续国际化应通过资源系统实现，不影响领域层

## 9. 安全

- API Key 不写入仓库
- API Key 不输出到普通日志
- 凭据使用 Android 合适的安全存储
- 设备删除等破坏性操作必须明确确认
