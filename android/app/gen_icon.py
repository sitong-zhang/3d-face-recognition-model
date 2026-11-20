#!/usr/bin/env python3
"""高质量 3D 人脸扫描应用图标生成器（自研）。
生成 legacy 圆角方图标 + adaptive Foreground（透明），带人脸 3D 网格与扫描角标。
"""
import os, math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

RES = "/workspace/repo_build/android/app/src/main/res"
S = 4  # 超采样倍率

def lerp(a, b, t): return a + (b - a) * t

def gradient(size, c1, c2, radial=False):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    x = xx / (size - 1); y = yy / (size - 1)
    if radial:
        cx, cy = 0.42, 0.32
        d = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
        t = np.clip(d / 0.95, 0, 1)
    else:
        t = np.clip(0.55 * x + 0.65 * y, 0, 1)
    arr = np.zeros((size, size, 3), np.float32)
    for i in range(3):
        arr[..., i] = lerp(c1[i], c2[i], t)
    return Image.fromarray(arr.astype(np.uint8), "RGB")

def draw_content(dr, cx, cy, R, adaptive=False):
    """在 (cx,cy) 半径 R 内绘制人脸 3D 网格 + 扫描角标 + 对勾。"""
    blue = (110, 190, 255, 255)
    cyan = (90, 220, 235, 255)
    white = (255, 255, 255, 255)

    # ---- 四角扫描取景框 ----
    arm = R * 0.30
    corner = R * 0.92
    lw = max(3, int(R * 0.075))
    def corner_mark(sx, sy, ex, ey):
        dr.line([(cx + sx * corner, cy + sy * corner * 0.15),
                 (cx + sx * corner, cy + sy * corner)], fill=cyan, width=lw)
        dr.line([(cx + sx * corner * 0.15, cy + sy * corner),
                 (cx + sx * corner, cy + sy * corner)], fill=cyan, width=lw)
    # 用带圆角效果的 L 形四角
    for sx, sy in [(-1, -1), (1, -1), (-1, 1), (1, 1)]:
        x0 = cx + sx * corner; y0 = cy + sy * corner
        dr.line([(x0, y0 - sy * arm), (x0, y0)], fill=cyan, width=lw)
        dr.line([(x0 - sx * arm, y0), (x0, y0)], fill=cyan, width=lw)

    # ---- 人脸 3D 网格（模拟 MediaPipe 468 点拓扑的抽象点阵）----
    # 脸型轮廓（椭圆）
    face_rx, face_ry = R * 0.56, R * 0.70
    pts = []
    for a in np.linspace(0, 2 * math.pi, 60):
        pts.append((cx + face_rx * math.sin(a), cy - face_ry * math.cos(a) * 0.92 + R * 0.04))
    mesh_line = (150, 205, 255, 150)
    dr.line(pts + [pts[0]], fill=mesh_line, width=max(2, int(R * 0.03)), joint="curve")

    # 网格点：按人脸几何分布
    mesh = []
    # 额头
    for dx in np.linspace(-0.30, 0.30, 5):
        mesh.append((cx + dx * R, cy - 0.45 * R))
    # 眼部
    for ex in (-0.26, 0.26):
        for dx in np.linspace(-0.10, 0.10, 3):
            mesh.append((cx + (ex + dx) * R, cy - 0.18 * R))
    # 鼻梁/鼻尖
    for dy in (-0.10, 0.02, 0.14):
        mesh.append((cx, cy + dy * R))
    # 脸颊两侧
    for sy in (-0.12, 0.10, 0.30):
        for sx in (-0.40, 0.40):
            mesh.append((cx + sx * R, cy + sy * R))
    # 嘴部
    for dx in np.linspace(-0.16, 0.16, 5):
        mesh.append((cx + dx * R, cy + 0.40 * R))
    # 下巴
    for dx in np.linspace(-0.12, 0.12, 3):
        mesh.append((cx + dx * R, cy + 0.55 * R))

    # 连线（横向网格）
    rows = {}
    for (mx, my) in mesh:
        key = round(my / (R * 0.09))
        rows.setdefault(key, []).append((mx, my))
    for key in sorted(rows):
        row = sorted(rows[key])
        for i in range(len(row) - 1):
            if abs(row[i+1][0] - row[i][0]) < R * 0.34:
                dr.line([row[i], row[i+1]], fill=(120, 185, 255, 90), width=max(1, int(R*0.018)))
    # 点
    pr = max(2, int(R * 0.030))
    for (mx, my) in mesh:
        dr.ellipse([mx - pr, my - pr, mx + pr, my + pr], fill=white)

    # 眼睛高亮（两点更亮更大）
    er = max(3, int(R * 0.05))
    for ex in (-0.26, 0.26):
        exx, eyy = cx + ex * R, cy - 0.18 * R
        dr.ellipse([exx - er, eyy - er, exx + er, eyy + er], fill=(120, 230, 255, 255))

    # ---- 右下角绿色对勾徽标（识别成功语义）----
    bx, by, br = cx + corner * 0.86, cy + corner * 0.86, R * 0.26
    dr.ellipse([bx - br, by - br, bx + br, by + br], fill=(44, 190, 110, 255))
    cw = max(3, int(R * 0.07))
    dr.line([(bx - br*0.42, by), (bx - br*0.08, by + br*0.38), (bx + br*0.48, by - br*0.40)],
            fill=(255, 255, 255, 255), width=cw, joint="curve")

def make_legacy(size):
    sz = size * S
    base = gradient(sz, (11, 16, 32), (26, 58, 107), radial=True).convert("RGBA")
    # 光晕
    glow = Image.new("RGBA", (sz, sz), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    m = int(sz*0.18)
    gd.ellipse([m, m, sz-m, sz-m], fill=(60, 140, 255, 55))
    glow = glow.filter(ImageFilter.GaussianBlur(sz*0.06))
    base = Image.alpha_composite(base, glow)
    d = ImageDraw.Draw(base)
    R = sz * 0.30
    draw_content(d, sz/2, sz/2, R)
    # 圆角裁剪
    mask = Image.new("L", (sz, sz), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, sz-1, sz-1], radius=int(sz*0.225), fill=255)
    base.putalpha(mask)
    return base.resize((size, size), Image.LANCZOS)

def make_foreground(size):
    """adaptive 前景：透明背景，内容在中心安全区（约画布 66/108）。"""
    sz = size * S
    img = Image.new("RGBA", (sz, sz), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    R = sz * 0.25  # 内容控制在中心安全区内
    draw_content(d, sz/2, sz/2, R, adaptive=True)
    return img.resize((size, size), Image.LANCZOS)

def main():
    densities = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                 ("xxhdpi", 144), ("xxxhdpi", 192)]
    for name, s in densities:
        folder = os.path.join(RES, "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        make_legacy(s).save(os.path.join(folder, "ic_launcher.png"))
        # 前景用 108dp 基准的缩放
        fg_size = int(s * 108 / 48)
        make_foreground(fg_size).save(os.path.join(folder, "ic_launcher_foreground.png"))
    # Play 商店 512
    make_legacy(512).save("/workspace/repo_build/android/app/icon_512.png")
    print("图标生成完成:", [d[0] for d in densities])

if __name__ == "__main__":
    main()
