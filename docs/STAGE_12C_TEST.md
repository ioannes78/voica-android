# Voica Stage 12C — 转写 / AI 总结内容管理 + 统一全文搜索真机验收

状态：**用户真机验收通过 / FROZEN**

## 1. 候选基线

- 分支：stage12c-content-management-search
- PR：#14
- versionCode：40
- versionName：0.12.4-stage12c-qa1
- QA Application ID：io.github.ioannes78.voica.qa
- Room schema：6
- ABI：arm64-v8a
- sherpa-onnx：1.13.8
- 功能实现基线：`3f7a6a372e561c14090e5c51868a4c11600a01e6`
- 自动化验证：GitHub Actions #592 / run `37167326317` — success
- 最终 QA CI：GitHub Actions #593 / run `37169848489` — success
- QA Artifact：`Voica-qa-apk` / ID `11291100984`
- Artifact digest：`sha256:3d10191d1456e9f639def7218d47c42813e354f024a6a28db3e4d9e09deba393`
- APK SHA-256：`89a9df3a610019f8fe9d750966ea4497b09ab71a19f60816b8144866d6942d49`

## 2. 覆盖安装与 Room v5 → v6

- [x] 从 Stage 12B QA 覆盖安装
- [x] App 正常启动，无 destructive migration
- [x] 既有录音保留
- [x] 既有 FAST / High Quality 转写版本保留
- [x] Segment / Token / speaker / alignment 保留
- [x] 既有 AI 总结、Provider/Model、Evidence、chunk 保留
- [x] 文件夹 / 标签 / 收藏保留
- [x] 首次启动出现搜索索引建立状态时可正常完成
- [x] Room schema 1..6 CI gate 通过

## 3. 转写版本管理

准备同一录音至少两个已完成转写版本。

- [x] 可以切换两个转写版本
- [x] 手动选定旧版本，退出详情再进入仍保持该版本
- [x] 重启 App 后仍恢复用户选定版本
- [x] 可重命名转写版本
- [x] 删除不被 AI 总结引用的转写版本成功
- [x] 删除当前版本后自动回退到仍存在版本
- [x] 某转写仍被 AI 总结引用时删除被明确阻止
- [x] 阻止删除时 AI 总结没有被 Room cascade 一并删除
- [x] 删除转写版本不删除原始录音

## 4. 错误分段人工整理

选择一条存在“一句话从中间被 ASR 切成两段”的录音。

- [x] 进入“编辑转写”
- [x] “合并下段 / 合并上段”可以把错误断句恢复为一个阅读段
- [x] 中文合并后不会无故插入空格
- [x] 光标位于段落中间时可“从光标拆分”
- [x] 没有可验证 token 边界的人工拆分不会伪造精确逐词时间
- [x] “整理段落”可自动合并同一说话人、句意未结束的相邻段
- [x] “整理段落”不会跨明确不同说话人自动合并
- [x] 保存后显示“已人工整理”
- [x] 模型原始 ASR Segment / Token 仍可恢复

## 5. 阅读 / 时间轴双模式

- [x] 有人工修订时默认进入“阅读”模式
- [x] 阅读模式按自然段显示，不显示时间戳
- [x] 阅读模式不做当前词逐词高亮
- [x] 阅读模式仍可显示说话人名称
- [x] 可切换到“时间轴”
- [x] 时间轴模式仍保持 Stage 10 播放同步
- [x] 当前句/当前词高亮不回归
- [x] 人工修改没有改变原始 absolute canonical sample timeline
- [x] 修订历史可切换旧修订
- [x] 删除修订后能回退到前一修订或模型原文
- [x] “恢复模型原文”正常

## 6. 转写复制 / 分享 / 导出

- [x] 阅读稿文本可长按选择并使用系统复制
- [x] “复制阅读稿”复制当前有效文本
- [x] “分享阅读稿”打开 Android Sharesheet
- [x] 短文本以 text/plain 分享
- [x] 长文本可通过受控临时文件分享
- [x] 导出 TXT 正常
- [x] 导出 Markdown 正常
- [x] Android 10+ 文件进入 Downloads/Voica
- [x] Android 8–9 使用系统另存为
- [x] 导出的是当前有效修订，不是旧修订
- [x] 阅读稿导出默认不引入伪造时间戳

## 7. AI 总结版本与人工修订

准备同一转写至少两个 AI 总结版本。

- [x] 历史列表显示真实 Provider + Model
- [x] 手动选择旧总结，退出/重进后保持选择
- [x] 重启 App 后保持用户选择
- [x] 可编辑标题
- [x] 可编辑 overview
- [x] 可修改章节标题
- [x] 可修改结构化 item
- [x] 可新增 item / 章节
- [x] 可删除 item / 章节
- [x] 可调整章节顺序
- [x] 人工修改项明确标记“人工修改”
- [x] 人工新增项明确标记“人工新增”
- [x] 人工修改后不会把原 Evidence 伪装成新文本的有效 Evidence
- [x] 人工新增项没有伪造模型 Evidence
- [x] 修订历史可查看 / 切换 / 删除
- [x] 可恢复 AI 原始结果
- [x] 可删除当前 AI 总结版本
- [x] 删除总结版本不删除录音或转写
- [x] 删除当前总结版本后回退到仍存在版本

## 8. AI 总结复制 / 分享 / 导出

- [x] 总结正文可长按选择并系统复制
- [x] “复制总结”复制当前有效版本
- [x] “分享总结”打开 Android Sharesheet
- [x] TXT 导出正常
- [x] Markdown 导出正常
- [x] 导出保留标题、overview、章节结构
- [x] 导出显示实际 Provider / Model
- [x] 人工修改 / 人工新增标记保留

## 9. 统一全文搜索

首次升级后等待索引完成再测试。

### 中文

- [x] 搜索“供应链”
- [x] 搜索“供应”
- [x] 搜索“链”
- [x] 搜索“供应链风险”
- [x] 搜索“供应链 风险”
- [x] 搜索“项目进度”
- [x] 连续中文无空格可命中

### 中英混合与安全输入

- [x] 搜索“供应链 OpenAI”
- [x] OpenAI 大小写不影响结果
- [x] 数字关键词可搜索
- [x] 全角 / 半角输入行为正常
- [x] 中文标点不会造成 FTS 语法错误
- [x] emoji 不会造成崩溃
- [x] 引号、星号、AND、OR、减号、括号不会变成原始 MATCH 语法

### 类型和导航

- [x] 全部
- [x] 录音
- [x] 转写
- [x] AI 总结
- [x] 文件夹
- [x] 标签
- [x] 转写命中打开正确 Recording
- [x] 转写命中打开正确 Transcription version
- [x] 转写命中滚动到对应阅读段 / 时间轴段
- [x] AI 总结命中打开正确 Summary version
- [x] Folder 命中返回相应录音库筛选
- [x] Tag 命中返回相应录音库筛选

### 索引生命周期

- [x] 修改转写并保存后，新文本可以搜到
- [x] 被替换的旧当前修订不再出现在全局当前搜索
- [x] 恢复模型原文后索引随之恢复
- [x] 修改 AI 总结后新内容可搜索
- [x] 删除修订后索引与当前有效内容一致
- [x] 删除转写版本后无孤立结果
- [x] 删除 AI 总结版本后无孤立结果
- [x] 删除 Recording 后对应转写 / 总结搜索结果全部消失
- [x] 录音改名后新名称可搜索
- [x] Folder / Tag 改名后搜索结果更新

## 10. 性能与长文本

- [x] 30 分钟转写阅读和编辑无明显卡顿
- [x] 60 分钟转写阅读和编辑无明显卡顿
- [x] 120 分钟转写使用 LazyColumn，不因单个超大 TextField 卡死
- [x] 编辑时不会每个按键都写 Room
- [x] 搜索输入有 debounce，不会逐键全库扫描
- [x] 500 / 1000 Recording 搜索可正常完成
- [x] 搜索索引重建期间 UI 明确显示状态，不误显示为空结果

## 11. 核心功能回归

- [x] BLE 连接 / 自动连接
- [x] 电量 / 容量 / Firmware
- [x] 开始 / 暂停 / 继续 / 停止录音
- [x] 设备文件刷新 / OPUS / WAV 下载 / 删除
- [x] 手机音频导入
- [x] canonical WAV 生成
- [x] 本地录音删除生命周期
- [x] 播放 / Audio Focus / Mini Player
- [x] FAST / High Quality 转写
- [x] 自动说话人分离
- [x] Stage 10 时间轴 / 播放同步
- [x] Speaker rename
- [x] AI 总结生成
- [x] Grok strict structured output
- [x] 火山 / 硅基流动等兼容模式
- [x] 临时 Provider / Model 选择
- [x] 全局任务未读状态
- [x] BLE Diagnostics

## 12. 验收结论

用户最终明确确认：

**“测试通过”**

Stage 12C QA1 真机验收通过。

冻结基线：

- versionCode 40
- `0.12.4-stage12c-qa1`
- Room v6
- 功能实现基线 `3f7a6a372e561c14090e5c51868a4c11600a01e6`
- QA/document HEAD `d1244f1b00747d6b03d1099d41666e7f6adf0f42`
- Freeze 内容提交 `0c052ead13bf83826707c1e390ca4d3b0345b280`
- CI #593 / run `37169848489` success
- Artifact ID `11291100984`
- APK SHA-256 `89a9df3a610019f8fe9d750966ea4497b09ab71a19f60816b8144866d6942d49`

下一阶段：**Stage 13 — 稳定性与长录音专项**。
