<div align="center">
  <img src="android/app/icon_512.png" alt="logo" width="96">
  <h1>3D 人脸识别 / Face3D</h1>
  <p><b>自研 3D 人脸识别框架 + 安卓 APP</b></p>
  <p>Self-developed 3D face recognition framework & Android app</p>
  <p>
    <img src="https://img.shields.io/badge/自研-original-blue.svg" alt="original">
    <img src="https://img.shields.io/badge/platform-Android%20%7C%20Python-green.svg" alt="platform">
    <img src="https://img.shields.io/badge/3D%20landmarks-478-orange.svg" alt="landmarks">
    <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="license">
  </p>
  <p>
    <a href="https://a98efe28447afa6ae.app.workbuddy.host/?sharecode=Q3RYF7QBKX">
      <img src="https://img.shields.io/badge/🌐%20发布页-在线预览-6f42c1.svg" alt="landing page">
    </a>
    <a href="https://github.com/sitong-zhang/3d-face-recognition-model/releases/download/v1.0.0/Face3D-v1.0.apk">
      <img src="https://img.shields.io/badge/⬇%20Download-APK-2ea44f.svg" alt="download apk">
    </a>
  </p>
</div>

## 项目简介 / Overview

本项目是一套**自研的 3D 人脸识别系统**，包含两部分：

1. **Python 万能接口框架**：一个统一接口，任何识别后端都能作为插件插入，即插即用。
2. **安卓 APP**：真正的 3D 人脸录入与扫描识别，苹果 Face ID 风格的引导与成功动画。

> **自研说明**：本项目的架构设计、万能接口、3D 几何特征算法、头部姿态估计、多角度引导采集、
> 人脸库、全部 UI 与 Face ID 动画、交互流程均为本项目自研实现。底层 3D 关键点检测使用
> MediaPipe 作为基础依赖（如同 App 使用系统相机 API），其许可证与出处在
> `THIRD_PARTY_NOTICES.md` 中列出。

## 功能 / Features

### 安卓 APP（重点）
- **应用图标**：自研设计——人脸 3D 网格 + 扫描取景框 + 识别成功对勾，多分辨率 + 自适应图标
- **极简主界面**：进入即为「录入人脸」「扫描人脸」两个核心入口
- **命名录入**：录入前为每个人输入姓名；按苹果 Face ID 风格**引导缓慢转头**，多角度采集 3D 特征
- **多人人脸库**：可录入多个人，每人保存多个角度的 3D 特征；支持查看与删除
- **扫描识别**：实时 3D 检测，与库内所有人比对，**识别成功后在下方显示此人姓名**
- **苹果 Face ID 动画**：录入分段进度环、扫描旋转光环、成功绿色对勾（带绘制动画+震动）、失败红叉

### Python 万能接口
- 一个 `UniversalFace3D` 接口聚合所有插件，单个插件失败不影响整体
- 内置插件：dlib（检测 / 68 点 / 128D 特征 / 比对）、MediaPipe（478 点 3D 网格 / 表情 / 变换矩阵）
- 新增实现只需继承 `Face3DProvider` + `@register`，无需改动引擎

## 目录结构 / Structure

| 目录 | 说明 |
|------|------|
| `android/` | 安卓 APP 完整工程（自研 UI / 动画 / 交互 / 数据层） |
| `android/app/src/main/java/com/example/face3d/` | 自研核心代码：引擎、人脸库、动画、主流程 |
| `python/` | 万能接口框架与示例 |
| `docs/` | APK 独立发布页（GitHub Pages） |
| `releases/` | 已构建的 APK 安装包 |

## 快速开始 / Quick Start

### 安装 APP
- **直接安装**：下载 [APK](https://github.com/sitong-zhang/3d-face-recognition-model/releases/download/v1.0.0/Face3D-v1.0.apk)（Android 7.0+，需允许安装未知来源）。
- **源码构建**：Android Studio 打开 `android/`，连接设备运行。

使用步骤：
1. 点「录入人脸」→ 输入姓名 → 按屏幕引导缓慢转头，完成多角度 3D 采集 → 绿色对勾 + 显示姓名
2. 点「扫描人脸」→ 正对镜头 → 识别成功后显示该人的姓名
3. 点「管理」可查看已录入人员或删除

### Python
```bash
cd python
pip install -r requirements.txt
bash download_models.sh
python examples/demo.py
```

## 3D 人脸识别是怎么做到的

- **3D 数据来源**：MediaPipe FaceLandmarker 输出 478 个带 z 深度的 3D 顶点 + 4×4 头部变换矩阵。
- **自研 3D 特征**：以鼻尖为原点、双眼外眼角距离为尺度做归一化，得到对平移/缩放稳定、保留深度的 3D 几何特征向量。
- **多角度采集**：录入时通过头部姿态估计（yaw/pitch）引导用户转头，采集 5 个不同角度的 3D 特征，比单帧更稳健。
- **识别比对**：扫描帧与库内每个人所有角度特征做余弦相似度比对，连续匹配确认后判定成功并显示姓名。

> **边界说明（不夸大）**：APP 的人脸比对是基于 3D 几何特征的**演示级方案**，相似度阈值 0.985 为经验值，
> 不是论文级身份识别模型；Face ID 动画为 UI 视觉与交互复刻，不代表具备苹果硬件级深度/活体能力，
> 本项目未实现活体检测。

## 许可证 / License

本项目 MIT。底层基础依赖各自遵循原始许可证，详见 `THIRD_PARTY_NOTICES.md`（根目录 / python / android）。
