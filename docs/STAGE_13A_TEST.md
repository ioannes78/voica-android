# Voica Stage 13A — 本地语音引擎 V2 真机验收

状态：**QA2 候选 / 待用户真机验收 / 未冻结**

## 1. 候选基线

- 分支：`stage13a-local-speech-engine-v2`
- PR：#15（Draft / `[APK]`）
- versionCode：42
- versionName：`0.13.0-stage13a-qa2`
- QA Application ID：`io.github.ioannes78.voica.qa`
- Room schema：7（v6 → v7 additive migration）
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- Stage 13A 全量候选清单：`candidate-stage13a-all-r1`
- production manifest 在本阶段真机验收前保持不变。
- Stage 16 streaming contract V2 已冻结接口语义，但 Stage 13A 不实现完整实时 BLE 转写 UI。

## 2. 覆盖安装与 Room v6 → v7

- [ ] 从 Stage 12C QA 覆盖安装 QA2
- [ ] App 正常启动，无 destructive migration
- [ ] 既有录音、文件夹、标签、收藏保留
- [ ] 既有转写版本 / Segment / Token / speaker / alignment 保留
- [ ] 既有 AI Summary / Revision / 搜索索引可正常使用
- [ ] 新转写可保存 VAD / first-pass / second-pass / punctuation 的 model revision
- [ ] 新转写可保存 Snapshot V2 requested/effective config
- [ ] Room schema 1..7 CI gate 通过

## 3. 切换 Stage 13A 候选模型清单

1. 设置 → 模型 → 开发选项。
2. 点击“使用 Stage 13A 全量候选”。
3. 完全退出 App 后重新打开。
4. 检查模型列表可看到原 production 模型及新增 Stage 13A 候选。

- [ ] production 未被直接修改
- [ ] Chinese Large Transducer INT8 可见
- [ ] Chinese Large CTC INT8 可见
- [ ] FireRedASR2 CTC INT8 可见
- [ ] Qwen3-ASR 0.6B INT8 可见
- [ ] CAM++ zh-cn 可见
- [ ] SenseVoice 显示语言 / ITN capability
- [ ] CTC 不显示无效 Modified Beam Search 参数
- [ ] Transducer 显示真实 decoder 参数 capability

## 4. 模型下载 / 校验 / 激活

依次测试新增模型，不要求同时把所有大模型常驻手机。

- [ ] Chinese Large Transducer 下载可恢复、SHA/file 校验通过、native smoke 通过、可激活
- [ ] Chinese Large CTC 下载可恢复、SHA/file 校验通过、native smoke 通过、可激活
- [ ] FireRedASR2 CTC 下载可恢复、校验 / native smoke 通过、可激活
- [ ] Qwen3-ASR 0.6B 下载可恢复、校验 / native smoke 通过、可激活
- [ ] CAM++ 下载、校验、embedding smoke 通过、可激活
- [ ] 下载失败/取消不会留下伪“已安装”状态
- [ ] 已激活模型被使用时不会被删除或原位覆盖
- [ ] rollback / 删除旧候选行为正常

## 5. 实时 ASR 三模型（文件转写 first-pass）

使用同一条普通话录音，分别明确选择三个实时模型进行 FAST 转写。

- [ ] Small Bilingual 正常
- [ ] Chinese Large Transducer 正常
- [ ] Chinese Large CTC 正常
- [ ] 明确选定模型时不会静默回退到其它模型
- [ ] Auto 优先 Transducer，不可用时按 CTC → Small 回退
- [ ] 三模型均保持 canonical sample timeline / token timing contract
- [ ] 转写详情显示真实 model/version/revision
- [ ] 新旧 FAST 历史版本互不覆盖

### Transducer Decoder

- [ ] 默认 Greedy Search 正常
- [ ] Modified Beam Search 可选择
- [ ] Max Active Paths 仅在 Modified Beam Search 下显示
- [ ] 修改 Max Active Paths 后真实影响 effective Snapshot
- [ ] “恢复 Decoder 推荐值”回到 greedy_search / 4

### CTC

- [ ] 选择 CTC 时不显示 Modified Beam Search / Max Active Paths
- [ ] CTC 实际使用 greedy_search，不存在伪生效 beam 设置

## 6. 离线 / Final Pass 三模型

使用同一条录音运行 High Quality / 对应离线质量档。

- [ ] SenseVoice Balanced 正常
- [ ] FireRedASR2 High Quality 正常
- [ ] Qwen3-ASR Ultra 正常或明确记录设备资源不满足
- [ ] FireRed / Qwen 不被标记为 true streaming
- [ ] 每次模型切换产生独立 Transcription version
- [ ] second-pass model/version/revision 保存正确
- [ ] punctuation 不重复添加

### SenseVoice

- [ ] Auto language 保持原自动检测行为
- [ ] 中文 / English / 日语 / 韩语 / 粤语强制语言设置可选
- [ ] ITN 开关真实传入 runtime
- [ ] Snapshot 保存 requested/effective language 与 ITN

### Qwen3-ASR

- [ ] maxTotalLen / maxNewTokens 范围限制正常
- [ ] temperature / topP / seed 正常
- [ ] hotwords 输入与恢复默认正常
- [ ] 默认参数保持确定性推荐值
- [ ] 大模型失败/OOM 时不产生伪 completed 转写

## 7. VAD / 性能档位

- [ ] 自动 / 省电 / 均衡 / 性能档位正常
- [ ] 手动线程数上限受 CPU 与安全上限约束
- [ ] VAD threshold 可调
- [ ] min silence 可调
- [ ] min speech 可调
- [ ] max speech duration 可调
- [ ] “恢复 VAD 推荐值”正常
- [ ] requested/effective threads 与 VAD 参数写入 Snapshot V2

## 8. Diarization 模型与人数约束

同一录音分别使用 ERes2Net 与 CAM++。

- [ ] ERes2Net baseline 正常
- [ ] CAM++ challenger 正常
- [ ] 自动模式可完成说话人分离
- [ ] 单人录音 fragmentation 相比旧行为无明显回归
- [ ] 预计说话人数=1 时全局结果严格 1 Speaker
- [ ] 预计说话人数=2 时全局结果严格 2 Speaker
- [ ] 预计说话人数=3 时全局结果严格 3 Speaker
- [ ] 预计说话人数=4 时全局结果严格 4 Speaker
- [ ] 5+ 使用最少 5、受控上限的语义
- [ ] clustering threshold 仅自动模式真实生效
- [ ] stitching cosine threshold 真实生效
- [ ] 多 clean-anchor aggregation 不破坏跨 chunk speaker continuity
- [ ] Diarization Snapshot V2 记录模型、阈值、anchor strategy 与 effective config

## 9. Speech Benchmark

选择至少一条带标准 PCM 的录音；有人工参考文本时填写 reference text。

- [ ] “实时 3 模型”能顺序完成 Small / Transducer / CTC
- [ ] 记录 model/revision/runtime/quantization
- [ ] 记录 first-pass decoder effective 配置
- [ ] 记录 threads / VAD config
- [ ] 记录 wall time / CPU time / RTF
- [ ] 记录 Peak PSS / thermal
- [ ] 记录 first partial / finalization latency
- [ ] 有参考文本时计算 CER/WER
- [ ] Benchmark 不创建或覆盖正式 Transcription version

## 10. Diarization Benchmark

- [ ] “说话人 2 模型”顺序比较 ERes2Net / CAM++
- [ ] 可填写真实说话人数
- [ ] 记录 RTF / CPU / Peak PSS / thermal
- [ ] 记录 detected speaker count / turn / window
- [ ] 计算 speaker count error / fragmentation / merge missing 指标
- [ ] Benchmark 不写入正式 Diarization history

## 11. Stage 16 Streaming Contract V2 回归

- [ ] PARTIAL 必须 `isFinal=false`
- [ ] STABLE 必须 `isFinal=false`
- [ ] FINAL 必须 `isFinal=true`
- [ ] `finishInput()` 后不能继续 accept/decode，除非 reset
- [ ] reset 后 session-relative sample timeline 从 0 重新开始
- [ ] 不为缺失 token timing 的模型伪造 timing
- [ ] Stage 13A 没有提前引入完整 BLE realtime UI/state machine

## 12. 核心功能回归

- [ ] BLE 连接 / 自动连接 / 电量 / 容量 / Firmware
- [ ] 开始 / 暂停 / 继续 / 停止录音
- [ ] 设备文件刷新 / OPUS / WAV 下载 / 删除
- [ ] 本地录音库 / 导入 / canonical WAV
- [ ] 播放 / seek / Audio Focus / Mini Player
- [ ] FAST / High Quality 转写
- [ ] 自动说话人分离
- [ ] Stage 10 时间轴 / 播放同步
- [ ] 转写阅读稿 / 人工修订 / 搜索
- [ ] AI 总结 / Provider / Model 选择
- [ ] 全局任务状态
- [ ] BLE Diagnostics

## 13. Stage 13A 不在本轮宣称完成的项目

- 真实 30 分钟 / 1 小时 / 2 小时完整稳定性 soak 属于 Stage 13B。
- Foreground Service / 锁屏后台 BLE 可靠下载属于 Stage 13B。
- 完整 BLE Audio → Opus → PCM 属于 Stage 15。
- 完整实时 PCM → VAD → streaming ASR → stable/final UI 属于 Stage 16。
- Speaker voiceprint / 跨录音人物身份属于 Stage 20。

## 14. 验收门禁

Stage 13A 在用户明确回复 **“测试通过”** 前：

- 不创建 Freeze/Handoff；
- 不把 Stage 13A candidate 推入 production；
- 不合并 PR #15；
- 不进入 Stage 13B。
