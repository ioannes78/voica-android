# Voica 开发规则

## 一、项目唯一事实来源

当前 `ioannes78/voica-android` 仓库是 Voica 项目实现状态的唯一事实来源。

Kardo 参考基线固定为：

- 仓库：`laidely/kardo`
- 提交：`bcec3c5fdbcb34810a6f235e8b5873683f2ab951`

## 二、与 voice-card-android 完全隔离

这是硬性规则：

**Voica 不继承、不复制、不迁移、不 cherry-pick、不机械翻译 `voice-card-android` 的任何代码。**

禁止从 `voice-card-android` 复制或改写：

- Kotlin/Java/C/C++ 源代码
- 单元测试、Instrumentation 测试
- Gradle 脚本和模块实现
- Android 资源文件
- Room 数据库实体、DAO、Migration 实现
- BLE、音频、ASR、说话人、云端等模块实现
- Stage 1–30 的功能代码
- 可执行实现型配置或脚本

如需验证设备行为，应基于 Kardo 公开行为、公开协议事实、真实设备抓包/测试结果以及 Voica 自己重新编写的测试进行独立实现。

不得为了“节省开发时间”从旧 VoiceCard 工程搬运实现。

## 三、语言规范

- App 默认界面语言：**简体中文**
- README、需求、架构、路线图、测试说明、Freeze/Handoff：**简体中文**
- 用户可见错误信息和状态信息：默认简体中文
- Kotlin 标识符、模块名、协议常量、API 字段：保持英文技术命名
- 必要英文术语可在中文文档中直接使用，例如 BLE、ASR、Room、Flow

## 四、阶段开发门禁

每个 Stage 开始前必须：

1. 阅读本文件。
2. 阅读当前 ROADMAP、ARCHITECTURE、协议说明和上一阶段 Freeze/Handoff。
3. 检查 GitHub 当前真实代码，不依赖聊天记忆。
4. 输出该 Stage 的“修订需求”。
5. 等待用户明确确认。
6. 输出“修订开发规划”。
7. 再次等待用户明确确认。
8. 只有确认后才允许开始编码。

每个 Stage 结束前必须：

1. 执行适用的 build、unit test、lint/static check。
2. 有用户界面的阶段应提供可真机测试 APK。
3. 记录准确 commit SHA、CI/build 证据、已知风险和真机测试清单。
4. 创建该阶段 Freeze/Handoff 文档。
5. 真机未确认通过，不得标记该阶段最终完成。

## 五、Android 技术基线

- 开发语言：Kotlin
- UI：Jetpack Compose
- 并发：Kotlin Coroutines / Flow
- 构建：Gradle Kotlin DSL
- Application ID：暂定 `io.github.ioannes78.voica`
- ABI 优先：`arm64-v8a`
- minSdk：Stage 1 中最终确定
- 默认产品语言：简体中文

## 六、架构规则

- BLE transport、二进制协议、音频、数据库、ASR、说话人分离、AI 和 UI 必须保持清晰边界。
- AE22 与 AE23 通知必须使用独立的流式帧解析器。
- GATT 操作必须串行化。
- TYPE=2/CMD=2 文件导入请求按完整 36B 协议帧处理。
- 原始设备音频必须先可靠落盘，再进行转换。
- 删除等破坏性操作必须有明确二次确认。
- UI 层不得直接解析二进制协议帧。
- ASR/LLM 的供应商或模型具体类型不得污染上层 Feature UI 契约。
- 下载、解码、转写、说话人分离等耗时操作必须支持取消并正确处理生命周期。
- 本地录音必须在设备断开后仍可独立使用。

## 七、测试规则

协议层必须具有 deterministic/golden tests，至少覆盖：

- CRC-16/XMODEM：`123456789 -> 0x31C3`
- 帧构造与解析
- 通知拆包、粘包
- AE22/AE23 独立解析状态
- 文件列表字节序
- 36B 文件导入请求
- 单文件删除请求布局
- 文件名候选回退
- Opus 包/容器转换关键约束
- 转写时间段归一化和合并规则

**CI 成功不等于真机验收成功。**

BLE、文件下载、音频转换、长录音、ASR、说话人分离必须按阶段执行真实设备或真实录音验收。

## 八、CI 原则

CI 默认保持精简：

- PR 运行快速 build/unit test
- 当前阶段不需要时，不运行模拟器或 Instrumentation
- 同一提交避免重复 Workflow
- 实验分支不自动触发昂贵构建
- APK 构建按阶段验收需要触发
