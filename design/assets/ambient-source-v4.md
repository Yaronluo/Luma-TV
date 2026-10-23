# Luma TV 深蓝丝绸背景 v4

来源：2026-10-23 使用内置图像生成工具，基于用户选择的第一张「深蓝丝绸」界面示意图，去除全部界面后生成。不是 Apple 壁纸，不包含应用标识、文字或托盘。

源图：[ambient-source-v4.png](ambient-source-v4.png)，实际1672×941。静态深海军蓝丝绸褶皱，带银蓝边缘光，下方保持安静的深蓝，供应用图块和玻璃托盘叠加。

`design/export-background.cjs` 使用Sharp按16:9居中裁切导出1920×1080：清晰版为`app/src/main/res/drawable-nodpi/wallpaper_ambient.jpg`，高斯模糊版为同目录`wallpaper_ambient_blur.jpg`（边缘复制外扩144px、sigma48px高斯模糊后裁回原尺寸；最初6px版本已加深）。输出尺寸不代表源图是原生1080p或4K。v3源图留作历史素材，本次不再作为默认背景。

首页、应用网格及其他页面使用同一套本地背景加载逻辑，背景素材不再随应用焦点改变。首页另以缓存图片图层实现轻微视差和展开推近，不在每帧重新模糊；减少动态效果可关闭这些运动。模糊、亮度及其他内置/本地背景选择仍可通过设置调整，原配置键`dusk`保持兼容。

生成提示词（内置工具，非CLI）：

> Use case: precise-object-edit. Produce the actual wallpaper asset for Luma TV based on the approved image, not another UI mockup. Input image is the edit target. Preserve exactly the midnight navy flowing silk wallpaper composition, silver-blue edge lighting, fabric folds, texture, lighting and overall color in the upper 80% of the image. Remove the entire bottom UI: all six application tiles, ALL logos, text, icons, the translucent dock panel, its outlines, rounded corners and shadow. Reconstruct the lower region as a seamless continuation of the deep navy silk background. No UI, no panels, no text, no logo, no watermark, no white marks. The lower 20% should be calm dark navy so an app can overlay a glass tray later. Full bleed 16:9 landscape wallpaper, ideally 3840 by 2160. This is a shipped static background asset, with clean continuous gradients and restrained detailed silk grain.
