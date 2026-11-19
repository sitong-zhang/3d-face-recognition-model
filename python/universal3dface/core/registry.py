"""插件注册表：让第三方实现只需一个装饰器即可接入。"""

from __future__ import annotations

from typing import Dict, Type

from .base import Face3DProvider

_REGISTRY: Dict[str, Type[Face3DProvider] | Face3DProvider] = {}


def register(cls: Type[Face3DProvider]) -> Type[Face3DProvider]:
    """类装饰器：把一个 Provider 子类注册到全局插件表。"""
    name = getattr(cls, "name", None)
    if not name:
        raise ValueError("Provider 必须声明 name")
    _REGISTRY[name] = cls
    return cls


def register_instance(provider: Face3DProvider) -> Face3DProvider:
    """注册一个已经实例化的 provider。"""
    _REGISTRY[provider.name] = provider
    return provider


def get(name: str):
    if name not in _REGISTRY:
        raise KeyError(f"未注册的插件: {name}；已注册: {list(_REGISTRY)}")
    return _REGISTRY[name]


def names() -> list:
    return list(_REGISTRY)


def clear() -> None:
    _REGISTRY.clear()
