# Luma TV 背景源稿 v2

工具：内置 image_gen（未使用CLI/API密钥流程）。用于默认背景，无Apple壁纸或标识。

原图：`design/assets/ambient-source-v2.png`，实际1672×941。设计用途：默认高斯模糊背景的光影来源，关闭模糊时直接使用原图。原创素材，不使用Apple壁纸或标识。

取代 v1 的蓝调彩色渐层：v1 全画面中灰、左上钴蓝与右下黄铜两个光极等权、低频平滑无材质，8bit面板易出色带，观感接近"默认渐变"。v2 改为暗场单一光源的雾面石板：左上一束低角度光揭示细微矿物颗粒，中段入灰，下三分接近纯黑，色相与界面 `#F5F7F8` / `#D1DDE1` / `#D8E9ED` 同族。展开网格时壁纸即底场，应用图块是唯一亮物。

导出：`design/export-background.cjs` 使用Sharp将源图外扩到2200×1238，清晰版与模糊版按相同居中裁切取1920×1080，模糊版先执行sigma76px高斯模糊。默认资源是完整1080p缓存，不是实时模糊或4K原生素材。边缘外扩避免透明/黑边。其他背景和导入图片在Android IO线程使用三次滑动盒式滤波近似高斯，并缓存结果，API26+可用。

提示词：

Original 16:9 television launcher wallpaper: an extreme minimalist architectural material study of a dark interior wall, seen straight on, filling the frame. Cold honed slate or fine micro-cement, matte and dry, in a room with almost no light. One very wide, very soft column of neutral light grazes down from the upper left, revealing a fine, quiet mineral texture and a slow, gentle falloff into darkness toward the right and bottom. The lit area is cool graphite (around #22262B) with a hint of warm neutral grey, never brown or beige; the shadows sink to #06080B. The wall is smooth and calm — no marble veining, no cracks, no aggregate, no stains, no seams, no objects. Extremely fine even film grain, smooth dithering, no banding, no hotspots, no circular vignette rings, no light fixture. The lower third is nearly pure darkness. Low-key exposure, spacious, serene, gallery-like, expensive, restrained. No text, no watermark, no interface, no furniture, no people.
