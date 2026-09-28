# Voica Stage 1 修订开发规划

## 分支

`stage1-development`

## 实施顺序

1. 建立 Gradle / Android / Compose 工程。
2. 建立纯 Kotlin `:core:protocol`。
3. 实现 CRC、Sequence、Frame、Parser。
4. 实现文件请求编码和字段解码。
5. 添加 Golden Tests。
6. 建立最小简体中文 Compose UI 与协议诊断。
7. 建立低成本 GitHub Actions：
   - PR 时运行 Unit Test + assembleDebug
   - 不运行 Emulator / Instrumentation
   - 仅手动 workflow_dispatch 上传 APK
8. PR 验证构建。
9. 生成 Stage 1 真机安装测试清单。
10. 用户安装/启动确认后再 Freeze。

## 代码边界

- `:core:protocol` 不依赖 Android API。
- UI 不直接解析二进制帧。
- Stage 1 不引入 BLE。
- 不读取或复用 `voice-card-android` 的任何实现。
