# 第三方组件与素材

Luma TV 自有代码使用 [MIT License](LICENSE)。依赖组件保留各自的许可证。

- **Gradle Wrapper**：项目包含官方 Wrapper 脚本及 JAR，来自 Gradle 8.14.3，使用 Apache License 2.0。Copyright Gradle, Inc. and contributors。许可证全文见 [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)。
- **Kotlin、AndroidX / Jetpack Compose、TV Material、DataStore**：由构建工具从其官方发布源获取，使用 Apache License 2.0。依赖版本见 Gradle 构建文件；本仓库不包含它们的二进制缓存。
- **背景**：默认「深蓝丝绸」背景使用内置图像生成工具，根据项目所有者选择的设计方向生成，来源、提示词及导出方式见 [design/assets/ambient-source-v4.md](design/assets/ambient-source-v4.md)。旧版暖琥珀源图由项目所有者提供，留作历史素材。其他内置背景由项目内的 HTML 源稿生成。没有使用 Apple 壁纸、图标或参考照片。
- **应用图标与横幅**：运行时由 Android PackageManager 从已安装应用读取。仓库截图中的第三方应用名称、标识与横幅归各自权利人所有，作为真实运行效果展示，不纳入本项目的 MIT 授权范围；应用不内置这些第三方图标包。

Luma TV 是独立项目，与 Apple 及截图中的第三方应用没有隶属关系。
