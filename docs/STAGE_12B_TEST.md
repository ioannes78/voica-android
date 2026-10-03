# Voica Stage 12B — 本地录音库增强真机验收

状态：**用户真机验收通过**

日期：2026-10-04

## 1. 最终验收结论

用户最终明确确认：

**“测试通过”**

最终真机候选：

- 分支：`stage12b-local-library`
- 实现 QA HEAD：`d161cf0404bcc8fd8c3a0a9817377789a10c5598`
- PR：#13（验收时仍为 Draft）
- versionCode：39
- versionName：`0.12.3-stage12b-qa3`
- QA Application ID：`io.github.ioannes78.voica.qa`
- Room schema：5
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- GitHub Actions：run `37142129654` / #570 — success
- Artifact：`Voica-qa-apk` / ID `11280668651`
- Artifact digest：`sha256:6a8bbdfe1d50c96fddd2e6bcbeccb6ee650db07b036e9c7d25d8d70bd343140b`
- APK SHA-256：`a6ec8350683f055ecf8e87ff61bd8e67d9ae75ea85c7dddc0d53e8922350e2fd`

## 2. 覆盖安装与数据库升级

- [x] Stage 12A QA 覆盖安装到 Stage 12B
- [x] App 正常启动
- [x] Room v4 → v5 显式 migration 成功
- [x] 既有录音、转写、说话人、AI 总结历史未丢失
- [x] Room schema 1..5 CI 校验通过
- [x] 无 destructive migration

## 3. 本地录音库

- [x] 录音库列表正常
- [x] 元数据搜索正常
- [x] 日期搜索正常
- [x] 排序正常
- [x] 收藏正常
- [x] 文件夹创建/重命名/删除/移动正常
- [x] 删除文件夹不删除录音
- [x] 标签创建/重命名/删除/多标签筛选正常
- [x] 已转写 / 有总结 / 来源等筛选正常
- [x] 多选与当前结果全选正常
- [x] 批量收藏 / 文件夹 / 标签正常
- [x] 500 / 1000 Recording 查询规模回归通过

## 4. 手机音频导入

- [x] Android SAF 导入正常
- [x] 导入原始文件进入 app-private managed storage
- [x] WAV / MP3 / M4A-AAC / FLAC / Ogg Opus 通用解码链可用
- [x] QS668 raw framed Opus 旧链路不回归
- [x] 导入后 canonical WAV 可生成
- [x] 导入后播放 / 转写正常
- [x] 精确 SHA-256 重复检测生效
- [x] 默认不重复导入；用户明确选择后可保留重复副本
- [x] 非法输入不会留下半成品 Recording

## 5. 本地删除生命周期

- [x] 普通本地删除正常
- [x] 播放中删除会先卸载播放
- [x] 转写中删除会取消/等待任务
- [x] 说话人分离中删除会取消/等待任务
- [x] AI 总结中删除会取消/等待任务
- [x] 批量删除顺序执行
- [x] 删除本地 Recording 不删除录音卡远端文件
- [x] DELETING 启动恢复语义正常

## 6. 导出与分享

- [x] canonical WAV 导出正常
- [x] 原始文件导出正常
- [x] Android 10+ 使用 MediaStore Downloads/Voica
- [x] 同名导出冲突使用递增文件名
- [x] 批量导出正常，单项失败不影响后续项
- [x] Android Sharesheet 分享正常
- [x] WAV 使用 audio/wav
- [x] 标准原始文件保持真实 MIME
- [x] QS668 raw Opus 不伪装为标准 Ogg Opus
- [x] FileProvider 只暴露受控 share cache

## 7. 存储空间管理

- [x] 原始音频 / 标准化音频 / 模型 / 数据库与文本 / 临时数据分类统计
- [x] 临时缓存安全清理
- [x] canonical 清理只处理可再生成资产
- [x] 原始音频不被静默删除
- [x] 共享物理路径保护生效
- [x] source lineage SHA/size/path 校验生效
- [x] canonical 删除失败时数据库补偿恢复
- [x] 模型删除继续走 ModelManager / ModelStorage 生命周期

## 8. Recording Detail 与全局播放状态

- [x] 进入已有转写录音时默认显示最后一次已完成转写版本
- [x] 用户可切换历史转写版本
- [x] 转写/说话人状态只显示在真实对应 recordingId 页面
- [x] 历史 Completed 状态不再污染所有录音详情
- [x] 离开录音详情后全局 Mini Player 显示真实播放录音名称
- [x] 播放自然结束后全局 Mini Player 自动消失
- [x] 暂停时全局 Mini Player 保留

## 9. 全局任务状态

- [x] 转写 / 说话人分离 / AI 总结离开对应页面后可见全局任务状态
- [x] 正在任务对应页面内不重复显示全局任务条
- [x] 切换其他 Tab / 退出录音详情后恢复显示
- [x] Completed 保留为未读任务通知
- [x] Failed 保留为未读任务通知
- [x] 点击通知进入对应录音/Tab 后视为已读并消失
- [x] 用户自行进入对应录音/Tab 后同样视为已读

## 10. AI Summary 可靠性与模型选择

- [x] 模型级 structured-output compatibility probe
- [x] strict JSON Schema 优先
- [x] JSON Object / prompt-compatible 有限回退
- [x] JSON/字段/枚举/evidence 错误分类
- [x] 截断输出识别
- [x] 有界定向 repair
- [x] evidence validation 不放宽
- [x] Grok strict 成功路径不回归
- [x] 火山 / 硅基流动兼容性改善
- [x] AI 总结历史版本显示实际 providerNameSnapshot + model
- [x] 历史版本列表显示实际 Provider + Model
- [x] 每次生成 AI 总结可临时选择 Provider
- [x] 每次生成 AI 总结可临时选择 Model
- [x] 本次选择不修改全局默认 Provider / Model
- [x] 中断恢复继续使用原 Summary 保存的 Provider / Model lineage

## 11. Stage 12A 与核心功能回归

- [x] 设备 / 录音库 / 设置导航正常
- [x] BLE 连接与自动连接不回归
- [x] 电量 / 存储 / Firmware 不回归
- [x] 录音开始 / 暂停 / 继续 / 停止不回归
- [x] 设备文件刷新 / 下载 / 删除不回归
- [x] OPUS / WAV / canonical audio 不回归
- [x] 播放 / Audio Focus / 时间轴不回归
- [x] FAST / High Quality 转写不回归
- [x] 说话人分离与 Stage 10 timeline 不回归
- [x] Stage 11 Provider / Summary / Evidence 不回归
- [x] 完整 BLE Diagnostics 保持可用

## 12. 验收结论

Stage 12B 最终 QA3 真机功能测试通过。

本阶段作为冻结基线：

- Room v5
- versionCode 39
- `0.12.3-stage12b-qa3`
- QA implementation HEAD `d161cf0404bcc8fd8c3a0a9817377789a10c5598`
- CI run `37142129654` success

下一子阶段进入：

**Stage 12C — 转写 / AI 总结内容管理 + 统一全文搜索**
