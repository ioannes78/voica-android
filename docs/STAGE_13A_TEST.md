# Voica Stage 13A — 本地语音引擎 V2 真机验收

状态：**QA3 候选 / 待用户真机验收 / 未冻结**

> QA3 必须重新覆盖 QA2 中因模型验证/缺模型而未实际执行的项目。任何 QA2 未测项均不得视为默认通过。

## 1. 候选基线

- 分支：`stage13a-local-speech-engine-v2`
- PR：#15（Draft / `[APK]`）
- versionCode：43
- versionName：`0.13.0-stage13a-qa3`
- QA Application ID：`io.github.ioannes78.voica.qa`
- Room schema：7（v6 → v7 additive migration）
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- Stage 13A 全量候选清单：`candidate-stage13a-all-r1`
- production manifest 在本阶段真机验收前保持不变。
- Stage 16 streaming contract V2 只冻结接口语义；Stage 13A 不实现完整实时 BLE 转写 UI。

## 2. 覆盖安装与历史数据

- [ ] 从上一版 QA2 覆盖安装 QA3，App 正常启动
- [ ] 无 destructive migration
- [ ] 既有录音、文件夹、标签、收藏保留
- [ ] 既有转写版本 / Segment / Token / speaker / alignment 保留
- [ ] 既有 AI Summary / Revision / 搜索索引可正常使用
- [ ] 新 FAST / High Quality 转写可保存 model version/revision 与 Snapshot V2
- [ ] Room schema 1..7 CI gate 通过

## 3. Stage 13A 候选模型清单

1. 设置 → 模型 → 开发选项。
2. 选择“使用 Stage 13A 全量候选”。
3. 完全退出 App 后重新打开。
4. 刷新模型清单。

确认：

- [ ] Chinese Large Transducer INT8 可见
- [ ] Chinese Large CTC INT8 可见
- [ ] FireRedASR2 CTC INT8 可见
- [ ] Qwen3-ASR 0.6B INT8 可见
- [ ] CAM++ zh-cn 可见
- [ ] 原有 Small Bilingual / SenseVoice / VAD / CT punctuation / ERes2Net 保留
- [ ] production channel 未被改写

## 4. QA3 模型下载、验证与启用（QA2 未完成项必须重测）

建议逐个模型测试，不要求所有大模型同时保留。

### 4.1 Chinese Large Transducer

- [ ] 下载可续传并完成
- [ ] 静态 SHA / 文件完整性通过
- [ ] 点击“验证并启用”时 Voica 主 App **不能退出或重启**
- [ ] native runtime 验证通过并成功启用
- [ ] 若验证失败，显示明确的 INIT / INFERENCE / OOM / validator crash / timeout 信息
- [ ] 验证失败后主 App 仍可继续操作其它模型

### 4.2 Chinese Large CTC

- [ ] 下载、静态校验通过
- [ ] native runtime 验证通过
- [ ] 可成功启用
- [ ] 不显示 Modified Beam / Max Active Paths 等 Transducer 专用参数

### 4.3 FireRedASR2 CTC

- [ ] 下载、静态校验通过
- [ ] native runtime 验证通过
- [ ] 可成功启用
- [ ] capability 显示/运行时可获得 token timing
- [ ] 标点使用 CT-Transformer，不出现重复标点

### 4.4 Qwen3-ASR 0.6B

- [ ] 下载、静态校验通过
- [ ] native runtime 验证通过，或明确提示设备 RAM 不满足
- [ ] 验证/OOM 失败时 Voica 主 App 不退出
- [ ] 可成功启用时，模型参数页可正常保存 maxTotalLen / maxNewTokens / temperature / topP / seed / hotwords

### 4.5 CAM++

- [ ] 下载、静态校验通过
- [ ] embedding native smoke 通过
- [ ] 可启用并参加后续 Diarization 对比

### 4.6 通用下载回归

- [ ] 下载取消/失败不会留下伪“已安装”
- [ ] App 重启后下载状态正确
- [ ] 已启用模型使用中不能被原位删除/覆盖
- [ ] 删除旧候选 / rollback 行为正常

## 5. FAST 实时模型（QA2 Large 模型未测项补测）

同一条 1–3 分钟普通话录音分别运行 FAST：

- [ ] Small Bilingual 正常
- [ ] Chinese Large Transducer 正常
- [ ] Chinese Large CTC 正常
- [ ] 明确选择模型时不静默切换到其它模型
- [ ] Auto：Transducer → CTC → Small 回退符合设计
- [ ] 三模型 token timeline 单调、seek/高亮同步正常
- [ ] model/version/revision 与 Snapshot V2 正确

### Transducer Decoder

- [ ] Greedy Search 正常
- [ ] Modified Beam Search 正常
- [ ] Max Active Paths 只在 Modified Beam 下生效
- [ ] 恢复推荐值回到 greedy_search / 4

### CTC

- [ ] 只使用 greedy_search
- [ ] 不出现伪 beam 参数
- [ ] 长句 partial/final 不明显回退

## 6. High Quality 离线三模型 — QA3 核心

测试前至少准备一条 1–3 分钟中文录音，最好含数字、日期、英文词与多个停顿。

### 6.1 SenseVoice Balanced

先确保 **Small/Large Zipformer 均未启用或临时删除**，只保留 VAD、SenseVoice 与需要的 CT punctuation。

- [ ] High Quality 可直接启动，不再提示“缺少 Zipformer”
- [ ] 完成路径为 VAD → SenseVoice，而不是 Zipformer first-pass → SenseVoice
- [ ] 有原生 token timestamp，时间轴 / seek / 当前词高亮正常
- [ ] 默认 ITN=关闭：SenseVoice → CT punctuation，断句正常
- [ ] ITN=开启：使用 SenseVoice 原生 ITN/标点，不再二次运行 CT
- [ ] ITN 开/关均无“每几个字被异常切成一段”的现象
- [ ] Snapshot 正确记录语言与 ITN

### 6.2 FireRedASR2 High Quality

确保所有 realtime Zipformer 均未启用，保留 VAD + FireRed + CT。

- [ ] FireRed 可独立完成 High Quality，不提示缺少 Zipformer
- [ ] FireRed token timestamps 被保存
- [ ] CT-Transformer 只运行一次
- [ ] 标点自然，无 `，，` / `。。` 等重复
- [ ] Stage 10 精确时间轴/播放同步正常
- [ ] second-pass model/version/revision 正确

### 6.3 Qwen3-ASR Ultra

#### A. 不启用 Small Bilingual Transducer

- [ ] 仅 VAD + Qwen 即可完成转写
- [ ] Qwen 原生标点保留
- [ ] 不要求 CT punctuation
- [ ] 不提示缺少 Zipformer/Transducer
- [ ] 无 timing model 时正常退化为 VAD segment timeline
- [ ] 转写、编辑、搜索、AI 总结均可用

#### B. 启用现有 Small Bilingual Transducer

- [ ] Qwen 先完成最终文本
- [ ] Small Bilingual 作为**后置 timing alignment**，不决定 Qwen 最终文字
- [ ] Qwen/Transducer 文本高匹配时生成精确 token timeline
- [ ] Qwen 比 Transducer 多/少少数字时能通过锚点插值
- [ ] 中英混说仍能建立合理时间锚点
- [ ] 匹配率低于安全阈值时放弃伪精确 timeline，退回 VAD segment timeline
- [ ] alignment 失败只给 warning，不把 Qwen transcription 标记失败
- [ ] Qwen 原文和标点不会被 Transducer 文本覆盖

## 7. 自动说话人分离持久化回归（QA2 间歇问题）

设置 → 本地语音 → 自动说话人分离 = 开。

至少对同一条录音重复 3 次，并覆盖以下场景：

- [ ] 在转写页面等待完成：自动运行
- [ ] 点击开始转写后立刻返回录音库：完成后仍自动运行
- [ ] 转写过程中切换到首页/设置页：仍自动运行
- [ ] 转写完成前让 Activity/ViewModel 重建：仍自动运行
- [ ] 自动 Diarization 只创建一次，不重复
- [ ] 已有兼容 completed Diarization 时复用结果，不重跑 embedding
- [ ] 已有 Diarization 但缺 alignment 时自动补 alignment
- [ ] Diarization 失败时显示失败，可手动重试，不形成无限自动重试
- [ ] 转写取消/失败后不会错误触发说话人分离

可选强回归：

- [ ] 转写完成后、说话人分离尚未开始时杀掉 App，再打开后 pending post-processing 能恢复

## 8. Diarization 模型 / 人数约束（包含 QA2 未测 CAM++）

同一条多人录音分别使用 ERes2Net 与 CAM++：

- [ ] ERes2Net baseline 正常
- [ ] CAM++ challenger 正常
- [ ] Auto speaker count 正常
- [ ] 单人录音不被明显碎成大量 speaker
- [ ] 固定 1 / 2 / 3 / 4 人严格遵守
- [ ] 5+ 使用受控上限
- [ ] clustering threshold 只在 Auto 模式生效
- [ ] stitching cosine threshold 生效
- [ ] clean-anchor aggregation 不破坏跨 chunk speaker continuity
- [ ] Snapshot V2 保存模型、阈值、人数与 effective config

## 9. VAD / 性能参数

- [ ] 自动 / 省电 / 均衡 / 性能档位正常
- [ ] 手动 threads 受 CPU 与安全上限约束
- [ ] VAD threshold / min silence / min speech / max speech 可调
- [ ] 恢复 VAD 推荐值正常
- [ ] requested/effective threads 与 VAD 参数进入 Snapshot V2

## 10. Speech Benchmark（QA2 未执行项补测）

至少选择一条标准 PCM 录音：

- [ ] Small / Large Transducer / Large CTC 三模型顺序完成
- [ ] 记录 model/revision/runtime/quantization
- [ ] 记录 decoder effective config
- [ ] 记录 wall time / CPU time / RTF
- [ ] 记录 Peak PSS / thermal
- [ ] 记录 first partial / final latency
- [ ] 有参考文本时计算 CER/WER
- [ ] Benchmark 不创建正式 Transcription version

## 11. Diarization Benchmark（QA2 未执行项补测）

- [ ] ERes2Net / CAM++ 顺序完成
- [ ] 可填写真实 speaker count
- [ ] 记录 RTF / CPU / Peak PSS / thermal
- [ ] 记录 speaker count / turn / window
- [ ] 计算 speaker count error / fragmentation / merge missing
- [ ] Benchmark 不写入正式 Diarization history

## 12. Stage 16 Streaming Contract V2 回归

- [ ] PARTIAL → `isFinal=false`
- [ ] STABLE → `isFinal=false`
- [ ] FINAL → `isFinal=true`
- [ ] `finishInput()` 后不可继续 accept/decode，除非 reset
- [ ] reset 后 session timeline 从 0 重新开始
- [ ] 不为原生无 timestamp 的 Qwen 直接伪造 timestamp
- [ ] Stage 13A 未提前加入完整 BLE realtime UI/state machine

## 13. 核心功能回归

- [ ] BLE 连接 / 自动连接 / 电量 / 容量 / Firmware
- [ ] 开始 / 暂停 / 继续 / 停止录音
- [ ] 设备文件刷新 / OPUS / WAV 下载 / 删除
- [ ] 本地录音库 / 导入 / canonical WAV
- [ ] 播放 / seek / Audio Focus / Mini Player
- [ ] FAST / High Quality 转写
- [ ] Stage 10 时间轴 / 播放同步
- [ ] 自动说话人分离 / speaker alignment
- [ ] 转写阅读稿 / 编辑 / 版本 / 搜索
- [ ] AI 总结 / Provider / Model 选择
- [ ] 全局任务状态
- [ ] BLE Diagnostics

## 14. QA3 必须重点截图/记录的失败信息

若任一候选模型仍不能启用，请截图完整错误文字。QA3 应能区分：

- `NATIVE_INIT_FAILED`
- `NATIVE_INFERENCE_FAILED`
- `OUT_OF_MEMORY`
- `VALIDATOR_PROCESS_CRASHED`
- `TIMEOUT`
- 静态包/文件完整性错误

无论上述哪一种错误，**Voica 主 App 都不应直接退出**。

## 15. Stage 13A 本轮不宣称完成

- 30 分钟 / 1 小时 / 2 小时完整 soak：Stage 13B
- Foreground Service / 锁屏后台 BLE 下载可靠性：Stage 13B
- 完整 BLE Audio → Opus → PCM：Stage 15
- 完整实时 PCM → VAD → streaming ASR → UI：Stage 16
- Speaker voiceprint / 跨录音身份：Stage 20

## 16. 验收门禁

在用户明确回复 **“测试通过”** 前：

- 不创建 Freeze/Handoff；
- 不把 Stage 13A candidate 推入 production；
- 不合并 PR #15；
- 不进入 Stage 13B。
