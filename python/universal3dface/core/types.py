"""
统一数据契约。

所有插件都返回同一种结果对象 Face3DResult，从而让上层"万能接口"
无需关心底层用的是 dlib、MediaPipe 还是 Deep3DFaceRecon。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import numpy as np

from .capabilities import Capability


@dataclass
class BBox:
    """人脸检测框，左上角原点坐标 (x, y, width, height)。"""

    x: float
    y: float
    width: float
    height: float
    confidence: Optional[float] = None

    @property
    def xyxy(self) -> tuple:
        return (self.x, self.y, self.x + self.width, self.y + self.height)

    @property
    def area(self) -> float:
        return self.width * self.height


@dataclass
class Mesh3D:
    """3D 人脸网格。"""

    vertices: np.ndarray                      # (V, 3) 顶点
    triangles: Optional[np.ndarray] = None    # (F, 3) 面片索引
    colors: Optional[np.ndarray] = None       # (V, 3) 顶点颜色（可选）

    @property
    def vertex_count(self) -> int:
        return int(self.vertices.shape[0])

    @property
    def triangle_count(self) -> int:
        return 0 if self.triangles is None else int(self.triangles.shape[0])


@dataclass
class Face3DResult:
    """所有插件统一的结果容器。"""

    provider: str
    bbox: Optional[BBox] = None
    landmarks_2d: Optional[np.ndarray] = None   # (N, 2)
    landmarks_3d: Optional[np.ndarray] = None   # (N, 3)
    mesh: Optional[Mesh3D] = None
    embedding: Optional[np.ndarray] = None      # (D,)
    attributes: Dict[str, Any] = field(default_factory=dict)
    errors: Dict[str, str] = field(default_factory=dict)
    capabilities: List[str] = field(default_factory=list)

    def has(self, cap: Capability) -> bool:
        return cap.value in self.capabilities

    def merge(self, other: "Face3DResult") -> "Face3DResult":
        """把另一个插件的结果合并进来，已有值优先保留。

        用于万能接口聚合多个插件：先注册的插件优先级更高。
        """
        if self.bbox is None:
            self.bbox = other.bbox
        if self.landmarks_2d is None and other.landmarks_2d is not None:
            self.landmarks_2d = other.landmarks_2d
        if self.landmarks_3d is None and other.landmarks_3d is not None:
            self.landmarks_3d = other.landmarks_3d
        if self.mesh is None:
            self.mesh = other.mesh
        if self.embedding is None:
            self.embedding = other.embedding
        for k, v in other.attributes.items():
            self.attributes.setdefault(k, v)
        for k, v in other.errors.items():
            self.errors.setdefault(f"[{other.provider}] {k}", v)
        providers = self.attributes.setdefault("providers", [self.provider])
        if other.provider not in providers:
            providers.append(other.provider)
        return self

    def summary(self) -> Dict[str, Any]:
        return {
            "provider": self.provider,
            "capabilities": self.capabilities,
            "bbox_xyxy": None if self.bbox is None else list(self.bbox.xyxy),
            "landmarks_2d": None if self.landmarks_2d is None else self.landmarks_2d.shape,
            "landmarks_3d": None if self.landmarks_3d is None else self.landmarks_3d.shape,
            "mesh_vertices": None if self.mesh is None else self.mesh.vertex_count,
            "embedding": None if self.embedding is None else self.embedding.shape,
            "errors": self.errors,
        }


@dataclass
class VerifyResult:
    """人脸比对结果。"""

    provider: str
    metric: str
    similarity: Optional[float] = None
    distance: Optional[float] = None
    is_same: Optional[bool] = None
    threshold: Optional[float] = None
    errors: Dict[str, str] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "provider": self.provider,
            "metric": self.metric,
            "similarity": self.similarity,
            "distance": self.distance,
            "is_same": self.is_same,
            "threshold": self.threshold,
            "errors": self.errors,
        }
