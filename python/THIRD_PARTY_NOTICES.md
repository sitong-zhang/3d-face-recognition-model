# 第三方开源声明（如实列出，无夸大）

本项目 Universal3DFace **只做接口封装与编排**。所有实际的人脸算法均来自下列第三方开源项目，
按各自许可证原样调用其官方 API，未修改其算法代码。以下星数为调研时（2026-11）GitHub 页面数据，仅供参考。

| 组件 | 来源仓库 | 许可证 | 用途 | 星数(约) |
|------|----------|--------|------|----------|
| dlib | https://github.com/davisking/dlib | Boost Software License 1.0 | 人脸检测、68 点 2D 关键点、128D 人脸识别特征 | 11k+ |
| MediaPipe | https://github.com/google-ai-edge/mediapipe | Apache License 2.0 | Face Landmarker：478 个 3D 人脸顶点、blendshapes、变换矩阵 | 37.7k |
| face_landmarker.task 模型 | Google 官方 MediaPipe 模型库 | Apache License 2.0 | 3D 人脸网格推理权重 | - |
| shape_predictor_68_face_landmarks.dat | dlib.net 官方模型 | Boost Software License 1.0 | 68 点关键点模型 | - |
| dlib_face_recognition_resnet_model_v1.dat | dlib.net 官方模型 | Boost Software License 1.0 | 128D ResNet-34 识别模型 | - |
| canonical_face_model.obj | MediaPipe 官方仓库 | Apache License 2.0 | 468 点标准人脸三角面拓扑 | - |
| scan-face.svg 图标 | https://github.com/lucide-icons/lucide | ISC License | 项目人脸扫描图标 | 13k+ |
| skimage.data.astronaut | https://github.com/scikit-image/scikit-image | BSD-3-Clause | demo 测试人像（公开领域 NASA 图像） | 12k+ |

## 项目自研部分（明确边界）

以下是本项目**自己编写**的代码，仅涉及"封装/编排/接口"，不含任何自研人脸识别算法：

- `universal3dface/core/*`：能力枚举、数据契约（dataclass）、插件基类、注册表、万能引擎。
- `universal3dface/adapters/*`：对上述第三方库的调用封装（adapter 模式）。
- `examples/demo.py`：演示脚本。

## 诚实的已知限制

1. **dlib 的 68 点关键点是 2D 的**，不是 3D；3D 几何（带 z 深度）由 MediaPipe 提供。
2. 本系统是**算法集成框架**，识别精度完全取决于所接入的开源模型（dlib 128D / MediaPipe），本项目未训练任何模型、未声称任何精度指标。
3. `verify()` 的 0.6 欧氏距离阈值来自 dlib 官方示例惯例，不是本项目提出的结论。
4. 未接入活体检测实现，`Capability.LIVENESS` 仅为接口预留位，不代表系统具备该能力。
5. 若要达到论文级 3D 重建（如 Deep3DFace、DECA），可继续按插件契约接入；本项目当前未集成它们，也未宣称集成。

## 可继续接入的高质量开源候选（调研发现，尚未集成）

| 仓库 | 许可证 | 能力 |
|------|--------|------|
| microsoft/Deep3DFaceReconstruction | MIT | 单图 3D 人脸重建 |
| sicxu/Deep3DFaceRecon_pytorch | MIT | PyTorch 版 3D 重建（NoW 基准 1.41mm） |
| yfeng95/PRNet | MIT | 实时 3D 人脸重建 + 密集对齐 |
| yfeng95/DECA | 研究用途 | 细节人脸捕获与动画（SIGGRAPH 2021） |
| alfredtorres/3DFacePointCloudNet | MIT | 基于点云的 3D 人脸识别 |

这些项目如被接入，需遵守其各自许可证（部分要求研究用途/模型权重申请），届时须更新本声明。
