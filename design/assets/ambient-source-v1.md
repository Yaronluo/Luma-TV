# Luma TV 背景源稿 v1

工具：内置 image_gen（未使用CLI/API密钥流程）。已用于0.2.0原生首页默认背景，无Apple壁纸或标识。

原图：`design/assets/ambient-source-v1.png`。设计用途：默认高斯模糊背景的色彩与光影来源，关闭模糊时直接使用原图。原创素材，不使用Apple壁纸或标识。

原图实际1672×941。`design/export-background.cjs`使用Sharp将其扩展到2200×1238，模糊版本先执行sigma76px高斯模糊，再居中裁到1920×1080；清晰版本使用相同构图裁切。默认资源是完整1080p缓存，不是实时模糊或4K原生素材。边缘外扩避免透明/黑边。其他背景和导入图片在Android IO线程使用三次滑动盒式滤波近似高斯，并缓存结果，API26+可用。

提示词：

Use case: stylized-concept. Asset type: original 16:9 television launcher wallpaper source, 2048x1152 landscape. Create an elegant abstract spatial color composition deliberately designed to look rich after strong Gaussian blur, and still attractive with blur disabled. Full bleed, no interface, no text, no symbols, no logos. A few enormous overlapping softly curved translucent satin/glass-like planes, occupying the entire image with a feeling of depth: deep ocean blue and luminous muted cyan toward the upper left, smoky petrol teal and blue-grey through the center, subdued sage-grey with a small warm pearl light area in the lower right. Asymmetric composition; broad, uneven areas of illumination and shadow, no central focal point. The forms should have gently defined broad edges and smooth volumetric shading before blurring, enough structure for blur to transform them into layered organic clouds of color rather than a flat gradient. Restrained saturation, medium-dark exposure, no black voids and no white hotspots; keep the whole backdrop comfortable behind white TV interface text and bright app icons. Quiet high-end living room ambience. An original artwork: do not copy Apple wallpaper designs or screensaver imagery. Avoid landscape, coastline, horizon, photography, particles, small bokeh circles, neon, sharp shiny streaks, stars, fine patterns, grids, noise, large dark vignette and decorative text.
