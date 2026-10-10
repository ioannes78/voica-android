# Voica Stage 14 Final Freeze

状态：**FINAL / ACCEPTED / FROZEN**

日期：2026-10-10

## 1. 最终结论

Stage 14 — Voica V1.0 Release Freeze 已完成并通过全部发布门禁：Release 构建、R8/resource shrink、权限/隐私、production model pin、Release CI、正式签名、30/60/120 分钟真实长录音稳定性、最终 `1.0.0` 正式签名包真机冒烟。

用户对最终 `1.0.0` 正式签名 APK 明确确认：

**“Final 1.0.0 冒烟测试通过”**

最终正式产品源 HEAD：

`3ecde11b3bafff438b76e691dd812457ed8ddee3`

其中相对已完成 Stage 14.6 RC / Stage 14.7B 长时验收的行为基线 `c7ccdd77e71daf2f11cb88489bd0b3a0fab3b80b`，产品源变化被 Final Version Gate 严格限制为 `app/build.gradle.kts` 中：

- `versionCode: 87 → 88`
- `versionName: 1.0.0-rc3-r2 → 1.0.0`

除上述版本元数据外，不允许 `app` / `core` / `engine` / Gradle 构建源发生行为漂移。

后续 Final Freeze/Handoff/阶段状态收口提交只允许文档与阶段元数据变化，不得改变上述最终 `1.0.0` 二进制行为。

## 2. V1.0 最终发布身份

- Application ID：`io.github.ioannes78.voica`
- QA Application ID：`io.github.ioannes78.voica.qa`
- versionCode：`88`
- Release versionName：`1.0.0`
- ReleaseQa versionName：`1.0.0-export-qa`
- ABI：`arm64-v8a`
- minSdk：26
- targetSdk：37
- compileSdk：37.1
- Kotlin：2.4.20
- Android Gradle Plugin：9.4.0
- Gradle：9.6
- JDK：17
- sherpa-onnx：1.13.8
- Room schema：`12`
- 默认产品语言：简体中文

V1.0 是当前正式发布基线；Stage 14 明确不再保留 pre-V1 运行时兼容作为正式支持契约。

## 3. 最终正式签名产物

Android Full Release Gate：

- workflow：Android Full Release Gate
- run：`38057645101`
- run number：`#22`
- source HEAD：`3ecde11b3bafff438b76e691dd812457ed8ddee3`
- result：**SUCCESS**
- production signing job：**SUCCESS**

正式 APK：

- artifact：`Voica-V1-production-signed-apk`
- artifact ID：`11671638628`
- artifact ZIP digest：`sha256:270419b762358ec81f386b35b3ee3446d9295e206a96ab28d8456eac4f280fa5`
- APK size：`30,651,355 bytes`
- APK SHA-256：`62c62d4ad93cef3b58a5443181f16d984e397b94785286136e01e163f9f125fe`

正式 AAB：

- artifact：`Voica-V1-production-signed-aab`
- artifact ID：`11671848627`
- artifact ZIP digest：`sha256:1597b2853a265f137dda795a9f16a301e223d64dc0e5cf634c99de4663e22d86`
- AAB size：`25,602,784 bytes`
- AAB SHA-256：`a1ac71f07ee129c51ca267492275e6991818441a28a4093b60735f4893c186c1`

签名 provenance：

- artifact：`Voica-V1-production-signing-provenance`
- artifact ID：`11671638631`
- artifact ZIP digest：`sha256:27dc5ff8bf8ee3b657d45ec4c074002184f7abac6f3c7616889e2ea5fd87d093`
- `production_signed=true`
- production certificate SHA-256：`f972e0b4f37a528a7e667af888f68e0b9470a6dd74e32b865a9d1507554d25e7`

正式证书与 public QA signer 不同；production keystore/private key/password 未进入仓库、未进入构建产物，CI 完成后 runner 临时 keystore 已清理。

上述 APK/AAB 文件 SHA-256 已在 CI provenance 之外再次独立计算并与 provenance 完全一致。

## 4. Final Release CI

最终源 HEAD `3ecde11...` 对应：

- Android PR CI `#1162` / run `38057645108` — **SUCCESS**
- Android Full Release Gate `#22` / run `38057645101` — **SUCCESS**

Full Release Gate 已验证：

- Final V1 source contract；
- RC → Final 仅版本元数据变化；
- ReleaseQa / unsigned Release / production-signed Release 身份；
- APK / AAB applicationId、versionCode、versionName；
- R8 / resource shrink 输出；
- non-debuggable；
- backup disabled；
- cleartext disabled；
- production model pin；
- Sherpa JNI ABI；
- arm64 native packaging；
- Room v1..v12 schema provenance；
- production signing guardrails；
- APK/AAB production signer；
- provenance 生成；
- keystore 清理。

CI SUCCESS 不能替代真机验收；最终真机门禁见本文件第 10 节。

## 5. R8 / APK 大小 / Native Packaging 冻结

Stage 14 最终 Release 保持：

- `isMinifyEnabled = true`
- `isShrinkResources = true`
- arm64-v8a only
- Sherpa JNI Java/Kotlin ABI 受 keep contract 保护
- 保留 `libsherpa-onnx-jni.so`
- 保留 `libonnxruntime.so`
- 移除未使用的 `libsherpa-onnx-c-api.so`
- 移除未使用的 `libsherpa-onnx-cxx-api.so`

后续不得为了继续减小 APK 在没有完整 ASR/VAD/diarization 回归证据的情况下删除 native/runtime 组件或放松 JNI keep 规则。

## 6. 安全 / 隐私冻结

V1.0 保持 Stage 14.4 已验收边界：

- Android system backup disabled；
- Android 12+ data extraction / transfer 明确排除敏感数据；
- `usesCleartextTraffic=false`；
- Provider credential 继续使用 Android Keystore + AES/GCM app-private storage；
- credential 不进入 Room 内容 lineage；
- FileProvider non-exported；
- 分享范围限制在 app 管理的 share/cache 路径；
- production signing private material 永不提交 Git；
- Release 构建不得回退到 public QA signer。

## 7. Room / 数据基线

Stage 14 Final Freeze：Room **v12**。

- 保持完整 v1→v12 migration lineage；
- 不允许 destructive migration；
- V1.0 为当前正式支持的数据/安装基线；
- pre-V1 compatibility 不得重新以隐式 startup workaround 形式回流；
- 后续 schema 变更必须由明确产品需求驱动，使用真实 Room/KSP schema，并新增受控 migration。

Stage 12C/13A/13B/13C 已冻结的原始 ASR、人工 Revision、Candidate、durable attention、speaker enrichment、搜索索引等数据契约继续有效。

## 8. Production model channel 冻结

Final Freeze 前重新核验：

- repository：`ioannes78/voica-model-channel`
- main HEAD：`be74c7065ce22a5f9b207a7cf1c88d3be0e872ec`
- V1 production manifest version：`7`
- V1 production manifest digest：`8bdb505ce97820cb942bc2855c8a099484b7e22f63ec5567b63f2c1c18d567cf`

V1 App 读取 immutable production model pin，不跟踪 mutable `main/manifests/production.json`。

Stage 14 没有执行新的 production model-channel promotion。

任何后续模型新增、替换或 manifest promotion 必须独立重新核验 license/revision/digest/runtime/真机结果并取得明确授权。

## 9. 30 / 60 / 120 分钟真实稳定性门禁

权威记录：`docs/STAGE_14_7B_STABILITY.md`。

最终结果：

- B1 `30:48`：SenseVoice RTF `0.1342`；diarization RTF `0.4275` — **PASS**
- B2 `64:27`：SenseVoice RTF `0.1241`；diarization RTF `0.4267` — **PASS**
- B3 `121:07`：SenseVoice RTF `0.1349`；diarization RTF `0.4582` — **PASS**

三组均确认：

- background / screen-off 正常；
- 无 crash；
- 无 ANR；
- 无 hang；
- 无报告 OOM；
- playback / search / export 正常；
- transcription / diarization 结果持久化正常。

RAM/PSS、CPU、thermal、storage 定量 telemetry 本轮未完成 instrumentation，按真实事实记录为 N/A，不得伪造成已测。

## 10. 最终 `1.0.0` 真机验收

最终正式签名 `1.0.0` APK 来自 Full Release Gate #22，source HEAD `3ecde11...`。

用户执行覆盖安装与最终冒烟后明确确认：

**“Final 1.0.0 冒烟测试通过”**

该 Final Smoke 作为 Stage 14 最终真机门禁，覆盖正式签名连续性、V1 版本升级身份、现有数据/模型/设置保留、播放、短音频 canonical WAV、SenseVoice、说话人分离、搜索、导出、后台/息屏返回、重启持久化与基本稳定性。

由于 Final Version Gate 已证明 RC→Final 产品行为源只改变 versionCode/versionName，Stage 14 不重复要求 30/60/120 分钟长时 soak；长时结果继续由同一行为基线的 Stage 14.7B 证据承接。

## 11. Stage 14 产品冻结重点

Stage 14 最终冻结并保护：

- 统一搜索的 filename / transcription / AI summary 命中与 exact deep link；
- 搜索上下文返回；
- V1 clean baseline；
- export destination 统一与 SAF 自定义目录；
- R8/resource shrink；
- Release signing boundary；
- security/privacy/data extraction；
- immutable production model pin；
- Stage 13C ASR / diarization lifecycle；
- Stage 13B durable task / notification / process recovery；
- Room v12；
- release provenance；
- 30/60/120 分钟真实稳定性基线。

## 12. 已知非阻塞事项：Canonical WAV 性能 / UI

Stage 14.7B 与 Final Smoke 均未发现阻塞发布的 canonical WAV correctness 问题，但长 MP3/压缩音频生成标准 WAV 的等待体验仍有优化空间。

该事项已经单独记录：

`docs/POST_V1_CANONICAL_AUDIO_OPTIMIZATION_PLAN.md`

后续优化目标包括：

- 标准 WAV 生成速度 profiling / Fast Path / I/O / buffer / GC / decode-resample pipeline 优化；
- 播放器卡片内显示生成阶段与真实进度；
- 取消独立占整行的“取消生成标准 WAV”边框按钮；
- 将取消动作合并进生成状态/进度区域；
- 使用 30/60/120 分钟音频做优化前后 A/B benchmark。

该事项是 **Post-V1 非阻塞性能/UX优化**，不回写或改变已验收的 V1.0 Release binary。实施前必须重新走需求/规划/真机验收，不得直接借 Final Freeze 名义修改生产代码。

## 13. 明确未进入 Stage 14 的事项

Stage 14 未实现：

- Stage 15 BLE realtime audio pipeline；
- Stage 16A true streaming ASR；
- Stage 16B Fun-ASR-Nano 文件 ASR V2；
- Stage 17/18 云端文件/实时 ASR；
- Stage 19 本地/云端 Audio LLM 与最终 UI/UX 精修；
- Stage 20 V2 云同步/账号/多设备等；
- Post-V1 Canonical WAV 性能优化实现本身。

## 14. 下一阶段

Stage 14 Final Freeze/Handoff 合并到 `main` 并完成 post-merge 核验后，正式路线图下一阶段解锁为：

**Stage 15 — BLE 实时音频链路**

Stage 15 开始任何编码前必须重新读取：

- `AGENTS.md`
- `docs/STAGE_14_FREEZE.md`
- `docs/STAGE_14_HANDOFF.md`
- `docs/ROADMAP_STAGE_13C_PLUS.md`
- `docs/STAGE_16_STREAMING_CONTRACT_V2.md`
- 当前 `main` / open PR / CI / Room schema / app version
- production model channel 当前 HEAD
- 当前 BLE / protocol / audio / canonical timeline 实现

Post-V1 Canonical WAV 优化计划是独立规划项，不得未经确认静默塞入 Stage 15。