"""
MediaPipe Face Landmarker 适配器插件（3D 人脸网格）。

封装的开源实现（原样调用其官方 tasks API，未改动算法）：
  - google-ai-edge/mediapipe 的 FaceLandmarker（tasks.vision）
  - 官方模型 face_landmarker.task，输出 468 个带 z 深度的 3D 人脸顶点
  - 可选 canonical_face_model.obj 提供标准三角面索引（若 assets 中存在）

许可：Apache License 2.0。
"""

from __future__ import annotations

from pathlib import Path
from typing import Optional, Set

import cv2
import numpy as np

from ..core.base import Face3DProvider
from ..core.capabilities import Capability
from ..core.registry import register
from ..core.types import BBox, Face3DResult, Mesh3D

_MODELS_DIR = Path(__file__).resolve().parents[2] / "models"
_ASSETS_DIR = Path(__file__).resolve().parents[2] / "assets"
_MODEL_PATH = _MODELS_DIR / "face_landmarker.task"
_TOPOLOGY_PATH = _ASSETS_DIR / "canonical_face_model.obj"


def _load_triangles(path: Path) -> Optional[np.ndarray]:
    """从 canonical_face_model.obj 解析三角面索引（1-based -> 0-based）。"""
    if not path.exists():
        return None
    faces = []
    with open(path, "r", encoding="utf-8", errors="ignore") as fh:
        for line in fh:
            if line.startswith("f "):
                parts = [p.split("/")[0] for p in line.strip().split()[1:]]
                if len(parts) == 3:
                    faces.append([int(p) - 1 for p in parts])
    return np.array(faces, dtype=np.int32) if faces else None


@register
class MediaPipeProvider(Face3DProvider):
    name = "mediapipe"
    upstream = "https://github.com/google-ai-edge/mediapipe"
    license = "Apache License 2.0"
    version = "1.2.3"
    capabilities = frozenset({
        Capability.DETECT,
        Capability.LANDMARKS_2D,
        Capability.LANDMARKS_3D,
        Capability.MESH_3D,
    })

    def __init__(self, num_faces: int = 1):
        self._num_faces = num_faces
        self._landmarker = None
        self._triangles = _load_triangles(_TOPOLOGY_PATH)
        self._init_error: Optional[str] = None

    def available(self) -> bool:
        if not _MODEL_PATH.exists():
            return False
        if self._landmarker is None and self._init_error is None:
            self._init()
        return self._landmarker is not None

    def close(self) -> None:
        """显式释放底层资源，避免解释器退出时的析构告警。"""
        if self._landmarker is not None:
            try:
                self._landmarker.close()
            except Exception:
                pass
            self._landmarker = None

    def __del__(self):
        self.close()

    def _init(self) -> None:
        try:
            import mediapipe as mp
            from mediapipe.tasks.python import vision
            options = vision.FaceLandmarkerOptions(
                base_options=mp.tasks.BaseOptions(model_asset_path=str(_MODEL_PATH)),
                running_mode=vision.RunningMode.IMAGE,
                num_faces=self._num_faces,
                output_face_blendshapes=True,
                output_facial_transformation_matrixes=True,
            )
            self._landmarker = vision.FaceLandmarker.create_from_options(options)
        except Exception as exc:  # pragma: no cover
            self._init_error = repr(exc)

    def process(self, image: np.ndarray, capabilities: Optional[Set[Capability]] = None) -> Face3DResult:
        if not self.available():
            return Face3DResult(provider=self.name, errors={"init": self._init_error or "模型不可用"})
        import mediapipe as mp

        rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB) if image.ndim == 3 else cv2.cvtColor(image, cv2.COLOR_GRAY2RGB)
        h, w = rgb.shape[:2]
        mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
        detection = self._landmarker.detect(mp_image)

        result = Face3DResult(provider=self.name, capabilities=[c.value for c in self.capabilities])
        if not detection.face_landmarks:
            result.errors["detect"] = "未检测到人脸"
            return result

        # 取面积最大的人脸
        def bbox_of(lms):
            xs = [p.x * w for p in lms]
            ys = [p.y * h for p in lms]
            return (min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys))

        lms = max(detection.face_landmarks, key=lambda ls: bbox_of(ls)[2] * bbox_of(ls)[3])
        x, y, bw, bh = bbox_of(lms)
        result.bbox = BBox(x=x, y=y, width=bw, height=bh)

        result.landmarks_2d = np.array([[p.x * w, p.y * h] for p in lms], dtype=np.float32)
        # MediaPipe 输出的 x,y,z 均为归一化坐标（z 为相对深度），这里保留原始 3D 几何
        result.landmarks_3d = np.array([[p.x, p.y, p.z] for p in lms], dtype=np.float32)
        result.mesh = Mesh3D(vertices=result.landmarks_3d, triangles=self._triangles)

        if detection.face_blendshapes:
            shapes = detection.face_blendshapes[0]
            result.attributes["blendshapes"] = {s.category_name: float(s.score) for s in shapes}
        if detection.facial_transformation_matrixes:
            result.attributes["transformation_matrix"] = np.array(
                detection.facial_transformation_matrixes[0].data, dtype=np.float32
            ).reshape(4, 4)
        return result
