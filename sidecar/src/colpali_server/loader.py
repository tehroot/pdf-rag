"""Top-level model loading entry point.

Kept small so tests can monkeypatch it cleanly (inject a ``FakeModelHandle``)
and unit-test the routing without touching torch.
"""

from __future__ import annotations

from .config import Settings
from .model import ModelHandle, RealModelHandle
from .tomoro import TomoroColQwen3Handle


def handle_class_for(model_name: str) -> type:
    """Routing decision, exposed for tests.

    Tomoro's colqwen3 models must be checked BEFORE the colpali-engine
    substring registry: "tomoro-colqwen3-embed-4b" contains "colqwen" and
    would otherwise be forced through ColQwen2's Qwen2-VL modeling code,
    which crashes on Qwen3-VL weights (and would emit garbage if it didn't).
    """
    if "tomoro" in model_name.lower():
        return TomoroColQwen3Handle
    return RealModelHandle


def load_model(cfg: Settings) -> ModelHandle:
    """Load and return a real model handle. Slow on cold start.

    Tests substitute via ``main.set_model_for_testing(...)`` rather than
    calling this directly.
    """
    return handle_class_for(cfg.model)(cfg)
