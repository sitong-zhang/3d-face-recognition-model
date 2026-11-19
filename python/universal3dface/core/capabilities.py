"""
能力定义模块。

本框架把 3D 人脸识别领域常见的处理步骤抽象成一组"能力"（Capability）。
任何第三方开源实现只要声明自己支持哪些能力、并按统一契约返回结果，
就可以作为一个插件接入本系统——这就是本框架所称的"万能接口"。
"""

from enum import Enum


class Capability(str, Enum):
    """插件可提供能力的枚举。"""

    DETECT = "detect"                    # 人脸检测：定位人脸框
    LANDMARKS_2D = "landmarks_2d"        # 2D 关键点
    LANDMARKS_3D = "landmarks_3d"        # 3D 关键点（带深度 z）
    MESH_3D = "mesh_3d"                  # 3D 人脸网格（顶点 + 面片）
    EMBED = "embed"                      # 身份特征向量（用于识别/比对）
    VERIFY = "verify"                    # 两张人脸的相似度比对
    LIVENESS = "liveness"                # 活体检测（接口预留，未接入实现则不算支持）

    @classmethod
    def all(cls):
        return list(cls)

    @classmethod
    def values(cls):
        return [c.value for c in cls]
