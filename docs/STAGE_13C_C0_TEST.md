# Stage 13C C0 真机基线测试

状态：C0 Profiling / Baseline QA

本轮只验证性能观测链和取得优化前基线，不验证 C1 以后任何性能优化。

## 1. 测试构建

- 分支：`stage13c-diarization-performance`
- 版本：`0.13.2-stage13c-c0-qa`
- Room：v11，不迁移
- production model channel：不修改

## 2. 测试前设置

保持当前正式说话人分离模型链：

`Silero VAD → Pyannote Segmentation 3.0 INT8 → CAM++ → clustering / stitching → alignment`

第一轮建议保持：

- 说话人数：自动
- 性能档位：日常实际使用的默认档
- 线程数：自动/默认
- 不在不同样本之间切换模型或高级参数

## 3. C0-A 快速定位集

至少准备三段约 5–10 分钟录音：

1. 单人连续讲话；
2. 两人对话；
3. 两人或多人快速交替、短句较多。

对每个样本：

1. 确认 canonical audio 已就绪；
2. 运行正常离线转写，让自动说话人分离自然执行；
3. 等待说话人分离和自动 alignment 完成；
4. 简单检查转写正文和说话人结果仍可正常查看；
5. 到系统文件管理器打开 `Downloads/Voica/Diagnostics/`；
6. 找到最新 `diarization-benchmark-<benchmarkId>.json`；
7. 将 JSON 文件保留并提交用于 Stage 13C C0 分析。

QA 构建会在同一 benchmark 更新时覆盖同名 JSON，因此以最终文件为准。

## 4. JSON 必查字段

报告至少应包含：

- `outcome`
- `totalElapsedMs`
- `realTimeFactor`
- `speechRealTimeFactor`
- `phases`
- `workload.vadSegmentCount`
- `workload.windowCount`
- `workload.totalWindowSamples`
- `workload.uniqueWindowSamples`
- `workload.overlapProcessedSamples`
- `workload.anchorEmbeddingCallCount`
- `workload.anchorEmbeddingTotalSamples`
- `resources.sampledPeakPssKb`
- `resources.processCpuMs`
- `resources.maxThermalStatus`
- `environment`
- `models`
- `config`
- `durableRunLineage.sourceCanonicalSha256`
- `durableRunLineage.canonicalProfileId`
- `durableRunLineage.pipelineVersion`
- `durableRunLineage.modelManifestDigest`
- `durableRunLineage.configSnapshot`

其中 `NATIVE_DIARIZATION` 是 sherpa 原生聚合阶段，不拆成虚假的 Pyannote / internal CAM++ / clustering 子耗时。

`sampledPeakPssKb` 是阶段边界采样峰值，不宣称为绝对进程峰值。

## 5. 第一轮分析目标

三份 JSON 到齐后优先回答：

1. `NATIVE_DIARIZATION` 占总耗时多少；
2. `ANCHOR_EMBEDDING` 占总耗时多少；
3. `VAD_ENGINE` 占总耗时多少；
4. overlap 重复样本比例多少；
5. anchor embedding 调用次数及处理样本量是否异常；
6. 单人、双人、快速轮换三类 workload 差异；
7. PSS / CPU / thermal 是否存在明显异常。

根据数据决定 C1 VAD reuse 与 C2 embedding reuse 的实际开发优先级。

## 6. 本轮不做

- 不修改 Pyannote / CAM++ 模型；
- 不修改 60s / 10s 默认窗口；
- 不修改 clustering 参数；
- 不修改线程默认值；
- 不实现 1-speaker fast path；
- 不修改 Room schema；
- 不修改 production model channel；
- 不创建 Stage 13C Freeze/Handoff；
- 不合并 PR。
