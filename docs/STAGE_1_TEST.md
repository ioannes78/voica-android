# Voica Stage 1 测试清单

## 自动测试

- [x] CRC-16/XMODEM 标准向量
- [x] Sequence 255 → 0
- [x] 2-2 36B Golden Frame
- [x] 2-12 Segment Frame
- [x] Delete-one Frame
- [x] UTF-8 24B 文件名
- [x] 拆包
- [x] 粘包
- [x] 前导噪声
- [x] CRC 错误恢复
- [x] 超长 LEN 恢复
- [x] AE22/AE23 独立 parser
- [x] 文件列表 BE
- [x] 容量 LE
- [x] 容量 BE
- [x] 短 Body 安全

## 构建

- [x] `:core:protocol:test`
- [x] `:app:assembleDebug`
- [x] GitHub Actions PR CI

最终成功 CI：

- Workflow：`Stage 1 CI`
- Run ID：`36468839882`
- 结果：`success`
- 验证实现提交：`3ac16dff92f10058c225392f1f693808a6396c28`
- APK Artifact：`Voica-0.1.0-stage1-debug`
- Artifact ID：`10990273111`
- Artifact SHA-256 digest：`8b604ef81c4e01fa9b74b4c7c735705d815a7c8e19b904d4fe2a08bbd6361c2e`

## CI 调整记录

Stage 1 首次建立 CI 时遇到的是 Runner/SDK 工具链问题，而非协议实现问题：

1. `android-actions/setup-android@v3` 默认旧 `tools` 包不可用。
2. Runner 旧 sdkmanager 看不到 Android 37。
3. 最终采用 Android SDK Platform 37.1，并在 AGP 中设置 `compileSdk = 37`、`compileSdkMinor = 1`。
4. `activity-compose` 修正为 Google Maven 可解析的稳定版 `1.13.0`。
5. 最终 Run `36468839882` 完整通过协议单测和 Debug APK 构建。

## APK 真机安装

用户于 2026-09-29 确认真机测试通过：

- [x] APK 可安装
- [x] App 可启动
- [x] 默认简体中文
- [x] 录音 Tab 可显示
- [x] 设置 Tab 可切换
- [x] 协议诊断 CRC 显示“通过”
- [x] 协议诊断 FrameParser 显示“通过”
- [x] 2-2 导入帧显示 36 bytes
- [x] 无启动闪退

结论：**Stage 1 验收通过，可以 Freeze 并合并 main。**
