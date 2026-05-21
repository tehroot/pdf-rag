"""Top-level model loading entry point.

Kept as a one-function module so tests can monkeypatch it cleanly to inject
a ``FakeModelHandle``.
"""

from __future__ import annotations

from .config import Settings
from .model import ModelHandle, RealModelHandle


def load_model(cfg: Settings) -> ModelHandle:
    """Load and return a real model handle. Slow on cold start.

    Tests substitute via ``main.set_model_for_testing(...)`` rather than
    calling this directly.
    """
    return RealModelHandle(cfg)
