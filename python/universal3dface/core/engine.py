"""万能接口门面：UniversalFace3D。

上层只跟这个类打交道，完全不需要知道底层接了哪些开源实现。

设计目标（对应需求：一个万能接口，后面什么都能插）：
  - 任何开源实现只要实现 Face3DProvider 并用 @register 注册，即可被自动发现；
  - 一次 analyze() 调用聚合所有插件的结果，按注册优先级合并；
  - 单个插件失败不影响整体，保证接口鲁棒。
"""

from __future__ import annotations

from typing import Dict, List, Optional, Set

import numpy as np

from .base import Face3DProvider
from .capabilities import Capability
from .types import Face3DResult, VerifyResult


class UniversalFace3D:
    """可插拔的通用 3D 人脸识别引擎（万能接口）。"""

    def __init__(self, auto_discover: bool = True):
        self._providers: Dict[str, Face3DProvider] = {}
        if auto_discover:
            self.discover()

    # ---------- 插件管理 ----------
    def discover(self) -> None:
        """导入内置 adapters 包，触发注册装饰器，并把注册表里的插件实例化装载进来。"""
        try:
            import universal3dface.adapters  # noqa: F401
            from .registry import _REGISTRY
            for name, provider_cls in _REGISTRY.items():
                if name not in self._providers:
                    try:
                        self._providers[name] = provider_cls()
                    except Exception as exc:  # 某个插件构造失败不影响其他插件
                        print(f"[警告] 插件 {name} 实例化失败: {exc}")
        except Exception as exc:  # pragma: no cover
            print(f"[警告] 自动发现插件失败: {exc}")

    def register(self, provider: Face3DProvider, name: Optional[str] = None) -> "UniversalFace3D":
        key = name or provider.name
        self._providers[key] = provider
        return self

    def unregister(self, name: str) -> "UniversalFace3D":
        provider = self._providers.pop(name, None)
        if provider is not None and hasattr(provider, "close"):
            try:
                provider.close()
            except Exception:
                pass
        return self

    def close(self) -> None:
        """显式释放所有插件占用的底层资源（如 MediaPipe 推理器）。"""
        for provider in self._providers.values():
            if hasattr(provider, "close"):
                try:
                    provider.close()
                except Exception:
                    pass

    @property
    def providers(self) -> Dict[str, Face3DProvider]:
        return dict(self._providers)

    def list_providers(self) -> List[Dict]:
        return [p.info() for p in self._providers.values()]

    def list_capabilities(self) -> Dict[str, List[str]]:
        return {name: sorted(c.value for c in p.capabilities) for name, p in self._providers.items()}

    # ---------- 核心调用 ----------
    def analyze(
        self,
        image: np.ndarray,
        capabilities: Optional[Set[Capability]] = None,
        provider_names: Optional[List[str]] = None,
    ) -> Face3DResult:
        """用所有（或指定）插件分析一张图，结果按优先级合并。

        capabilities: 若为 None 则调用插件的全部能力；否则只请求交集能力。
        """
        targets = provider_names or list(self._providers)
        if not targets:
            return Face3DResult(provider="none", errors={"system": "没有任何可用插件"})

        merged: Optional[Face3DResult] = None
        for name in targets:
            provider = self._providers.get(name)
            if provider is None:
                continue
            try:
                if not provider.available():
                    continue
                result = provider.process(image, capabilities)
                if merged is None:
                    merged = result
                else:
                    merged.merge(result)
            except Exception as exc:
                # 单个插件失败不影响整体，保证万能接口鲁棒
                err = Face3DResult(provider=name, errors={name: repr(exc)})
                if merged is None:
                    merged = err
                else:
                    merged.errors.update(err.errors)

        if merged is None:
            merged = Face3DResult(provider="none", errors={"system": "所有插件均不可用"})
        return merged

    def verify(
        self,
        img_a: np.ndarray,
        img_b: np.ndarray,
        provider_name: Optional[str] = None,
    ) -> VerifyResult:
        """比对两张人脸。优先使用支持 VERIFY 的插件。"""
        candidates = [provider_name] if provider_name else [
            n for n, p in self._providers.items() if p.supports(Capability.VERIFY)
        ]
        errors: Dict[str, str] = {}
        for name in candidates:
            provider = self._providers.get(name)
            if provider is None or not provider.available():
                continue
            try:
                return provider.verify(img_a, img_b)
            except Exception as exc:
                errors[name] = repr(exc)
        return VerifyResult(
            provider="none", metric="none",
            errors=errors or {"system": "没有可用的比对插件"},
        )
