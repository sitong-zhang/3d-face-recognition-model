<div align="center">
  <img src="python/assets/scan-face.svg" alt="logo" width="70">
  <h1>3D 人脸识别模型 / 3D Face Recognition</h1>
  <p><b>一个万能接口的 3D 人脸识别框架 + 安卓 APP</b></p>
  <p>Universal plugin interface for 3D face recognition, with an Android app</p>
  <p>
    <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="license">
    <img src="https://img.shields.io/badge/platform-Android%20%7C%20Python-green.svg" alt="platform">
    <img src="https://img.shields.io/badge/3D%20landmarks-478-orange.svg" alt="landmarks">
  </p>
  <p>
    <a href="https://github.com/sitong-zhang/3d-face-recognition-model/releases/download/v1.0.0/Face3D-v1.0.apk">
      <img src="https://img.shields.io/badge/⬇%20Download-APK%20v1.0-2ea44f.svg" alt="download apk">
    </a>
  </p>
</div>

> **原则 / Principle**：不编造、不夸大。所有算法来自成熟开源项目，本仓库只做接口封装与 APP 集成，
> 并逐个如实署名。No fabricated claims. All algorithms come from mature open-source projects.

## 目录结构 / Structure

| 目录 | 说明 |
|------|------|
| `python/` | **万能接口框架**：一个接口，任何开源实现都能作为插件插入 |
| `android/` | **安卓 APP**：录入人脸 + 扫描人脸，苹果 Face ID 风格成功动画 |

## 功能 / Features

### Python 万能接口
- 一个 `UniversalFace3D` 接口，聚合所有插件结果，单个插件失败不影响整体
- 已接入插件：**dlib**（检测 / 68 点 / 128D 特征 / 比对）、**MediaPipe**（478 点 3D 网格 / 表情 / 变换矩阵）
- 新增开源实现只需继承 `Face3DProvider` + `@register`，无需改引擎

### 安卓 APP
- **录入人脸**：相机采集 3D 人脸，提取 478 点 3D 几何特征保存到本地
- **扫描人脸**：采集当前 3D 特征与录入特征比对，反馈成功/失败
- **成功动画**：模仿苹果 Face ID —— 绿色圆环 + 对勾绘制动画
- 深色渐变 UI、实时相机预览、圆角按钮

## 快速开始 / Quick Start

### Python
```bash
cd python
pip install -r requirements.txt
bash download_models.sh        # 下载第三方开源模型
python examples/demo.py
```

### Android
- **直接安装**：下载 [Face3D-v1.0.apk](https://github.com/sitong-zhang/3d-face-recognition-model/releases/download/v1.0.0/Face3D-v1.0.apk) 安装到手机（Android 7.0+，需允许安装未知来源）。
- **源码构建**：用 Android Studio 打开 `android/` 目录，连接设备或模拟器运行。

APP 使用步骤：打开后点「录入人脸」采集 3D 特征 → 点「扫描人脸」验证，成功时播放苹果 Face ID 风格绿色对勾动画。

## 演示 / Demos

- `python/examples/demo.py`：端到端演示（检测 / 3D 网格 / 128D 特征 / 比对）
- `python/examples/custom_plugin.py`：演示万能接口的即插即用（不改引擎插入新插件）

## 3D 人脸识别是怎么做到的

- **Python 端**：MediaPipe FaceLandmarker 输出 478 个带 z 深度的 3D 顶点 + 898 三角面网格；dlib 提供 128D 身份特征。
- **APP 端**：MediaPipe FaceLandmarker（与 Python 同一模型族）输出 3D 顶点；以鼻尖为原点、双眼距离为尺度归一化，得到 3D 几何特征；录入即保存该特征，扫描即做 3D 几何相似度比对。

> 诚实说明：APP 的人脸比对是**基于 3D 几何特征的演示级方案**，不是论文级身份识别模型。

## 许可证 / License

本项目 MIT。集成的第三方库各自遵循原始许可证，详见：
- `python/THIRD_PARTY_NOTICES.md`
- `android/THIRD_PARTY_NOTICES.md`
