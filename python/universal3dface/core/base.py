"""插件抽象基类。"""

from __future__ import annotations

from typing import Dict, Optional, Set

import numpy as np

from .capabilities import Capability
from .types import Face3DResult, VerifyResult


class Face3DProvider:
    """所有插件的基类（也可直接作为接口协议使用）。

    子类需要：
      1. 声明 name / upstream / license / capabilities 元信息；
      2. 实现 process()；若支持 VERIFY，再实现 verify()。
    """

    name: str = "base"
    upstream: str = ""
    license: str = ""
    version: str = ""
    capabilities: frozenset = frozenset()

    def supports(self, cap: Capability) -> bool:
        return cap in self.capabilities

    def available(self) -> bool:
        """依赖是否满足。子类可覆盖以做更细判断。"""
        return True

    def process(self, image: np.ndarray, capabilities: Optional[Set[Capability]] = None) -> Face3DResult:
        raise NotImplementedError(f"{self.name} 未实现 process()")

    def verify(self, img_a: np.ndarray, img_b: np.ndarray) -> VerifyResult:
        raise NotImplementedError(f"{self.name} 未实现 verify()")

    def info(self) -> Dict:
        return {
            "name": self.name,
            "upstream": self.upstream,
            "license": self.license,
            "version": self.version,
            "capabilities": sorted(c.value for c in self.capabilities),
            "available": self.available(),
        }
