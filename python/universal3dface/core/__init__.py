"""核心：能力定义、数据契约、插件基类、注册表、万能引擎。"""

from .capabilities import Capability
from .types import BBox, Face3DResult, Mesh3D, VerifyResult
from .base import Face3DProvider
from .engine import UniversalFace3D
from . import registry

__all__ = [
    "Capability",
    "BBox",
    "Face3DResult",
    "Mesh3D",
    "VerifyResult",
    "Face3DProvider",
    "UniversalFace3D",
    "registry",
]
