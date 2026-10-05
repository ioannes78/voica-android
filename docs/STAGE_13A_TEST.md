# Voica Stage 13A — 本地语音引擎 V2 真机验收

状态：**QA5 候选 / 待用户真机验收 / 未冻结**

## 1. QA5 候选基线

- 分支：`stage13a-local-speech-engine-v2`
- PR：#15（Draft / `[APK]`）
- HEAD：`3b13e96d8b8a65d2c85e7454c43053b1efb93650`
- versionCode：45
- versionName：`0.13.0-stage13a-qa5`
- Room schema：7
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- Android PR CI：#681 success
- QA Application ID：`io.github.ioannes78.voica.qa`
- production model channel：保持不变

QA5 按**全新安装**验收，不做旧 QA 版本覆盖升级兼容测试。

## 2. 最终产品模型矩阵

### 离线录音文件转写

- [ ] SenseVoice INT8：快速 / 默认
- [ ] Qwen3-ASR 0.6B INT8：高质量
- [ ] FireRedASR2 不出现在产品模型列表与离线转写设置

### 未来实时转写

- [ ] Small Bilingual Zipformer INT8：轻量 / 默认
- [ ] Chinese Large CTC INT8：高质量
- [ ] Large Transducer 不出现在产品模型列表与实时转写设置
- [ ] 不显示 AUTO / Modified Beam / Max Active Paths 等 Transducer 产品选项

### 说话人分离

- [ ] Pyannote Segmentation 3.0 INT8 + CAM++
- [ ] ERes2Net 不出现在产品模型列表与说话人设置
- [ ] 用户不需要选择 embedding 模型

### 其它

- [ ] Silero VAD
- [ ] CT-Transformer punctuation

## 3. 全新安装与模型页

1. 卸载当前 QA App。
2. 安装 QA5 APK。
3. 打开设置 → 本地模型 / 开发选项。
4. 使用 Stage 13A 候选源并刷新模型列表。

确认：

- [ ] App 正常启动，无闪退
- [ ] 最终产品只接受：Silero、CT、SenseVoice、Qwen3-ASR、Small Bilingual、Large CTC、Pyannote、CAM++
- [ ] FireRedASR2 不可见
- [ ] Chinese Large Transducer 不可见
- [ ] ERes2Net 不可见
- [ ] production channel 未被修改
- [ ] 模型下载失败/取消后不会显示伪“已安装”
- [ ] 模型验证失败时 App 不退出，并显示可理解错误

## 4. “本地语音识别”页面产品化

进入：设置 → 本地语音识别。

### 离线转写（默认展开）

- [ ] 仅显示 SenseVoice / Qwen3-ASR 两个选择
- [ ] 默认 SenseVoice
- [ ] 当前选择清晰可见
- [ ] SenseVoice 选中时可展开语言、ITN
- [ ] Qwen 选中时可展开 maxTotalLen / maxNewTokens / temperature / topP / seed / hotwords

### 实时转写（默认折叠）

- [ ] 仅显示 Small Bilingual / Large CTC
- [ ] 默认 Small Bilingual
- [ ] 明确说明该设置用于未来“录音 + 实时转写”，不影响当前录音文件离线转写

### 说话人分离（默认折叠）

- [ ] 自动说话人分离开关存在
- [ ] 人数可选 Auto / 1 / 2 / 3 / 4 / 5+
- [ ] 高级设置可调 clustering threshold / 跨分块相似度阈值
- [ ] 不显示 CAM++ / ERes2Net 模型选择器

### 性能与语音检测（默认折叠）

- [ ] 性能模式可调
- [ ] CPU threads 可调
- [ ] VAD threshold / min silence / min speech / max speech 可调

### 产品文案

- [ ] 不显示“第一遍识别 / 第二遍识别 / 二遍 ASR / first-pass / second-pass”等工程术语
- [ ] 不显示 Speech Benchmark / Diarization Benchmark 产品入口

## 5. 统一离线转写入口

进入任意本地录音详情 → 转写。

- [ ] 只显示一个主要动作：`开始离线转写`
- [ ] 不再显示“快速转写 / 高质量转写”两个独立按钮
- [ ] 当前离线模型由设置页决定
- [ ] 运行时显示实际模型名

### SenseVoice

设置 SenseVoice 后：

- [ ] 可直接开始离线转写
- [ ] 运行状态显示 `离线转写 · SenseVoice`
- [ ] 结果显示 `SenseVoice · 快速`
- [ ] 不要求 Small Bilingual / Large CTC 才能完成正文识别

ITN=关闭：

- [ ] 使用 CT-Transformer 标点
- [ ] 标点与正文保持正常段落关系
- [ ] 不出现独立标点 speaker 段

ITN=开启：

- [ ] 使用 SenseVoice 原生 ITN/标点
- [ ] 不重复运行 CT
- [ ] 不出现重复标点

### Qwen3-ASR

设置 Qwen3-ASR 后：

- [ ] 可直接开始离线转写
- [ ] 运行状态显示 `离线转写 · Qwen3-ASR`
- [ ] 结果显示 `Qwen3-ASR · 高质量`
- [ ] Qwen 最终文字不被 Small Bilingual 的 alignment 文本覆盖
- [ ] 没有 Small Bilingual timing model 时仍可完成转写，并退化为安全时间轴
- [ ] 有 Small Bilingual 时可作为后置时间轴对齐辅助
- [ ] alignment 失败只作为 warning，不把 Qwen 正文转写标记失败

## 6. 自动说话人分离 + CAM++

设置 → 本地语音识别 → 自动说话人分离 = 开。

- [ ] 转写完成后自动触发
- [ ] 离开录音详情页后仍能完成
- [ ] 自动任务不重复创建
- [ ] 已有兼容 completed diarization 时可复用并重新做 speaker alignment
- [ ] 手动重新运行 diarization 使用 CAM++
- [ ] 失败可重试，不无限自动重试

### Auto Speaker 质量门槛

至少准备：

1. 单人录音
2. 两人对话
3. 3–4 人多人录音

确认：

- [ ] 单人录音不会明显碎成大量 speaker
- [ ] 两人录音可稳定区分主要两人
- [ ] 3–4 人录音不会明显合并/碎裂到不可用
- [ ] 固定人数 1 / 2 / 3 / 4 严格遵守
- [ ] 5+ 使用受控上限

若单人仍明显被拆成很多 speaker，本项视为 QA5 blocker，需要继续调 clustering / stitching / anchor 聚合 / speaker merge。

## 7. 核心回归

- [ ] BLE 连接 / 自动连接 / 电量 / 容量 / Firmware
- [ ] 开始 / 暂停 / 继续 / 停止录音
- [ ] 设备文件刷新 / OPUS / WAV 下载 / 删除
- [ ] 本地录音库 / 导入 / canonical WAV
- [ ] 播放 / seek / Audio Focus / Mini Player
- [ ] SenseVoice 离线转写
- [ ] Qwen3-ASR 高质量离线转写
- [ ] Stage 10 时间轴 / 播放同步
- [ ] 自动说话人分离 / speaker alignment
- [ ] 转写阅读稿 / 编辑 / 版本 / 搜索
- [ ] AI 总结 / Provider / Model 选择
- [ ] 全局任务状态
- [ ] BLE Diagnostics

## 8. QA5 通过条件

以下全部满足后，用户明确回复 `测试通过`：

- [ ] QA5 页面与模型矩阵符合最终产品设计
- [ ] SenseVoice 真机转写通过
- [ ] Qwen3-ASR 真机转写通过
- [ ] CAM++ Auto Speaker 的 1 / 2 / 3–4 人场景达到可用水平
- [ ] 核心功能无新增阻断回归
- [ ] production model channel 未修改

在用户明确 `测试通过` 前：

- 不创建 Freeze/Handoff
- 不 merge PR #15
- 不进入 Stage 13B
- 不提升 Stage 13A candidate 到 production model channel
