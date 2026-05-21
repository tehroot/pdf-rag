"""Test doubles for the sidecar.

Lets the inference and API tests run without torch / transformers / a real
model. The ``FakeModelHandle`` mirrors the production ``ModelHandle`` Protocol
and returns deterministic synthetic vectors shaped like ColPali output.
"""

from __future__ import annotations

import base64
import io
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from PIL.Image import Image


def make_b64_png(width: int = 4, height: int = 4, color: tuple[int, int, int] = (128, 128, 128)) -> str:
    """Build a tiny valid PNG and return it base64-encoded.

    Used by tests that need a wire-shape-realistic image payload without
    pulling in a binary fixture.
    """
    from PIL import Image

    img = Image.new("RGB", (width, height), color=color)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode()


class FakeModelHandle:
    """ModelHandle stand-in that returns predictable synthetic embeddings."""

    def __init__(
        self,
        model_name: str = "fake-colvision",
        vector_dim: int = 128,
        grid_size: int = 32,
        n_special_tokens: int = 6,
        device: str = "cpu",
    ) -> None:
        self._model_name = model_name
        self._vector_dim = vector_dim
        self._grid_size = grid_size
        self._n_special = n_special_tokens
        self._device = device

    @property
    def model_name(self) -> str:
        return self._model_name

    @property
    def vector_dim(self) -> int:
        return self._vector_dim

    @property
    def device(self) -> str:
        return self._device

    def embed_images(self, images: list["Image"]) -> list[list[list[float]]]:
        n_tokens = self._grid_size * self._grid_size + self._n_special
        # Each image gets the same synthetic embedding for predictability.
        # Pattern: row index normalized to [0,1] in each vector slot — distinguishable
        # across rows but constant within a row.
        per_image: list[list[float]] = [
            [t / n_tokens] * self._vector_dim for t in range(n_tokens)
        ]
        return [per_image for _ in images]

    def embed_query(self, query: str) -> list[list[float]]:
        # Length depends on token count (loose proxy: 1 token per ~4 chars).
        n_tokens = max(1, min(30, len(query) // 4))
        return [[float(t) / n_tokens] * self._vector_dim for t in range(n_tokens)]
