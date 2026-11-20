#!/usr/bin/env python3
"""单目 3D 人脸重建插件（自研算法，接入万能接口）。

把自研的 MonocularFaceReconstructor 包装成标准 Provider，
对外声明 LANDMARKS_3D + MESH_3D 能力，返回统一 Face3DResult。
这样上层 UniversalFace3D 只需一个接口，就能拿到完整重建的 3D 网格。
"""
from __future__ import annotations

from typing import Optional, Set
import numpy as np

from ..core.base import Face3DProvider
from ..core.capabilities import Capability
from ..core.registry import register
from ..core.types import Face3DResult, Mesh3D
from ..reconstruction import MonocularFaceReconstructor


@register
class MonocularReconstructionProvider(Face3DProvider):
    name = "monocular3d"
    upstream = "自研（基于 MediaPipe 3D 锚点 + 官方 canonical 拓扑的网格拟合）"
    license = "MIT（本项目自研部分）；底层 MediaPipe 为 Apache-2.0"
    version = "1.0.0"
    capabilities = frozenset({Capability.LANDMARKS_3D, Capability.MESH_3D})

    def __init__(self, subdivision: int = 2, smooth_iterations: int = 3):
        self._subdivision = subdivision
        self._smooth = smooth_iterations
        self._reconstructor: Optional[MonocularFaceReconstructor] = None
        self._error: Optional[str] = None

    def available(self) -> bool:
        if self._reconstructor is None and self._error is None:
            self._init()
        return self._reconstructor is not None

    def _init(self) -> None:
        try:
            self._reconstructor = MonocularFaceReconstructor(
                subdivision=self._subdivision, smooth_iterations=self._smooth
            )
        except Exception as exc:  # pragma: no cover
            self._error = str(exc)

    def process(self, image: np.ndarray, capabilities: Optional[Set[Capability]] = None) -> Face3DResult:
        result = Face3DResult(provider=self.name, capabilities=[c.value for c in self.capabilities])
        if not self.available():
            result.errors["init"] = self._error or "重建器不可用"
            return result
        try:
            r = self._reconstructor.reconstruct(image)
            if r is None:
                result.errors["detect"] = "未检测到人脸"
                return result
            result.landmarks_3d = r.landmarks
            result.mesh = Mesh3D(
                vertices=r.vertices,
                triangles=r.faces,
            )
            result.attributes["num_vertices"] = r.num_vertices
            result.attributes["num_faces"] = r.num_faces
            result.attributes["has_transform"] = r.transform is not None
        except Exception as exc:  # pragma: no cover
            result.errors["reconstruct"] = str(exc)
        return result

    def close(self) -> None:
        if self._reconstructor is not None:
            self._reconstructor.close()
            self._reconstructor = None
