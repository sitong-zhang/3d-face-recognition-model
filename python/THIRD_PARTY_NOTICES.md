# 开源依赖声明 / Third-Party Dependencies

Universal3DFace 是一套万能接口框架：能力抽象、数据契约、插件注册与引擎编排均为本项目自研。
底层识别能力由下列开源组件提供，按其许可证引用。

| 组件 | 来源仓库 | 许可证 | 用途 |
|------|----------|--------|------|
| dlib | https://github.com/davisking/dlib | Boost Software License 1.0 | 人脸检测、68 点 2D 关键点、128D 人脸识别特征 |
| MediaPipe | https://github.com/google-ai-edge/mediapipe | Apache License 2.0 | Face Landmarker：478 个 3D 人脸顶点、blendshapes、变换矩阵 |
| face_landmarker.task 模型 | Google 官方 MediaPipe 模型库 | Apache License 2.0 | 3D 人脸网格推理权重 |
| shape_predictor_68_face_landmarks.dat | dlib.net 官方模型 | Boost Software License 1.0 | 68 点关键点模型 |
| dlib_face_recognition_resnet_model_v1.dat | dlib.net 官方模型 | Boost Software License 1.0 | 128D ResNet-34 识别模型 |
| canonical_face_model.obj | MediaPipe 官方仓库 | Apache License 2.0 | 468 点标准人脸三角面拓扑 |
| scan-face.svg 图标 | https://github.com/lucide-icons/lucide | ISC License | 项目人脸扫描图标 |
| skimage.data.astronaut | https://github.com/scikit-image/scikit-image | BSD-3-Clause | demo 测试人像（公开领域 NASA 图像） |

## 项目自研部分

以下为本项目自研的框架层代码：

- `universal3dface/core/*`：能力枚举、数据契约（dataclass）、插件基类、注册表、万能引擎。
- `universal3dface/adapters/*`：对上述第三方库的调用封装（adapter 模式）。
- `universal3dface/reconstruction/*`：单目 3D 重建、网格拓扑与软件渲染。
- `examples/demo.py`：演示脚本。

## 能力来源

- 2D 关键点（dlib 68 点）与带 z 深度的 3D 顶点、网格（MediaPipe 478 点）分别由对应的开源组件提供。
- 识别与比对能力由接入的开源模型提供；框架负责统一契约、注册与编排。
- `verify()` 的 0.6 欧氏距离阈值沿用 dlib 官方示例惯例。

## 可扩展的开源候选

| 仓库 | 许可证 | 能力 |
|------|--------|------|
| microsoft/Deep3DFaceReconstruction | MIT | 单图 3D 人脸重建 |
| sicxu/Deep3DFaceRecon_pytorch | MIT | PyTorch 版 3D 重建（NoW 基准 1.41mm） |
| yfeng95/PRNet | MIT | 实时 3D 人脸重建 + 密集对齐 |
| yfeng95/DECA | 研究用途 | 细节人脸捕获与动画（SIGGRAPH 2021） |
| alfredtorres/3DFacePointCloudNet | MIT | 基于点云的 3D 人脸识别 |

这些项目如被接入，需遵守其各自许可证（部分要求研究用途/模型权重申请），届时须更新本声明。
