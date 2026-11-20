#!/usr/bin/env python3
"""单目 3D 人脸重建核心算法（自研实现）。

技术实质（如实说明）：
    从一张普通 2D 照片出发，用 MediaPipe FaceLandmarker 检测 478 个带 z 深度的
    3D 人脸锚点。其中前 468 个锚点与 MediaPipe 官方 canonical_face_model 的
    468 个顶点一一对应（这是官方拓扑的设计约定）。于是：
        1) 把检测到的 3D 锚点作为「形变后的顶点」；
        2) 套用 canonical 拓扑的 898 个三角面描述顶点连接关系；
        3) 以鼻尖为原点恢复到统一尺度；
        4) 可选做重心细分 + 拉普拉斯平滑，得到更平滑的密集网格。
    最终输出一个可旋转、多视角查看的完整 3D 人脸网格（OBJ / PLY）。

边界（不夸大）：
    - 这是「锚点驱动的网格拟合」方案，几何精度受限于 MediaPipe 的 3D 锚点，
      并非从零训练的深度学习重建（如 DECA / EMOCA）。
    - 细分只增加几何平滑度，不凭空增加真实细节。
    - 它确实能从单目 2D 恢复完整 3D 人脸表面，可用于弥补无 3D 硬件设备的
      可视化与几何比对需求。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
import numpy as np

from .mesh_topology import FaceTopology, load_canonical_topology

# 依赖：mediapipe（Google, Apache-2.0），作为底层 3D 锚点检测器（基础依赖）
import mediapipe as mp
from mediapipe.tasks.python import vision, BaseOptions
from mediapipe.tasks.python.vision import face_landmarker as fl


DEFAULT_MODEL = Path(__file__).resolve().parents[2] / "models" / "face_landmarker.task"
DEFAULT_TOPOLOGY = Path(__file__).resolve().parents[2] / "assets" / "canonical_face_model.obj"


@dataclass
class ReconstructionResult:
    """一次重建的结果。"""
    vertices: np.ndarray                 # (N,3) 重建后的 3D 顶点
    faces: np.ndarray                    # (M,3) 三角面索引
    landmarks: np.ndarray                # (468,3) 原始 3D 锚点（鼻尖原点）
    transform: np.ndarray | None = None  # 4x4 头部变换矩阵（含姿态/尺度）
    normals: np.ndarray | None = None    # (N,3) 顶点法线
    meta: dict = field(default_factory=dict)

    @property
    def num_vertices(self) -> int:
        return len(self.vertices)

    @property
    def num_faces(self) -> int:
        return len(self.faces)

    def center_and_scale(self, target: float = 1.0) -> np.ndarray:
        """返回归一化到指定尺度的顶点副本（便于渲染/比对）。"""
        v = self.vertices.copy()
        center = v.mean(axis=0)
        v -= center
        span = np.max(np.linalg.norm(v, axis=1))
        if span > 1e-9:
            v *= target / span
        return v


class MonocularFaceReconstructor:
    """单目 3D 人脸重建器。"""

    def __init__(
        self,
        model_path: str | Path = DEFAULT_MODEL,
        topology_path: str | Path = DEFAULT_TOPOLOGY,
        subdivision: int = 1,
        smooth_iterations: int = 2,
    ):
        model_path = Path(model_path)
        if not model_path.exists():
            raise FileNotFoundError(f"FaceLandmarker 模型不存在: {model_path}")
        self.topology: FaceTopology = load_canonical_topology(topology_path)
        self.subdivision = int(subdivision)
        self.smooth_iterations = int(smooth_iterations)

        base = BaseOptions(model_asset_path=str(model_path))
        opts = fl.FaceLandmarkerOptions(
            base_options=base,
            running_mode=vision.RunningMode.IMAGE,
            num_faces=1,
            output_facial_transformation_matrixes=True,
        )
        self._detector = fl.FaceLandmarker.create_from_options(opts)

    def close(self) -> None:
        if getattr(self, "_detector", None) is not None:
            self._detector.close()
            self._detector = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    # ---------- 公开 API ----------
    def reconstruct(self, image: np.ndarray) -> ReconstructionResult | None:
        """输入 RGB/HWC uint8 图像，重建 3D 人脸；无人脸返回 None。"""
        arr = np.asarray(image)
        if arr.ndim != 3 or arr.shape[2] != 3:
            raise ValueError("输入必须是 HWC 三通道图像")
        h, w = arr.shape[:2]

        mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=np.asarray(arr, dtype=np.uint8))
        result = self._detector.detect(mp_img)
        lms_list = getattr(result, "face_landmarks", None) or getattr(result, "faceLandmarks", [])
        if not lms_list:
            return None

        lms = lms_list[0]
        all_pts = np.array([[p.x, p.y, p.z] for p in lms], dtype=np.float64)  # (478,3)
        anchors = all_pts[:468].copy()  # 与 canonical 拓扑一一对应

        # 坐标转换：x,y 为归一化图像坐标，z 为相对鼻尖深度。
        # 统一乘以图像宽度 w 保持各轴比例一致；以鼻尖(索引1)为原点。
        pts = anchors.copy()
        pts[:, 0] *= w
        pts[:, 1] *= w
        pts[:, 2] *= w
        nose = pts[1].copy()
        pts -= nose
        # MediaPipe 的 z 方向朝屏幕内为正，翻转 y/z 让坐标系符合常规 3D（y 上、z 朝观察者）
        pts[:, 1] *= -1
        pts[:, 2] *= -1

        transform = None
        mats = getattr(result, "facial_transformation_matrixes", None) or \
               getattr(result, "facialTransformationMatrixes", None)
        if mats:
            transform = np.asarray(mats[0].data, dtype=np.float64).reshape(4, 4)

        vertices, faces = pts.copy(), self.topology.faces.copy()
        if self.subdivision > 0:
            vertices, faces = self._subdivide(vertices, faces, times=self.subdivision)
        if self.smooth_iterations > 0:
            vertices = self._laplacian_smooth(vertices, faces, iterations=self.smooth_iterations)

        normals = self._compute_vertex_normals(vertices, faces)
        return ReconstructionResult(
            vertices=vertices,
            faces=faces,
            landmarks=pts,
            transform=transform,
            normals=normals,
            meta={
                "image_size": (w, h),
                "detected_landmarks": len(lms),
                "source_vertices": 468,
                "subdivision": self.subdivision,
                "smooth_iterations": self.smooth_iterations,
            },
        )

    # ---------- 网格处理 ----------
    @staticmethod
    def _subdivide(vertices: np.ndarray, faces: np.ndarray, times: int = 1):
        """重心细分：每个三角形分成 4 个，边界共享中点去重。"""
        v, f = vertices, faces
        for _ in range(times):
            edge_map: dict[tuple[int, int], int] = {}
            new_verts = [p for p in v]

            def midpoint(i: int, j: int) -> int:
                key = (min(i, j), max(i, j))
                if key in edge_map:
                    return edge_map[key]
                m = (v[i] + v[j]) / 2.0
                idx = len(new_verts)
                new_verts.append(m)
                edge_map[key] = idx
                return idx

            new_faces = []
            for a, b, c in f:
                ab = midpoint(a, b)
                bc = midpoint(b, c)
                ca = midpoint(c, a)
                new_faces.extend([
                    [a, ab, ca], [ab, b, bc], [ca, bc, c], [ab, bc, ca],
                ])
            v = np.asarray(new_verts, dtype=np.float64)
            f = np.asarray(new_faces, dtype=np.int64)
        return v, f

    @staticmethod
    def _laplacian_smooth(vertices: np.ndarray, faces: np.ndarray, iterations: int = 1,
                         factor: float = 0.35) -> np.ndarray:
        """拉普拉斯平滑：顶点向邻居均值靠拢，让表面更自然。"""
        v = vertices.copy()
        n = len(v)
        adj: list[list[int]] = [[] for _ in range(n)]
        for a, b, c in faces:
            adj[a].extend((b, c))
            adj[b].extend((a, c))
            adj[c].extend((a, b))
        for _ in range(iterations):
            updated = v.copy()
            for i in range(n):
                nb = adj[i]
                if nb:
                    updated[i] = v[i] + factor * (np.mean(v[nb], axis=0) - v[i])
            v = updated
        return v

    @staticmethod
    def _compute_vertex_normals(vertices: np.ndarray, faces: np.ndarray) -> np.ndarray:
        normals = np.zeros_like(vertices)
        for a, b, c in faces:
            n = np.cross(vertices[b] - vertices[a], vertices[c] - vertices[a])
            normals[a] += n
            normals[b] += n
            normals[c] += n
        norm = np.linalg.norm(normals, axis=1, keepdims=True)
        norm[norm < 1e-12] = 1.0
        return normals / norm

    # ---------- 导出 ----------
    @staticmethod
    def export_obj(result: ReconstructionResult, path: str | Path) -> Path:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        v, f = result.vertices, result.faces
        with path.open("w", encoding="utf-8") as fh:
            fh.write("# Reconstructed 3D face (MonocularFaceReconstructor)\n")
            for x, y, z in v:
                fh.write(f"v {x:.6f} {y:.6f} {z:.6f}\n")
            for a, b, c in f:
                fh.write(f"f {a+1} {b+1} {c+1}\n")
        return path

    @staticmethod
    def export_ply(result: ReconstructionResult, path: str | Path) -> Path:
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        v, f = result.vertices, result.faces
        with path.open("w", encoding="utf-8") as fh:
            fh.write("ply\nformat ascii 1.0\n")
            fh.write(f"element vertex {len(v)}\n")
            fh.write("property float x\nproperty float y\nproperty float z\n")
            fh.write(f"element face {len(f)}\n")
            fh.write("property list uchar int vertex_indices\nend_header\n")
            for x, y, z in v:
                fh.write(f"{x:.6f} {y:.6f} {z:.6f}\n")
            for a, b, c in f:
                fh.write(f"3 {a} {b} {c}\n")
        return path
