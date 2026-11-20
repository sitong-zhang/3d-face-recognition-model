#!/usr/bin/env python3
"""3D 人脸重建结果可视化（自研）。

- render_views: 用 matplotlib 渲染正面/侧面/45° 三个视角的网格图
- interactive_html: 用 plotly 生成可在浏览器里拖拽旋转的交互式 3D 网格
"""
from __future__ import annotations

from pathlib import Path
import numpy as np

from .reconstructor import ReconstructionResult


def _norm(v: np.ndarray) -> np.ndarray:
    """归一化到单位尺度并居中，便于渲染。"""
    v = v.copy()
    v -= v.mean(axis=0)
    span = np.max(np.linalg.norm(v, axis=1))
    if span > 1e-9:
        v /= span
    return v


def _pick_cjk_font() -> str | None:
    """尝试寻找可用的中文字体，找不到返回 None（调用方改用英文标签）。"""
    import matplotlib
    candidates = [
        "Noto Sans CJK SC", "Noto Sans CJK JP", "WenQuanYi Micro Hei",
        "SimHei", "Microsoft YaHei", "PingFang SC", "Arial Unicode MS",
    ]
    available = {f.name for f in matplotlib.font_manager.fontManager.ttflist}
    for c in candidates:
        if c in available:
            return c
    return None


def render_views(result: ReconstructionResult, out_png: str | Path,
                 title: str | None = None) -> Path:
    """渲染三视角（正面 / 45° / 侧面）到一张 PNG。"""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from mpl_toolkits.mplot3d.art3d import Poly3DCollection

    font = _pick_cjk_font()
    if font:
        plt.rcParams["font.sans-serif"] = [font]
        plt.rcParams["axes.unicode_minus"] = False
        if title is None:
            title = "单目 3D 人脸重建"
        view_names = ["正面 (Front)", "45° 侧视", "侧面 (Profile)"]
    else:
        if title is None:
            title = "Monocular 3D Face Reconstruction"
        view_names = ["Front", "45-degree", "Profile"]

    out_png = Path(out_png)
    out_png.parent.mkdir(parents=True, exist_ok=True)
    v = _norm(result.vertices)
    f = result.faces
    # 取部分三角面渲染，避免过密；同时按法线做简单明暗
    normals = result.normals if result.normals is not None else np.zeros_like(v)
    light = np.array([0.3, 0.5, 1.0]); light /= np.linalg.norm(light)
    shade = np.clip(normals @ light, 0, 1)
    step = max(1, len(f) // 900)
    faces_show = f[::step]

    fig = plt.figure(figsize=(15, 5.5))
    fig.suptitle(title, fontsize=16, fontweight="bold")
    views = [
        (view_names[0], 0, 0),
        (view_names[1], -55, 10),
        (view_names[2], -90, 0),
    ]
    for i, (name, elev_azim, elev) in enumerate(views):
        ax = fig.add_subplot(1, 3, i + 1, projection="3d")
        tris = [[v[a], v[b], v[c]] for a, b, c in faces_show]
        # 用面法线方向做个近似灰度着色
        fc = []
        for a, b, c in faces_show:
            s = (shade[a] + shade[b] + shade[c]) / 3
            base = np.array([0.36, 0.62, 0.98])  # 蓝色人脸
            fc.append(base * (0.45 + 0.55 * s))
        pc = Poly3DCollection(tris, facecolors=fc, edgecolors="none", alpha=0.95)
        ax.add_collection3d(pc)
        ax.set_xlim(-1, 1); ax.set_ylim(-1, 1); ax.set_zlim(-1, 1)
        ax.set_box_aspect((1, 1, 1))
        ax.view_init(elev=elev, azim=elev_azim)
        ax.set_title(name, fontsize=12)
        ax.set_axis_off()
    plt.tight_layout()
    fig.savefig(out_png, dpi=130, bbox_inches="tight")
    plt.close(fig)
    return out_png


def interactive_html(result: ReconstructionResult, out_html: str | Path,
                     title: str = "3D 人脸重建 · 可旋转查看") -> Path:
    """生成可拖拽旋转 / 缩放的交互式 3D 网格页面（离线可用，内嵌 plotly）。"""
    import plotly.graph_objs as go

    out_html = Path(out_html)
    out_html.parent.mkdir(parents=True, exist_ok=True)
    v = _norm(result.vertices)
    f = result.faces

    mesh = go.Mesh3d(
        x=v[:, 0], y=v[:, 1], z=v[:, 2],
        i=f[:, 0], j=f[:, 1], k=f[:, 2],
        color="lightblue", opacity=0.95,
        flatshading=False,
        lighting=dict(ambient=0.5, diffuse=0.8, specular=0.3, roughness=0.5),
        lightposition=dict(x=100, y=100, z=200),
        hoverinfo="skip",
    )
    # 锚点散点
    lm = _norm(result.landmarks)
    pts = go.Scatter3d(
        x=lm[:, 0], y=lm[:, 1], z=lm[:, 2],
        mode="markers", marker=dict(size=2.2, color="crimson", opacity=0.6),
        hoverinfo="skip",
    )
    fig = go.Figure(data=[mesh, pts])
    fig.update_layout(
        title=title,
        scene=dict(
            xaxis=dict(visible=False), yaxis=dict(visible=False), zaxis=dict(visible=False),
            aspectmode="cube",
        ),
        width=760, height=680,
        margin=dict(l=0, r=0, t=50, b=0),
    )
    fig.write_html(str(out_html), include_plotlyjs=True, auto_open=False)
    return out_html
