#!/usr/bin/env python3
"""应用图标生成器。

前景使用专业设计素材：Lucide 开源 `scan-face` 图标（assets/scan-face.svg），
配合深色科技渐变背景，生成 legacy 圆角图标 + adaptive 前景。
不额外绘制任何人脸。
"""
import os
import cairosvg
import numpy as np
from PIL import Image, ImageFilter, ImageDraw

RES = "/workspace/repo_build/android/app/src/main/res"
SVG = "/workspace/repo_build/android/app/src/main/assets/scan-face.svg"
S = 4  # 超采样倍率


def lerp(a, b, t):
    return a + (b - a) * t


def gradient(size, c1, c2, cx=0.42, cy=0.30):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    x = xx / (size - 1)
    y = yy / (size - 1)
    d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    t = np.clip(d / 0.95, 0, 1)
    arr = np.zeros((size, size, 3), np.float32)
    for i in range(3):
        arr[..., i] = lerp(c1[i], c2[i], t)
    return Image.fromarray(arr.astype(np.uint8), "RGB")


def glyph_image(px, color="#FFFFFF"):
    """把 Lucide scan-face SVG 渲染为指定颜色的透明 PNG。"""
    with open(SVG, "r", encoding="utf-8") as f:
        svg = f.read()
    # currentColor -> 目标色
    svg = svg.replace("currentColor", color)
    png = cairosvg.svg2png(bytestring=svg.encode("utf-8"),
                           output_width=px, output_height=px)
    from io import BytesIO
    return Image.open(BytesIO(png)).convert("RGBA")


def make_legacy(size):
    sz = size * S
    base = gradient(sz, (10, 17, 38), (30, 74, 140)).convert("RGBA")
    # 柔和光晕
    glow = Image.new("RGBA", (sz, sz), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    m = int(sz * 0.16)
    gd.ellipse([m, m, sz - m, sz - m], fill=(70, 150, 255, 60))
    glow = glow.filter(ImageFilter.GaussianBlur(sz * 0.06))
    base = Image.alpha_composite(base, glow)

    # Lucide scan-face 前景，占画布约 52%，居中略上
    gpx = int(sz * 0.54)
    glyph = glyph_image(gpx, "#FFFFFF")
    gx = (sz - gpx) // 2
    gy = int((sz - gpx) * 0.46)
    base.alpha_composite(glyph, (gx, gy))

    # 圆角裁剪
    mask = Image.new("L", (sz, sz), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, sz - 1, sz - 1], radius=int(sz * 0.225), fill=255)
    base.putalpha(mask)
    return base.resize((size, size), Image.LANCZOS)


def make_foreground(size):
    """adaptive 前景：透明背景，Lucide scan-face 位于中心安全区。"""
    sz = size * S
    img = Image.new("RGBA", (sz, sz), (0, 0, 0, 0))
    gpx = int(sz * 0.50)  # 安全区内
    glyph = glyph_image(gpx, "#FFFFFF")
    gx = (sz - gpx) // 2
    gy = (sz - gpx) // 2
    img.alpha_composite(glyph, (gx, gy))
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    densities = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                 ("xxhdpi", 144), ("xxxhdpi", 192)]
    for name, s in densities:
        folder = os.path.join(RES, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        make_legacy(s).save(os.path.join(folder, "ic_launcher.png"))
        fg_size = int(s * 108 / 48)
        make_foreground(fg_size).save(os.path.join(folder, "ic_launcher_foreground.png"))
    # Play 商店 512
    make_legacy(512).save("/workspace/repo_build/android/app/icon_512.png")
    print("图标生成完成:", [d[0] for d in densities])
