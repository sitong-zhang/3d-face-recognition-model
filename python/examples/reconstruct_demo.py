#!/usr/bin/env python3
"""单目 3D 人脸重建演示（端到端验证）。

输入一张普通 2D 图片 → 重建完整 3D 人脸网格 → 导出 OBJ/PLY + 三视角图 + 可旋转交互页。
测试图用 skimage 自带的 NASA 公开领域宇航员照片。
"""
import sys
from pathlib import Path
import numpy as np
from PIL import Image
from skimage.data import astronaut

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from universal3dface.reconstruction import MonocularFaceReconstructor, render_views
from universal3dface.reconstruction.visualize import interactive_html

OUT = ROOT / "output" / "reconstruction"
OUT.mkdir(parents=True, exist_ok=True)


def main():
    # 1) 准备一张真实 2D 人脸图（裁出人脸区域）
    img = Image.fromarray(astronaut()).convert("RGB").crop((160, 60, 280, 180)).resize((320, 320))
    img_path = OUT / "input_face.jpg"
    img.save(img_path)

    # 2) 单目重建
    with MonocularFaceReconstructor(subdivision=1, smooth_iterations=2) as rec:
        result = rec.reconstruct(np.asarray(img))
    if result is None:
        print("未检测到人脸，重建失败")
        return 1

    print("=== 重建结果 ===")
    print(f"检测锚点: {result.meta['detected_landmarks']} 个")
    print(f"重建顶点: {result.num_vertices} 个 (原始 468 + 细分)")
    print(f"重建三角面: {result.num_faces} 个")
    print(f"有变换矩阵: {result.transform is not None}")
    print(f"图像尺寸: {result.meta['image_size']}")

    # 3) 导出
    obj = MonocularFaceReconstructor.export_obj(result, OUT / "reconstructed_face.obj")
    ply = MonocularFaceReconstructor.export_ply(result, OUT / "reconstructed_face.ply")
    view_list = render_views(result.vertices, result.faces, OUT)  # 软件光栅化三视角（可信）
    html = interactive_html(result, OUT / "interactive_3d.html")

    print("\n=== 输出文件 ===")
    for p in (img_path, obj, ply, *view_list, html):
        print(f"  {p.name}: {p.stat().st_size} bytes  -> {p}")
    print("\n演示完成：从单张 2D 图重建出完整 3D 人脸网格。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
