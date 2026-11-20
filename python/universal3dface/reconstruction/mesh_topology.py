#!/usr/bin/env python3
"""人脸网格拓扑加载器（自研）。

解析 MediaPipe 官方 canonical_face_model.obj：468 顶点 + 898 三角面。
该拓扑是人脸几何的"标准骨架"，重建算法把检测到的 3D 锚点形变到这套骨架上，
从而由稀疏关键点恢复出完整的密集人脸网格。
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import numpy as np


@dataclass
class FaceTopology:
    vertices: np.ndarray   # (468,3) canonical 顶点
    faces: np.ndarray      # (898,3) 三角面索引
    uv: np.ndarray | None  # (468,2) 纹理坐标

    @property
    def num_vertices(self) -> int:
        return len(self.vertices)

    @property
    def num_faces(self) -> int:
        return len(self.faces)


def load_canonical_topology(obj_path: str | Path) -> FaceTopology:
    """解析 OBJ，返回 canonical 人脸拓扑。"""
    obj_path = Path(obj_path)
    if not obj_path.exists():
        raise FileNotFoundError(f"canonical 拓扑文件不存在: {obj_path}")

    verts: list[list[float]] = []
    uvs: list[list[float]] = []
    faces: list[list[int]] = []
    for line in obj_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if parts[0] == "v":
            verts.append([float(parts[1]), float(parts[2]), float(parts[3])])
        elif parts[0] == "vt":
            uvs.append([float(parts[1]), float(parts[2])])
        elif parts[0] == "f":
            # 形如 "174/43 156/119 134/220"，取顶点索引（OBJ 从 1 开始）
            idx = [int(tok.split("/")[0]) - 1 for tok in parts[1:4]]
            faces.append(idx)

    if len(faces) == 0:
        raise ValueError("OBJ 中没有三角面")

    return FaceTopology(
        vertices=np.asarray(verts, dtype=np.float64),
        faces=np.asarray(faces, dtype=np.int64),
        uv=np.asarray(uvs, dtype=np.float64) if uvs else None,
    )
