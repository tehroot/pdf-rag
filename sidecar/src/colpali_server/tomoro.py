"""Model handle for TomoroAI ColQwen3 embedding models.

Tomoro's colqwen3 checkpoints (e.g. ``TomoroAI/tomoro-colqwen3-embed-4b``) are
ColBERT-style late-interaction retrievers on a Qwen3-VL backbone with a custom
320-dim projection head. They are NOT loadable through colpali-engine — they
ship their own remote code (``modeling_colqwen3.py``) and must go through
``AutoModel``/``AutoProcessor`` with ``trust_remote_code=True``. Forcing them
through ``ColQwen2`` (what the substring registry used to do) executes
Qwen2-VL modeling against Qwen3-VL weights and dies inside the placeholder
mask ("Image features and image tokens do not match").

API differences from colpali-engine handled here:
- the processor takes ``max_num_visual_tokens`` (dynamic-resolution cap;
  without it a dense page can exceed 12k visual tokens and OOM the batch);
- the forward returns an object with an ``.embeddings`` attribute rather
  than a raw tensor;
- text goes through ``process_texts`` (colpali-engine: ``process_queries``).

Pooling: Qwen3-VL pages are not a square 32x32 grid, so this handle reports
``pooling_mode = "sequence"`` and the inference layer bucket-pools the token
sequence instead of grid-pooling (see ``pooling.bucket_pool``).
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from .config import Settings

if TYPE_CHECKING:
    from PIL.Image import Image


class TomoroColQwen3Handle:
    """``ModelHandle`` implementation for tomoro-colqwen3-embed-* models."""

    #: Tells the inference layer to bucket-pool the token sequence rather
    #: than assume a square patch grid.
    pooling_mode = "sequence"

    def __init__(self, cfg: Settings):
        import torch

        self._cfg = cfg
        self._device = _select_device(cfg.device)
        self._dtype = _select_dtype(cfg.dtype, self._device)

        from transformers import AutoModel, AutoProcessor  # lazy: needs ml extras

        self._processor = AutoProcessor.from_pretrained(
            cfg.model,
            trust_remote_code=True,
            max_num_visual_tokens=cfg.max_visual_tokens,
        )
        self._model = AutoModel.from_pretrained(
            cfg.model,
            dtype=self._dtype,
            attn_implementation=cfg.attn_impl,
            trust_remote_code=True,
            device_map=self._device,
        ).eval()
        self._vector_dim = _detect_dim(self._model)

    @property
    def model_name(self) -> str:
        return self._cfg.model

    @property
    def vector_dim(self) -> int:
        return self._vector_dim

    @property
    def device(self) -> str:
        return self._device

    def embed_images(self, images: list["Image"]) -> list[list[list[float]]]:
        import torch

        if not images:
            return []
        batch = self._processor.process_images(images=images).to(self._device)
        with torch.no_grad():
            out = self._model(**batch)
        return _embeddings_of(out).detach().to("cpu").float().tolist()

    def embed_query(self, query: str) -> list[list[float]]:
        import torch

        batch = self._processor.process_texts(texts=[query]).to(self._device)
        with torch.no_grad():
            out = self._model(**batch)
        return _embeddings_of(out).detach().to("cpu").float().tolist()[0]


def _embeddings_of(out: Any) -> Any:
    """The tomoro forward returns an object carrying ``.embeddings``; fall
    back to the raw value if a future revision returns the tensor directly."""
    return getattr(out, "embeddings", out)


def _detect_dim(model: Any) -> int:
    cfg = getattr(model, "config", None)
    for key in ("projection_dim", "embedding_dim", "dim", "vector_dim"):
        v = getattr(cfg, key, None) if cfg is not None else None
        if isinstance(v, int) and v > 0:
            return v
    # tomoro-colqwen3's documented projection head; used when the remote-code
    # config doesn't expose the dim under a known key.
    return 320


# Device/dtype selection mirrors model.py; re-declared here (not imported)
# so each handle module stays independently lazy-importable.
def _select_device(requested: str) -> str:
    if requested != "auto":
        return requested
    try:
        import torch

        if torch.cuda.is_available():
            return "cuda:0"
        if getattr(torch.backends, "mps", None) and torch.backends.mps.is_available():
            return "mps"
    except ImportError:
        pass
    return "cpu"


def _select_dtype(requested: str, device: str) -> Any:
    import torch

    if device.startswith("cpu") and requested in ("bfloat16", "float16"):
        return torch.float32
    mapping = {
        "float32": torch.float32,
        "float16": torch.float16,
        "bfloat16": torch.bfloat16,
    }
    if requested not in mapping:
        raise ValueError(f"Unknown dtype '{requested}' (use float32/float16/bfloat16)")
    return mapping[requested]
