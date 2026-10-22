# 参与开发

欢迎通过 Issue 提交问题或通过 Pull Request 改进 Luma TV。提交问题时请注明设备型号、Android 版本、Luma TV 版本和可复现的遥控器按键路径；分享日志前先检查其中的个人信息。

开发使用 JDK 17、Kotlin 和 Jetpack Compose，最低支持 API 26。先阅读 [设计约定](docs/design.md) 与 [验证记录](docs/validation.md)。使用 Gradle Wrapper，不要同时运行多个 Gradle 构建。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

Windows 也可使用 `scripts/build.ps1`。项目中的模拟器脚本专门保护维护者已有的 `BILI_TV_API_34` 环境，不是面向任意设备的安装器。请使用自己的测试环境，明确指定 ADB serial；勿清空、卸载或重置既有应用及设备。`fixture` 模块仅生成独立的临时验收应用。

界面变更需要记录实际方向键、确认键和返回键操作，检查焦点落点、可见区域、放大裁切、滚动及返回恢复。编译成功、安装成功、启动成功与交互通过应分别报告。测试默认 Home 前记录原桌面组件及恢复命令，始终保留原桌面。

保持变更集中，不添加未经产品讨论的账号、影视聚合、网络、广告、遥测或后台常驻服务。提交前运行 `git diff --cached --check`，不要包含 APK、构建缓存、密钥、个人配置、设备转储或私人日志。
