#!/usr/bin/env python3
"""纯软件 3D 网格渲染器（自研，无外部 3D 依赖）。

用正交投影 + 画家算法（按深度从远到近画三角面）+ 法线光照，
把重建出的 3D 人脸网格渲染成 PNG。不依赖 WebGL / matplotlib 3D 的深度排序，
结果确定、可信，可用于验证重建几何是否正确。
"""
from __future__ import annotations

from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw


def _normalize(v: np.ndarray) -> np.ndarray:
    v = v.copy()
    v -= v.mean(axis=0)
    s = np.max(np.linalg.norm(v, axis=1))
    if s > 1e-9:
        v /= s
    return v


def _rotation(yaw_deg: float, pitch_deg: float) -> np.ndarray:
    """绕 Y(yaw) 和 X(pitch) 轴的旋转矩阵。"""
    yaw = np.deg2rad(yaw_deg); pitch = np.deg2rad(pitch_deg)
    ry = np.array([[np.cos(yaw), 0, np.sin(yaw)],
                   [0, 1, 0],
                   [-np.sin(yaw), 0, np.cos(yaw)]])
    rx = np.array([[1, 0, 0],
                   [0, np.cos(pitch), -np.sin(pitch)],
                   [0, np.sin(pitch), np.cos(pitch)]])
    return ry @ rx


def render_mesh(vertices: np.ndarray, faces: np.ndarray, out_path: str | Path,
                yaw: float = 0.0, pitch: float = 0.0, size: int = 640,
                bg: tuple = (16, 20, 34), base_color: tuple = (91, 155, 245)) -> Path:
    """把网格渲染成单视角 PNG。"""
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    v = _normalize(vertices)
    v = v @ _rotation(yaw, pitch).T

    # 正交投影：x->右, y->上(图像里取反), z->深度(朝观察者为正)
    scale = size * 0.42
    cx, cy = size / 2, size / 2
    px = v[:, 0] * scale + cx
    py = -v[:, 1] * scale + cy
    depth = v[:, 2]

    # 面法线与光照（逐面计算，用于背面剔除与明暗）
    f = faces
    face_normals = np.cross(v[f[:, 1]] - v[f[:, 0]], v[f[:, 2]] - v[f[:, 0]])
    fn_len = np.linalg.norm(face_normals, axis=1, keepdims=True); fn_len[fn_len < 1e-12] = 1
    face_normals = face_normals / fn_len
    light = np.array([0.2, 0.4, 1.0]); light /= np.linalg.norm(light)

    img = Image.new("RGB", (size, size), bg)
    draw = ImageDraw.Draw(img)

    # 画家算法：按面平均深度从远(小)到近(大)排序
    face_depth = depth[f].mean(axis=1)
    order = np.argsort(face_depth)
    for fi in order:
        a, b, c = f[fi]
        # 背面剔除：面法线 z<=0 的面朝后，跳过（人脸网格是单层面）
        if face_normals[fi, 2] <= 0.0:
            continue
        shade = float(np.clip(np.dot(face_normals[fi], light), 0, 1))
        col = tuple(int(base_color[k] * (0.35 + 0.65 * shade)) for k in range(3))
        tri = [(px[a], py[a]), (px[b], py[b]), (px[c], py[c])]
        draw.polygon(tri, fill=col)

    img.save(out_path)
    return out_path


def render_views(vertices: np.ndarray, faces: np.ndarray, out_dir: str | Path,
                 title: str = "3D Face Reconstruction") -> list[Path]:
    """渲染正面/45°/侧面三视角，并拼成一张图。"""
    out_dir = Path(out_dir); out_dir.mkdir(parents=True, exist_ok=True)
    specs = [("front", 0, 0), ("45deg", -45, 5), ("side", -88, 0)]
    imgs = []
    for name, yaw, pitch in specs:
        p = render_mesh(vertices, faces, out_dir / f"view_{name}.png", yaw=yaw, pitch=pitch)
        imgs.append(Image.open(p).convert("RGB"))

    w, h = imgs[0].size
    canvas = Image.new("RGB", (w * len(imgs), h + 50), (10, 12, 20))
    d = ImageDraw.Draw(canvas)
    labels = ["Front", "45-degree", "Profile"]
    for i, (im, lab) in enumerate(zip(imgs, labels)):
        canvas.paste(im, (i * w, 50))
        d.text((i * w + w // 2 - 30, 16), lab, fill=(230, 235, 245))
    out = out_dir / "reconstruction_views.png"
    canvas.save(out)
    return [out] + [out_dir / f"view_{n}.png" for n, _, _ in specs]
