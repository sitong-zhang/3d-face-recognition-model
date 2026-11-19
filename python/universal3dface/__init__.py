"""
Universal3DFace —— 可插拔的通用 3D 人脸识别框架。

一个万能接口，任何符合 Face3DProvider 契约的开源实现都可以插进来。
本框架本身只做"封装与编排"，核心算法全部来自第三方开源项目（见 THIRD_PARTY_NOTICES.md）。

快速开始::

    from universal3dface import UniversalFace3D
    import cv2

    engine = UniversalFace3D()                # 自动发现并加载所有插件
    print(engine.list_providers())            # 查看已插入的开源实现

    image = cv2.imread("face.jpg")
    result = engine.analyze(image)            # 一次拿到所有能力的结果
    print(result.summary())
"""

from .core import (
    Capability,
    BBox,
    Face3DResult,
    Mesh3D,
    VerifyResult,
    Face3DProvider,
    UniversalFace3D,
    registry,
)

__version__ = "0.1.0"
__all__ = [
    "Capability",
    "BBox",
    "Face3DResult",
    "Mesh3D",
    "VerifyResult",
    "Face3DProvider",
    "UniversalFace3D",
    "registry",
    "__version__",
]
