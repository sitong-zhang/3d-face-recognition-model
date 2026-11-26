<div align="center">
  <img src="assets/scan-face.svg" alt="Universal3DFace" width="72">
  <h1>Universal3DFace</h1>
  <p>一个万能接口的 3D 人脸识别框架 —— 任何开源实现都能插进来</p>
</div>

> Universal3DFace 是一个可插拔的万能接口框架：对外统一入口，对内聚合多种识别能力，
> 插件即插即用。所集成的开源项目见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 它是什么

一个**可插拔的插件框架**：对外只有一个万能接口 `UniversalFace3D`，
对内把多个开源实现包装成统一插件。当前已插入：

| 插件 | 提供的能力 | 底层开源项目 |
|------|-----------|--------------|
| `dlib` | 人脸检测、68 点 2D 关键点、128D 身份特征、人脸比对 | [dlib](https://github.com/davisking/dlib) |
| `mediapipe` | 人脸检测、2D 关键点、**478 点 3D 顶点 + 898 三角面网格**、52 项表情系数、4×4 变换矩阵 | [MediaPipe](https://github.com/google-ai-edge/mediapipe) |

## 快速开始

```bash
pip install -r requirements.txt
python examples/demo.py          # 用内置公开测试人像演示
python examples/demo.py your.jpg # 或指定自己的图片
```

```python
from universal3dface import UniversalFace3D
import cv2

engine = UniversalFace3D()                 # 自动发现并装载所有插件
print(engine.list_providers())             # 查看背后接了哪些开源实现

img = cv2.imread("face.jpg")
result = engine.analyze(img)               # 一次聚合所有插件能力
print(result.summary())
# -> bbox / landmarks_2d / landmarks_3d / mesh / embedding ...

same = engine.verify(img, img)             # 人脸比对
print(same.to_dict())                      # -> similarity / distance / is_same

only3d = engine.analyze(img, provider_names=["mediapipe"])  # 只要 3D 网格
```

## 万能接口如何扩展（核心设计）

任何开源实现都能接入，**无需改动引擎代码**。只需三步：

```python
from universal3dface import Face3DProvider, Capability, Face3DResult, registry
from universal3dface.core.registry import register

@register
class MyProvider(Face3DProvider):
    name = "my_engine"
    upstream = "https://github.com/xxx/yyy"
    license = "MIT"
    capabilities = frozenset({Capability.DETECT, Capability.LANDMARKS_3D})

    def process(self, image, capabilities=None):
        # 调用你接入的开源库，把结果填进统一契约
        return Face3DResult(provider=self.name, bbox=..., landmarks_3d=...)

# 在 adapters/__init__.py 里 import 它，即被 UniversalFace3D 自动发现
```

引擎的 `analyze()` 会按注册顺序聚合所有插件结果（先注册者优先），单个插件出错不影响整体。

## 能力（Capability）一览

| 能力 | 含义 | dlib | mediapipe |
|------|------|:----:|:---------:|
| `DETECT` | 人脸检测 | ✅ | ✅ |
| `LANDMARKS_2D` | 2D 关键点 | ✅(68) | ✅(478) |
| `LANDMARKS_3D` | 3D 关键点(带 z) | — | ✅(478) |
| `MESH_3D` | 3D 网格(顶点+面) | — | ✅(898面) |
| `EMBED` | 身份特征向量 | ✅(128D) | — |
| `VERIFY` | 人脸比对 | ✅ | — |
| `LIVENESS` | 活体检测 | 预留 | 预留 |

## 目录结构

```
universal3dface/
├── universal3dface/
│   ├── core/                 # 自研：万能接口框架（能力/契约/基类/注册表/引擎）
│   └── adapters/             # 自研封装：dlib、mediapipe
├── models/                   # 第三方开源模型权重（见 THIRD_PARTY_NOTICES）
├── assets/                   # 图标(Lucide) + 网格拓扑(MediaPipe)
├── examples/demo.py          # 端到端演示
├── THIRD_PARTY_NOTICES.md    # 第三方组件与许可证
└── requirements.txt
```

## 能力来源

- 2D 检测与 128D 身份特征由 dlib 提供；带 z 深度的 3D 顶点与网格由 MediaPipe 提供。
- 框架负责统一契约、插件注册与结果聚合，识别能力由接入的开源模型提供。
- dlib 68 点 / MediaPipe 478 点 / 3D 网格 / 表情系数 / 变换矩阵按需取用。
- 详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 的组件清单。
