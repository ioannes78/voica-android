# Voica

Voica 是面向 **QS668 / CB08 AI 录音卡** 的 Android 原生客户端，覆盖设备连接与录音控制、录音下载与本地管理、本地语音转写、说话人分离、播放同步、全文搜索与 AI 会议总结。

> 当前正式版本：**Voica 1.0.0**  
> Android：**8.0+（minSdk 26）**  
> ABI：**arm64-v8a**  
> 默认语言：**简体中文**

## 下载

前往 GitHub Releases 下载正式签名 APK：

**[下载最新版本](https://github.com/ioannes78/voica-android/releases/latest)**

Voica 1.0.0 正式 APK：

- Application ID：`io.github.ioannes78.voica`
- versionCode：`88`
- versionName：`1.0.0`
- APK SHA-256：`62c62d4ad93cef3b58a5443181f16d984e397b94785286136e01e163f9f125fe`
- Production certificate SHA-256：`f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`

首次安装需要允许系统从当前来源安装 APK。后续升级请继续使用官方正式签名版本，避免签名不一致导致无法覆盖安装。

## 主要功能

### QS668 / CB08 录音卡

- BLE 扫描、连接、自动重连与设备状态同步
- 电量、充电状态、容量、固件信息与时间同步
- App 录音开始 / 暂停 / 继续 / 停止
- 设备物理按键状态同步
- 设备录音文件列表、刷新、下载与删除
- OPUS / WAV 资产独立管理与校验

### 本地录音库

- 本地导入 WAV / MP3 / M4A-AAC / ADTS AAC / FLAC / Ogg Opus
- 原始文件与 Canonical WAV 分离保存
- Canonical 音频统一为 `16 kHz / mono / PCM16`
- 重命名、收藏、标签、文件夹、排序、筛选和批量管理
- 全文搜索覆盖文件名、转写内容和 AI 总结
- 搜索结果可定向进入对应内容

### 播放与时间轴

- 波形播放器
- 精确 seek 与播放位置同步
- 多档倍速播放
- 转写文本与播放时间轴联动
- 全局 Mini Player

### 本地语音识别

V1.0 当前文件转写模型：

- **SenseVoice INT8**：快速 / 默认
- **Qwen3-ASR 0.6B INT8**：高质量

同时包括：

- Silero VAD
- 中英文标点恢复
- 多版本转写管理
- 转写历史、删除与人工编辑 Revision
- 长录音后台执行、任务恢复与通知

### 说话人分离

正式链路：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering / stitching → transcript alignment`

支持：

- 自动说话人数
- 1 / 2 / 3 / 4 / 5+ 人数约束
- 明确选择 1 人时使用单说话人 Fast Path
- ASR 正文完成后立即可读，说话人后处理继续后台运行
- 说话人结果增量补充，不覆盖 ASR 原文

### AI 总结

- 基于转写文本生成结构化会议总结
- 多 Provider / 多模型配置
- OpenAI-compatible Provider 支持
- 模型发现与连接测试
- 长文本 map/reduce
- Structured Output / Evidence 校验
- 总结版本、人工编辑 Revision 与历史管理
- Provider credential 使用 Android Keystore 加密保存

## V1.0 稳定性基线

Stage 14 已完成正式发布冻结和真机长录音验证：

| 音频时长 | SenseVoice | SenseVoice RTF | 说话人分离 | Diarization RTF | 结果 |
| --- | ---: | ---: | ---: | ---: | --- |
| 30:48 | 4:08 | 0.1342 | 13:10 | 0.4275 | PASS |
| 64:27 | 8:00 | 0.1241 | 27:30 | 0.4267 | PASS |
| 121:07 | 16:20 | 0.1349 | 55:30 | 0.4582 | PASS |

三档测试均完成后台 / 息屏恢复、播放、搜索、导出和结果持久化检查，未报告闪退或卡死。

## 当前开发状态

**Stage 14 — Voica V1.0 Release Freeze：FINAL / ACCEPTED / FROZEN**

当前主线正式基线：

- `versionCode 88`
- `versionName 1.0.0`
- Room schema `12`
- sherpa-onnx `1.13.8`
- arm64-v8a

下一阶段：

**Stage 15 — BLE 实时音频链路**

后续主路线：

`Stage 15 BLE realtime audio → Stage 16A 本地实时 ASR → Stage 16B 文件 ASR V2 → Stage 17/18 云端 ASR → Stage 19 本地/云端 AI → Stage 20 Voica V2`

另有独立 Post-V1 P0 优化项：Canonical WAV 生成性能、生成进度与取消交互优化。

## 技术栈

- Kotlin 2.4.20
- Jetpack Compose
- Kotlin Coroutines / Flow
- Android 原生 BLE / GATT
- Room
- WorkManager / Android notifications
- sherpa-onnx 1.13.8
- libopus 1.6.1
- Android NDK / JNI
- R8 + resource shrink

主要模块：

```text
app
├─ core:protocol
├─ core:ble
├─ core:database
├─ core:audio
├─ core:model
├─ core:transcript
├─ core:ai
├─ engine:opus
├─ engine:media
├─ engine:playback
├─ engine:sherpa
└─ engine:llm
```

## 构建环境

当前 V1.0 构建基线：

- JDK 17
- Android Gradle Plugin 9.4.0
- Gradle 9.6
- compileSdk 37.1
- targetSdk 37
- minSdk 26

生产签名材料不进入仓库。普通开发 / QA 构建不得使用生产 keystore。

## 协议与事实来源

Voica 的 QS668 / CB08 行为优先级：

1. QS668 / CB08 真机可重复验证结果
2. 官方 Android App `声云语音转写 3.0.9-u` 的协议 / 行为参考
3. [`nextproto1024/ai-recorder-card-open-protocol`](https://github.com/nextproto1024/ai-recorder-card-open-protocol)
4. Android 官方 BLE / Media / Storage 行为

当静态资料与真机观察冲突时，以可重复真机证据为准。

## 项目文档

推荐从以下文档开始：

- [开发规则](AGENTS.md)
- [产品需求](docs/PRODUCT_REQUIREMENTS.md)
- [系统架构](docs/ARCHITECTURE.md)
- [Stage 13C+ 路线图](docs/ROADMAP_STAGE_13C_PLUS.md)
- [Stage 14 Final Freeze](docs/STAGE_14_FREEZE.md)
- [Stage 14 Final Handoff](docs/STAGE_14_HANDOFF.md)
- [Stage 14 长录音稳定性](docs/STAGE_14_7B_STABILITY.md)
- [Post-V1 Canonical Audio 优化规划](docs/POST_V1_CANONICAL_AUDIO_OPTIMIZATION_PLAN.md)

历史 Stage 的测试、Freeze、Handoff 和协议发现记录均保留在 [`docs/`](docs/) 中，不再堆叠在项目首页。

## 独立项目声明

Voica 是一个**全新、独立实现**的 Android 项目。

本项目不继承、不复制、不迁移、不 cherry-pick、不机械翻译 `ioannes78/voice-card-android` 的源代码、测试、Gradle、资源、数据库或模块实现。历史真机观察只能作为交叉验证线索，Voica 的实现和测试均独立完成。

## License

当前仓库**尚未声明开源许可证**。在许可证明确之前，请勿默认获得复制、修改、再分发或商用授权。
