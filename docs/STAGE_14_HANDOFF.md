# Voica Stage 14 Final Handoff

状态：**FINAL / ACCEPTED**

下一阶段：**Stage 15 — UNBLOCKED only after PR #24 merge + main verification**

日期：2026-10-10

## 1. 接管原则

GitHub 当前仓库是唯一事实来源。

Stage 15 开始任何修改前必须重新核对：

- `main` HEAD
- PR #24 最终 merged 状态
- GitHub Actions / checks
- `AGENTS.md`
- `docs/STAGE_14_FREEZE.md`
- `docs/STAGE_14_HANDOFF.md`
- `docs/STAGE_14_7A_PRODUCTION_SIGNING.md`
- `docs/STAGE_14_7B_STABILITY.md`
- `docs/POST_V1_CANONICAL_AUDIO_OPTIMIZATION_PLAN.md`
- `docs/ROADMAP_STAGE_13C_PLUS.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V2.md`
- Room v12 schema 与完整 migration lineage
- app versionCode / versionName
- production model-channel 当前 HEAD
- 当前 BLE / protocol / audio / canonical timeline 实现

聊天记录只能作为交接线索，不得替代 GitHub 当前事实。

## 2. Stage 14 最终 V1.0 基线

最终正式产品源 HEAD：

`3ecde11b3bafff438b76e691dd812457ed8ddee3`

最终发布身份：

- Application ID：`io.github.ioannes78.voica`
- versionCode：`88`
- versionName：`1.0.0`
- ReleaseQa：`1.0.0-export-qa`
- ABI：arm64-v8a
- Room：v12
- minSdk：26
- targetSdk：37
- sherpa-onnx：1.13.8

用户已明确完成最终正式签名 `1.0.0` 真机验证：

**“Final 1.0.0 冒烟测试通过”**

Freeze/Handoff 后续收口提交不得改变上述已验收二进制行为。

## 3. 最终正式签名产物

正式 APK：

- `Voica-V1-production-signed-apk`
- artifact ID：`11671638628`
- SHA-256：`62c62d4ad93cef3b58a5443181f16d984e397b94785286136e01e163f9f125fe`
- size：`30,651,355 bytes`

正式 AAB：

- `Voica-V1-production-signed-aab`
- artifact ID：`11671848627`
- SHA-256：`a1ac71f07ee129c51ca267492275e6991818441a28a4093b60735f4893c186c1`
- size：`25,602,784 bytes`

Production certificate SHA-256：

`f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`

Production signer 与 public QA signer 不同。

CI provenance：

- `source_head_sha=3ecde11b3bafff438b76e691dd812457ed8ddee3`
- `version_code=88`
- `release_version_name=1.0.0`
- `room_schema=12`
- `abi=arm64-v8a`
- `production_signed=true`

## 4. Final CI / Release Gate

Stage 14 最终 CI：

- Android PR CI `#1162` / run `38057645108` — **SUCCESS**
- Android Full Release Gate `#22` / run `38057645101` — **SUCCESS**
- Production signing job — **SUCCESS**

Final Version Gate 已证明：相对 Stage 14.6 accepted RC behavior source `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`，最终 V1 产品源只允许以下版本元数据变化：

- versionCode 87 → 88
- versionName `1.0.0-rc3-r2` → `1.0.0`

其余 app/core/engine/build behavior source 必须保持一致。

## 5. V1 Release signing boundary

后续 Release 必须继续保护：

- production private key / keystore / password 不进入 Git；
- public QA key 不得作为 production signer；
- production signing inputs 必须完整配置；
- CI signer fingerprint 必须可审计；
- APK/AAB 必须校验真实 production certificate；
- runner 临时 keystore 构建结束必须清理；
- 正式 Release provenance 继续记录 version、Room、ABI、model pin、artifact hashes、signer fingerprint。

Stage 15 不应修改 signing/version pipeline；如确需修改，必须作为独立 release-infrastructure 变更重新验收。

## 6. R8 / Native 冻结

Stage 15 及后续不得无证据破坏：

- Release `isMinifyEnabled=true`；
- Release `isShrinkResources=true`；
- arm64-v8a packaging；
- Sherpa JNI keep contract；
- `libsherpa-onnx-jni.so` 保留；
- `libonnxruntime.so` 保留；
- standalone Sherpa C/C++ API libs 不进入 APK/AAB；
- release mapping/configuration 必须保留用于 crash 分析。

## 7. Security / Privacy 冻结

必须继续保持：

- system backup disabled；
- Android data extraction / transfer exclusion；
- cleartext disabled；
- Provider credentials 使用 Android Keystore + encrypted app-private store；
- FileProvider non-exported；
- credential 不进入 Room / export /日志；
- production model pin 不追踪 mutable main URL。

Stage 15 BLE realtime 不得因为调试方便放宽以上边界。

## 8. Room / 内容生命周期

Stage 14 Final Room：**v12**。

Stage 15 若无需持久化新状态，应避免无必要升 schema。

若确需新增 Room 数据：

- 必须有明确产品需求；
- additive/受控 migration；
- 完整 v1→新版本 migration lineage；
- schema 来自真实 Room/KSP；
- 不允许 destructive fallback。

Stage 13B/13C 已冻结的 Current/Candidate、durable attention、process recovery、transcription/speaker enrichment 生命周期继续有效。

## 9. Production model channel

Final Freeze 核验：

- repo：`ioannes78/voica-model-channel`
- main HEAD：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- production manifest version：`7`
- production manifest digest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

Stage 14 未执行新的 model-channel promotion。

Stage 15 BLE realtime audio 不需要为自身提前修改 ASR 模型矩阵或 production manifest。

## 10. 真实长录音稳定性基线

权威记录：`docs/STAGE_14_7B_STABILITY.md`。

已通过：

- 30:48 — PASS
- 64:27 — PASS
- 121:07 — PASS

对应 SenseVoice / diarization RTF 已记录在 Freeze 与 14.7B 文档。

无 crash / ANR / hang / reported OOM；后台/息屏、播放、搜索、导出、持久化均通过。

RAM/PSS、CPU、thermal、storage quantitative telemetry 当时未 instrumentation，必须继续诚实标记为 N/A，不得继承为“已测指标”。

## 11. Canonical WAV Post-V1 P0 计划

用户已明确提出：

- 标准 WAV 生成速度优化；
- 标准 WAV 生成界面优化；
- 取消独立“取消生成标准 WAV”大边框按钮；
- 生成过程增加进度显示。

权威规划：

`docs/POST_V1_CANONICAL_AUDIO_OPTIMIZATION_PLAN.md`

该计划当前仅是 **Post-V1 P0 规划项**，没有进入 V1.0 binary。

Stage 15 不得未经明确确认将该优化静默混入 BLE realtime 开发；需要实施时应单独确认范围、benchmark 方案和 UI/UX，再建立独立修改链。

## 12. Stage 15 正式边界

路线图下一阶段：

**Stage 15 — BLE 实时音频链路**

核心链：

`BLE TYPE=1 Audio → Opus → PCM → realtime canonical timeline`

重点：

- realtime audio continuity；
- packet/drop diagnostics；
- reconnect；
- timeline consistency；
- bounded buffering；
- lifecycle；
- 不提前实现完整 streaming ASR 产品 UI。

Stage 15 必须继续保护既有 Stage 2 BLE serialization / generation / MTU / notification parser 事实以及 Stage 7+ canonical sample timeline。

## 13. Stage 16A 接口前置约束

虽然 Stage 15 不实现完整 ASR，但 realtime PCM/timeline 必须能被 Stage 16A 直接消费。

Stage 15 开发前必须重新读取：

`docs/STAGE_16_STREAMING_CONTRACT_V2.md`

不得通过离线 chunk 模拟宣称 true streaming ASR。

Stage 15 的媒体时间链、buffering、disconnect/finalization 契约需要为 Stage 16A 的 Interim / Stable / Final 生命周期保留清晰边界。

## 14. PR #24 merge 后门禁

Final Freeze/Handoff/阶段状态提交后可将 PR #24 标记 Ready 并合并，但 merge 后仍必须：

1. 核验新的 `main` HEAD 与 PR #24 merged 状态；
2. 核验适用 post-merge CI/checks；若 workflow 无 push trigger，明确记录，不伪造 run；
3. 确认 `docs/STAGE_14_FREEZE.md` / `docs/STAGE_14_HANDOFF.md` 已进入 `main`；
4. 确认 versionCode / versionName 仍为 `88 / 1.0.0`；
5. 确认 Room 仍为 v12；
6. 确认 production model channel 仍为 `be74c706...`；
7. 确认 Stage 14 最终正式签名 artifact hashes 与 Freeze 一致；
8. 确认没有 Freeze/Handoff 后产品代码漂移；
9. 完成以上核验后，Stage 15 才真正解锁。