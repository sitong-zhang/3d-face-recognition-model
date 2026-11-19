"""
Dlib 适配器插件。

封装的开源实现（原样调用其官方 API，未改动算法）：
  - dlib.get_frontal_face_detector  : 人脸检测（HOG + SVM）
  - dlib.shape_predictor            : 68 点人脸关键点（模型 shape_predictor_68_face_landmarks.dat）
  - dlib.face_recognition_model_v1  : 128 维人脸身份特征（ResNet-34，模型 dlib_face_recognition_resnet_model_v1.dat）

许可：dlib 为 Boost Software License 1.0；模型由 dlib.net 官方提供。
注意：dlib 的 68 点是 2D 关键点，128D 特征用于识别/比对；真正的 3D 网格由 MediaPipe 适配器提供。
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Optional, Set

import cv2
import dlib
import numpy as np

from ..core.base import Face3DProvider
from ..core.capabilities import Capability
from ..core.registry import register
from ..core.types import BBox, Face3DResult, VerifyResult

_MODELS_DIR = Path(__file__).resolve().parents[2] / "models"
_PREDICTOR_PATH = _MODELS_DIR / "shape_predictor_68_face_landmarks.dat"
_RECOGNIZER_PATH = _MODELS_DIR / "dlib_face_recognition_resnet_model_v1.dat"

# dlib 官方示例采用的欧氏距离阈值（128D 特征，LFW 上约 99.38% 准确率）
_VERIFY_THRESHOLD = 0.6


@register
class DlibProvider(Face3DProvider):
    name = "dlib"
    upstream = "https://github.com/davisking/dlib"
    license = "Boost Software License 1.0"
    version = dlib.__version__
    capabilities = frozenset({
        Capability.DETECT,
        Capability.LANDMARKS_2D,
        Capability.EMBED,
        Capability.VERIFY,
    })

    def __init__(self):
        self._detector = dlib.get_frontal_face_detector()
        self._predictor = dlib.shape_predictor(str(_PREDICTOR_PATH)) if _PREDICTOR_PATH.exists() else None
        self._recognizer = dlib.face_recognition_model_v1(str(_RECOGNIZER_PATH)) if _RECOGNIZER_PATH.exists() else None

    def available(self) -> bool:
        return _PREDICTOR_PATH.exists() and _RECOGNIZER_PATH.exists()

    def _largest_face(self, rgb: np.ndarray):
        rects = self._detector(rgb, 1)
        if not rects:
            return None
        return max(rects, key=lambda r: r.width() * r.height())

    def process(self, image: np.ndarray, capabilities: Optional[Set[Capability]] = None) -> Face3DResult:
        rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB) if image.ndim == 3 else cv2.cvtColor(image, cv2.COLOR_GRAY2RGB)
        result = Face3DResult(provider=self.name, capabilities=[c.value for c in self.capabilities])
        rect = self._largest_face(rgb)
        if rect is None:
            result.errors["detect"] = "未检测到人脸"
            return result

        result.bbox = BBox(
            x=float(rect.left()), y=float(rect.top()),
            width=float(rect.width()), height=float(rect.height()),
        )
        if self._predictor is None:
            return result

        shape = self._predictor(rgb, rect)
        result.landmarks_2d = np.array([(shape.part(i).x, shape.part(i).y) for i in range(68)], dtype=np.float32)

        if self._recognizer is not None:
            descriptor = self._recognizer.compute_face_descriptor(rgb, shape)
            result.embedding = np.array(descriptor, dtype=np.float32)
        return result

    def _embedding(self, image: np.ndarray) -> Optional[np.ndarray]:
        rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB) if image.ndim == 3 else cv2.cvtColor(image, cv2.COLOR_GRAY2RGB)
        rect = self._largest_face(rgb)
        if rect is None or self._predictor is None or self._recognizer is None:
            return None
        shape = self._predictor(rgb, rect)
        return np.array(self._recognizer.compute_face_descriptor(rgb, shape), dtype=np.float32)

    def verify(self, img_a: np.ndarray, img_b: np.ndarray) -> VerifyResult:
        e1, e2 = self._embedding(img_a), self._embedding(img_b)
        if e1 is None or e2 is None:
            return VerifyResult(provider=self.name, metric="euclidean_128d", errors={"verify": "至少一张图未检测到人脸"})
        dist = float(np.linalg.norm(e1 - e2))
        sim = float(np.dot(e1, e2) / (np.linalg.norm(e1) * np.linalg.norm(e2) + 1e-8))
        return VerifyResult(
            provider=self.name, metric="euclidean_128d",
            similarity=sim, distance=dist,
            is_same=bool(dist < _VERIFY_THRESHOLD), threshold=_VERIFY_THRESHOLD,
        )
