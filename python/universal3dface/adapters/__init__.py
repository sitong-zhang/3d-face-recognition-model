"""
插件集合：导入即触发各适配器的 @register 注册。

要接入新的开源实现，只需在此目录新增一个文件：
  1. 继承 Face3DProvider；
  2. 声明 name/upstream/license/capabilities；
  3. 实现 process()（可选 verify()）；
  4. 加 @register 装饰器。
万能引擎 UniversalFace3D 会自动发现它，无需修改引擎代码。
"""

from . import dlib_adapter  # noqa: F401
from . import mediapipe_adapter  # noqa: F401
from . import reconstruction_adapter  # noqa: F401

__all__ = ["dlib_adapter", "mediapipe_adapter", "reconstruction_adapter"]
