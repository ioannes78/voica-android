# Voica Stage 13A — QA5 真机验收

状态：**QA5 候选 / 待真机验收 / 未冻结**

## 1. 候选基线

- 分支：`stage13a-local-speech-engine-v2`
- PR：#15（Draft / 未合并）
- versionCode：45
- versionName：`0.13.0-stage13a-qa5`
- QA Application ID：`io.github.ioannes78.voica.qa`
- Room schema：7
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- production model manifest：**本阶段不修改**
- 真机可继续使用 `candidate-stage13a-all-r1` 作为 Debug 候选来源；QA5 App 会在 catalog 边界仅保留最终产品模型矩阵。

本轮允许卸载旧 QA App 后全新安装，因此不做覆盖安装、旧 SharedPreferences 或旧 QA 数据库迁移验收。

## 2. QA5 最终模型矩阵

### 离线转写

- [ ] 快速 · SenseVoice（默认）
- [ ] 高质量 · Qwen3-ASR 0.6B INT8
- [ ] FireRedASR2 不再出现在产品模型清单/设置/转写入口

### 实时转写（为后续 Stage 15/16 准备）

- [ ] 轻量 · Small Bilingual（默认）
- [ ] 高质量 · Chinese Large CTC INT8
- [ ] 不显示 AUTO
- [ ] 不显示 Large Transducer
- [ ] 不显示 Modified Beam Search
- [ ] 不显示 Max Active Paths

### 说话人分离

- [ ] Pyannote Segmentation 3.0 INT8
- [ ] CAM++
- [ ] 不显示 ERes2Net 选择
- [ ] 普通用户不需要选择 Speaker Embedding 模型

### 其他

- [ ] Silero VAD
- [ ] CT-Transformer punctuation

## 3. 全新安装准备

1. 卸载旧 QA App。
2. 安装 QA5 APK。
3. 打开“设置 → 本地模型 → 开发选项”。
4. 使用 Stage 13A 全量候选清单。
5. 完全退出并重新打开 App。
6. 检查模型列表。

验收：

- [ ] 本地模型页最终只显示 QA5 产品矩阵中的模型
- [ ] FireRedASR2 / Large Transducer / ERes2Net 不显示
- [ ] production channel 未被改写

## 4. “本地语音识别”页面

页面应为四个一级折叠区：

### 4.1 离线转写

默认展开。

- [ ] 只显示“快速 · SenseVoice”与“高质量 · Qwen3-ASR”
- [ ] 当前选择清晰显示在折叠标题摘要中
- [ ] 默认 SenseVoice
- [ ] 选择 SenseVoice 时显示语言与 ITN
- [ ] 选择 Qwen 时显示 maxTotalLen / maxNewTokens / temperature / topP / seed / hotwords

### 4.2 实时转写

默认折叠。

- [ ] 只显示 Small Bilingual / Large CTC
- [ ] 明确提示“用于后续边录边转写，不影响当前录音文件的离线转写”

### 4.3 说话人分离

默认折叠。

- [ ] 转写后自动说话人分离开关
- [ ] 说话人数：自动 / 1 / 2 / 3 / 4 / 5+
- [ ] 高级设置包含自动聚类阈值、跨分块相似度阈值
- [ ] 不显示 CAM++ / ERes2Net 模型选择器

### 4.4 性能与语音检测

默认折叠。

- [ ] 性能模式
- [ ] CPU 线程数
- [ ] VAD threshold
- [ ] min silence
- [ ] min speech
- [ ] max speech

## 5. 录音文件离线转写统一入口

录音详情 → 转写：

- [ ] 只有一个主要动作“开始离线转写”
- [ ] 不再同时提供“快速转写 / 高质量转写”两个入口
- [ ] 模型由“设置 → 本地语音识别 → 离线转写”决定
- [ ] 未生成标准音频时先显示生成/取消标准音频动作

## 6. SenseVoice 离线转写

准备一条 1–3 分钟普通话录音，最好包含数字、日期、英文词和多个停顿。

### ITN 关闭

- [ ] VAD → SenseVoice → CT-Transformer → 保存
- [ ] 转写完成
- [ ] 标点自然
- [ ] punctuation-only token 不形成独立 speaker/timeline cue
- [ ] 运行时只有实际执行 CT 时显示“正在处理标点…”
- [ ] 结果顶部显示“SenseVoice · 快速”

### ITN 开启

- [ ] SenseVoice 原生 ITN/文本处理生效
- [ ] 不运行 CT-Transformer
- [ ] 不显示“正在处理标点…”
- [ ] 不出现独立“。/，/？”说话人段
- [ ] 结果顶部仍显示“SenseVoice · 快速”

## 7. Qwen3-ASR 离线转写

- [ ] Qwen3-ASR 可独立完成最终文本
- [ ] 结果顶部显示“Qwen3-ASR · 高质量”
- [ ] 不显示“第一遍 / 第二遍 / 二次 ASR”等工程文案
- [ ] 若 Small Bilingual 已启用，可作为内部后置时间轴 alignment 使用
- [ ] 后置 alignment 时用户只看到“正在生成时间轴…”
- [ ] alignment 不得覆盖 Qwen 最终文本
- [ ] alignment 失败只降级时间轴，不把 Qwen 最终文本标记失败
- [ ] 未启用 Small Bilingual 时仍可完成 Qwen 转写并退化为 segment timeline

## 8. 自动说话人分离

设置“转写后自动说话人分离”= 开。

- [ ] SenseVoice 转写后自动运行
- [ ] Qwen 转写后自动运行
- [ ] 离开录音详情页后任务仍能完成
- [ ] 同一录音已有 compatible completed diarization 时，新转写版本复用 Speaker Turns，只重新做 Speaker Alignment
- [ ] 不重复跑重型 embedding/clustering
- [ ] 失败可手动重试，不形成无限自动重试

## 9. CAM++ / Speaker Auto 专项

至少准备：

1. 单人录音
2. 双人录音
3. 3～4 人录音

记录每条录音真实人数与识别人数。

### 单人

- [ ] Auto 不再明显碎成大量 speaker
- [ ] 固定 1 人严格得到 1 个 speaker

### 双人

- [ ] Auto 不错误合并成 1 人
- [ ] 不明显碎裂成大量 speaker
- [ ] 固定 2 人结果稳定

### 3～4 人

- [ ] speaker 数基本稳定
- [ ] 跨 chunk 身份连续性可接受
- [ ] 固定人数约束正常

高级参数回归：

- [ ] clustering threshold 只影响 Auto
- [ ] stitching cosine threshold 生效
- [ ] stitching threshold 调高不会被误解为“更容易合并”；越高应越严格

若单人仍出现类似 1 → 6 的严重碎片化，QA5 不直接判定通过，应继续调整 clustering / stitching / anchor aggregation / speaker merge。

## 10. 性能与 VAD

- [ ] 自动 / 省电 / 均衡 / 性能模式正常
- [ ] 手动 CPU threads 受设备核心数与 8 线程安全上限约束
- [ ] VAD threshold / min silence / min speech / max speech 可调
- [ ] 恢复推荐值正常

## 11. Benchmark 已退出产品

QA5 不再使用 Benchmark 做模型选型。

- [ ] 录音详情无 Speech Benchmark
- [ ] 录音详情无 Diarization Benchmark
- [ ] 无 Benchmark 产品入口
- [ ] Benchmark 自动退出问题不再作为 QA5 阻塞项

必须继续保留并回归：

- [ ] isolated model validator
- [ ] 模型 SHA / 文件完整性校验
- [ ] native runtime smoke/验证
- [ ] sherpa runtime probe

## 12. 用户文案

整个普通用户 UI 检查：

- [ ] 不出现“第一遍识别”
- [ ] 不出现“第二遍识别”
- [ ] 不出现“二遍 ASR”
- [ ] 不出现 `first-pass` / `second-pass`
- [ ] 使用“离线转写 / 实时转写 / 正在识别 / 正在生成时间轴 / 标点处理 / 说话人分离”等产品语言

内部 Kotlin enum、Room state、数据库字段可以继续保留 `FIRST_PASS / SECOND_PASS`。

## 13. 核心功能回归

- [ ] BLE 连接 / 自动连接 / 电量 / 容量 / Firmware
- [ ] 开始 / 暂停 / 继续 / 停止录音
- [ ] 设备文件刷新 / OPUS / WAV 下载 / 删除
- [ ] 本地录音库 / 导入 / canonical WAV
- [ ] 播放 / seek / Audio Focus / Mini Player
- [ ] 离线转写
- [ ] 时间轴 / 播放同步
- [ ] 自动说话人分离 / speaker alignment
- [ ] 转写阅读稿 / 编辑 / 版本 / 搜索
- [ ] AI 总结 / Provider / Model 选择
- [ ] 全局任务状态
- [ ] BLE Diagnostics

## 14. CI Gate

- [ ] Unit tests 通过
- [ ] Debug/QA build 通过
- [ ] Room schema 校验通过，仍为 v7
- [ ] QA signing identity 校验通过
- [ ] APK artifact 成功上传

## 15. QA5 完成条件

只有用户明确回复：

**“测试通过”**

之后才可以进入 QA6：

- Recording Detail V2
- 转写 / AI 总结最终内容生命周期收口

在此之前：

- 不 Freeze
- 不 merge PR #15
- 不修改 production model channel
- 不进入 Stage 13B
