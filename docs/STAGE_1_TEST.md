# Voica Stage 1 测试清单

## 自动测试

- [ ] CRC-16/XMODEM 标准向量
- [ ] Sequence 255 → 0
- [ ] 2-2 36B Golden Frame
- [ ] 2-12 Segment Frame
- [ ] Delete-one Frame
- [ ] UTF-8 24B 文件名
- [ ] 拆包
- [ ] 粘包
- [ ] 前导噪声
- [ ] CRC 错误恢复
- [ ] 超长 LEN 恢复
- [ ] AE22/AE23 独立 parser
- [ ] 文件列表 BE
- [ ] 容量 LE
- [ ] 容量 BE
- [ ] 短 Body 安全

## 构建

- [ ] `:core:protocol:test`
- [ ] `:app:assembleDebug`
- [ ] GitHub Actions PR CI

## CI 记录

- 初始 PR CI 在 Android SDK setup Action 阶段失败，原因是第三方 Action 默认请求已移除的旧 `tools` SDK 包。
- 第二次环境验证发现 Runner 的 Android SDK 已预装，但 `sdkmanager` 未加入 PATH；已改为显式调用 `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager`。前两类失败均不属于应用代码或协议测试失败。

## APK 真机安装

用户确认：
- [ ] APK 可安装
- [ ] App 可启动
- [ ] 默认简体中文
- [ ] 录音 Tab 可显示
- [ ] 设置 Tab 可切换
- [ ] 协议诊断 CRC 显示“通过”
- [ ] 协议诊断 FrameParser 显示“通过”
- [ ] 2-2 导入帧显示 36 bytes
- [ ] 无启动闪退

完成以上真机检查后才创建最终 `STAGE_1_FREEZE.md`。
