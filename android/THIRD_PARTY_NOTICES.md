# 安卓 APP 第三方开源声明（如实列出）

本 APP 只做 UI 与流程封装，人脸算法全部来自第三方开源项目，按许可证原样调用官方 SDK。

| 组件 | 来源 | 许可证 | 用途 |
|------|------|--------|------|
| MediaPipe Tasks Vision (FaceLandmarker) | https://github.com/google-ai-edge/mediapipe | Apache 2.0 | 478 点 3D 人脸网格检测 |
| face_landmarker.task 模型 | Google 官方 MediaPipe 模型库 | Apache 2.0 | 3D 人脸推理权重 |
| CameraX | https://github.com/androidx/androidx | Apache 2.0 | 相机预览与取帧 |
| Material Components | https://github.com/material-components/material-components-android | Apache 2.0 | UI 组件与主题 |
| AppCompat / Activity | https://github.com/androidx/androidx | Apache 2.0 | Android 兼容层 |

## 自研部分
- UI 布局与深色主题、Face ID 风格动画（`FaceIdAnimationView.java`，自绘圆环 + 对勾动画）
- 录入/扫描流程、本地 3D 特征存储与比对逻辑（`MainActivity.java`、`FaceEngine.java`）

## 诚实限制
1. 人脸比对基于 **3D 几何特征（478 点归一化）余弦相似度**，属演示级方案，非论文级身份识别模型。
2. 结果受光线、角度、遮挡影响；阈值 0.985 为经验值，可按场景调整。
3. 未接入活体检测；Face ID 动画仅为 UI 视觉效果，不代表具备 Apple 的硬件级深度/活体能力。
4. 模型文件随 APK 打包在 `assets/`，体积约 3.6MB。
