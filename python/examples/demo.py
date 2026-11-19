"""
端到端演示：使用万能接口做 3D 人脸分析。

运行：python examples/demo.py
"""

import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from universal3dface import UniversalFace3D  # noqa: E402


def load_test_image() -> np.ndarray:
    """优先用外部传入图片，否则用 skimage 自带的公开领域测试人像。"""
    if len(sys.argv) > 1:
        img = cv2.imread(sys.argv[1])
        if img is None:
            raise FileNotFoundError(sys.argv[1])
        return img
    from skimage.data import astronaut
    rgb = astronaut()
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)


def main():
    print("=" * 60)
    print("Universal3DFace 通用 3D 人脸识别框架 - 演示")
    print("=" * 60)

    engine = UniversalFace3D()

    print("\n[1] 已插入的开源实现（万能接口背后接的东西）:")
    for p in engine.list_providers():
        mark = "可用" if p["available"] else "不可用"
        print(f"  - {p['name']:<12} [{mark}] 许可: {p['license']}")
        print(f"      能力: {', '.join(p['capabilities'])}")
        print(f"      来源: {p['upstream']}")

    print("\n[2] 各插件声明的能力:")
    for name, caps in engine.list_capabilities().items():
        print(f"  {name}: {caps}")

    img = load_test_image()
    print(f"\n[3] 输入图像 shape={img.shape}")

    print("\n[4] 调用万能接口 analyze() 聚合所有插件结果:")
    result = engine.analyze(img)
    for key, value in result.summary().items():
        print(f"  {key}: {value}")
    for cap in ["blendshapes", "transformation_matrix"]:
        if cap in result.attributes:
            v = result.attributes[cap]
            print(f"  {cap}: {'共 ' + str(len(v)) + ' 项' if isinstance(v, dict) else v.shape}")

    print("\n[5] 人脸比对 verify()（同一张图 vs 自身，应判为同一人）:")
    vr = engine.verify(img, img)
    print(" ", vr.to_dict())

    print("\n[6] 只调用某个插件（mediapipe 拿 3D 网格，dlib 拿 128D 特征）:")
    mp_result = engine.analyze(img, provider_names=["mediapipe"])
    dlib_result = engine.analyze(img, provider_names=["dlib"])
    print("  mediapipe 3D 顶点:", None if mp_result.landmarks_3d is None else mp_result.landmarks_3d.shape)
    print("  mediapipe 三角面:", None if (mp_result.mesh and mp_result.mesh.triangles is None) else (mp_result.mesh.triangle_count if mp_result.mesh else None))
    print("  dlib 128D 特征:", None if dlib_result.embedding is None else dlib_result.embedding.shape)

    engine.close()  # 显式释放底层推理器资源
    print("\n演示完成。")


if __name__ == "__main__":
    main()
