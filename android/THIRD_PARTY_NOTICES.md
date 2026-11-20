# 开源依赖声明 / Third-Party Dependencies

本项目（Face3D 安卓 APP）的架构、3D 特征算法、头部姿态估计、多角度引导采集、
人脸库、UI 与 Face ID 动画、交互流程均为本项目**自研实现**。
底层 3D 关键点检测使用以下开源库作为基础依赖（如同使用系统相机 API），按其许可证合规引用：

| 组件 | 来源 | 许可证 | 用途 |
|------|------|--------|------|
| MediaPipe Tasks Vision (FaceLandmarker) | https://github.com/google-ai-edge/mediapipe | Apache 2.0 | 478 点 3D 人脸网格检测（基础依赖） |
| face_landmarker.task 模型 | Google 官方 MediaPipe 模型库 | Apache 2.0 | 3D 人脸推理权重 |
| CameraX | https://github.com/androidx/androidx | Apache 2.0 | 相机预览与取帧 |
| Material Components | https://github.com/material-components/material-components-android | Apache 2.0 | UI 组件与主题 |
| AppCompat / Activity | https://github.com/androidx/androidx | Apache 2.0 | Android 兼容层 |

## 自研部分 / Original Work
- **万能接口思想与整体架构设计**
- **3D 几何特征提取与归一化算法**（`FaceEngine.java`：鼻尖原点 + 眼距尺度 + z 深度保留）
- **头部姿态估计**（yaw/pitch 近似，用于转头引导）
- **多角度引导采集逻辑**（分段进度环 + 姿态去重，模拟 Face ID 转圈采集）
- **本地多人人脸库**（`FaceStore.java`：姓名 + 多角度特征，增删改查与最佳匹配）
- **Face ID 风格动画**（`FaceIdAnimationView.java`：自绘分段进度环 / 旋转光环 / 对勾绘制 / 弹跳）
- **应用图标**（`gen_icon.py`：人脸 3D 网格 + 扫描角 + 对勾，多分辨率自适应图标）
- **UI 布局、深色主题与全部交互流程**（`MainActivity.java`、`activity_main.xml`）

## 边界说明 / Limitations
1. 人脸比对基于 **3D 几何特征（478 点归一化）余弦相似度**，属演示级方案，非论文级身份识别模型。
2. 结果受光线、角度、遮挡影响；阈值 0.985 为经验值，可按场景调整。
3. 未接入活体检测；Face ID 动画仅为 UI 视觉效果，不代表具备 Apple 的硬件级深度/活体能力。
4. 模型文件随 APK 打包在 `assets/`，体积约 3.6MB。
