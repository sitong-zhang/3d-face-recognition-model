# 开源依赖声明 / Third-Party Dependencies

Face3D 安卓 APP 的架构、3D 人脸资产与联合识别算法、头部姿态估计、
多角度引导采集、人脸库、UI 与交互流程为本项目实现。以下开源组件按其许可证引用：

| 组件 | 来源 | 许可证 | 用途 |
|------|------|--------|------|
| Glance (Face Unlock for Mac) | https://github.com/jonnyoo/glance | MIT | 扫描/成功/失败三态解锁动画素材（逐帧透明化后内置于 `assets/anim/`） |
| MediaPipe Tasks Vision (FaceLandmarker) | https://github.com/google-ai-edge/mediapipe | Apache 2.0 | 478 点 3D 人脸网格检测（基础依赖） |
| MobileFaceNet (ArcFace 512D) | InsightFace / 开源 TFLite 权重 | MIT | 判别式身份特征提取 |
| face_landmarker.task 模型 | Google 官方 MediaPipe 模型库 | Apache 2.0 | 3D 人脸推理权重 |
| CameraX | https://github.com/androidx/androidx | Apache 2.0 | 相机预览与取帧 |
| Material Components | https://github.com/material-components/material-components-android | Apache 2.0 | UI 组件与主题 |
| AppCompat / Activity | https://github.com/androidx/androidx | Apache 2.0 | Android 兼容层 |
| Lucide (scan-face 图标) | https://github.com/lucide-icons/lucide | ISC | 应用图标扫描人脸图形 |

## 自研部分 / Original Work
- **万能接口思想与整体架构设计**
- **完整 3D 人脸资产**：每人保存多角度 478×3 归一化 3D 几何（`FaceEngine.normalize` + `FaceStore.geometries`）
- **3D 联合识别**：512D 身份特征 + 3D 结构联合比对（`FaceStore.findBest`）
- **头部姿态估计**（yaw/pitch 近似，用于转头引导）
- **多角度引导采集逻辑**（姿态去重，模拟 Face ID 转圈采集）
- **本地多人人脸库**（`FaceStore.java`：姓名 + 多角度特征 + 3D 几何 + 网格，增删改查与最佳匹配）
- **逐帧透明动画播放器**（`FrameAnimView.java`：加载并播放内置动画帧）
- **应用图标**（`gen_icon.py`：Lucide scan-face + 深色渐变底，多分辨率自适应图标）
- **UI 布局、深色主题与全部交互流程**（`MainActivity.java`、`activity_main.xml`）

## 技术说明
1. 人脸比对以判别式 512D 身份特征为主门限，完整 3D 几何资产每次参与联合比对并计入融合置信度。
2. 与 Glance 只存 512D 向量、丢弃图像与几何不同，本作保留并比对每个用户的完整 3D 人脸模型。
3. 模型文件随 APK 打包在 `assets/`，体积约 3.6MB，离线可用。
