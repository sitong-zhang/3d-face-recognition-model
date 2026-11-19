"""
演示"万能接口"的可扩展性：不改引擎一行代码，插入一个自定义插件。

这里用一个基于 OpenCV 的 Haar 级联检测器做演示（OpenCV 开源，Apache-2.0）。
它演示的是"接入方式"，你可以把 process() 里换成任意开源实现。
"""

import sys
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from universal3dface import (  # noqa: E402
    UniversalFace3D, Face3DProvider, Capability, Face3DResult, registry,
)


class HaarProvider(Face3DProvider):
    """用 OpenCV 自带的 Haar 级联人脸检测器作为插件。"""

    name = "opencv_haar"
    upstream = "https://github.com/opencv/opencv"
    license = "Apache License 2.0"
    capabilities = frozenset({Capability.DETECT})

    def __init__(self):
        self._cascade = cv2.CascadeClassifier(
            cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
        )

    def process(self, image, capabilities=None):
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
        faces = self._cascade.detectMultiScale(gray, 1.1, 4)
        result = Face3DResult(provider=self.name, capabilities=["detect"])
        if len(faces) == 0:
            result.errors["detect"] = "未检测到人脸"
            return result
        x, y, w, h = max(faces, key=lambda f: f[2] * f[3])
        from universal3dface import BBox
        result.bbox = BBox(float(x), float(y), float(w), float(h))
        return result


def main():
    engine = UniversalFace3D()
    print("插入前插件:", list(engine.providers))

    registry.register_instance(HaarProvider())   # 手动注册
    engine.register(HaarProvider(), name="opencv_haar")  # 插进万能接口
    print("插入后插件:", list(engine.providers))

    from skimage.data import astronaut
    img = cv2.cvtColor(astronaut(), cv2.COLOR_RGB2BGR)

    r = engine.analyze(img, provider_names=["opencv_haar"])
    print("自定义插件检测结果 bbox:", None if r.bbox is None else list(r.bbox.xyxy))

    # 卸载插件
    engine.unregister("opencv_haar")
    print("卸载后插件:", list(engine.providers))
    print("-> 全程未改动引擎代码，验证了万能接口的即插即用。")


if __name__ == "__main__":
    main()
