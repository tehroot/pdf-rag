"""Model handle abstraction and real loader.

We hide the actual ColPali / ColQwen2 / ColSmolVLM model behind a small
``ModelHandle`` Protocol so tests can substitute a fake without dragging in
torch / transformers. ``RealModelHandle`` does the actual heavy lifting via
the ``colpali-engine`` package; importing it is lazy so the bootstrap path
in ``main.py`` works on a torch-less env (e.g. CI without ml extras).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Protocol

from .config import Settings

if TYPE_CHECKING:
    from PIL.Image import Image


# Map model name substring → (model class symbol, processor class symbol).
# Resolved lazily inside ``_resolve_model_classes`` so we don't import
# colpali-engine at module import time.
_MODEL_REGISTRY: list[tuple[tuple[str, ...], tuple[str, str]]] = [
    (("colqwen2.5", "colqwen25"), ("ColQwen2_5", "ColQwen2_5_Processor")),
    (("colqwen",), ("ColQwen2", "ColQwen2Processor")),
    (("colpali",), ("ColPali", "ColPaliProcessor")),
    (("colsmolvlm", "colsmol", "smolvlm"), ("ColIdefics3", "ColIdefics3Processor")),
    (("colflor", "florence"), ("ColPali", "ColPaliProcessor")),  # Flor uses Pali wrapper
]


class ModelHandle(Protocol):
    """A loaded model + processor pair the inference layer can call.

    Implementations:
    - :class:`RealModelHandle` — wraps a colpali-engine model.
    - ``FakeModelHandle`` — defined in tests; returns synthetic vectors.
    """

    @property
    def model_name(self) -> str: ...

    @property
    def vector_dim(self) -> int: ...

    @property
    def device(self) -> str: ...

    def embed_images(self, images: list["Image"]) -> list[list[list[float]]]:
        """Return one (n_tokens, dim) embedding per image, as nested lists."""
        ...

    def embed_query(self, query: str) -> list[list[float]]:
        """Return the query as a (n_tokens, dim) tensor, as nested lists."""
        ...


@dataclass
class _ResolvedClasses:
    model_cls: Any
    processor_cls: Any


def _resolve_model_classes(model_name: str) -> _ResolvedClasses:
    """Pick the colpali-engine model + processor classes for ``model_name``.

    Lazy import — only fires when a real model load happens.
    """
    from colpali_engine import models as colvision_models   # type: ignore

    lname = model_name.lower()
    for keywords, (model_sym, proc_sym) in _MODEL_REGISTRY:
        if any(k in lname for k in keywords):
            model_cls = getattr(colvision_models, model_sym, None)
            proc_cls = getattr(colvision_models, proc_sym, None)
            if model_cls is None or proc_cls is None:
                raise RuntimeError(
                    f"colpali-engine missing expected classes {model_sym}/{proc_sym} "
                    f"for model '{model_name}'. Check the installed colpali-engine version."
                )
            return _ResolvedClasses(model_cls, proc_cls)
    raise RuntimeError(
        f"Could not determine ColVision model class for '{model_name}'. "
        f"Supported keywords: {[kw for kws, _ in _MODEL_REGISTRY for kw in kws]}."
    )


class RealModelHandle:
    """Production ``ModelHandle`` backed by colpali-engine + torch.

    Instantiation triggers the actual model download/load — be ready for
    the first call to take minutes on a fresh checkout. Subsequent calls
    reuse the in-memory model.
    """

    def __init__(self, cfg: Settings):
        import torch  # noqa: F401  (verify torch is importable before model load)

        self._cfg = cfg
        self._device = _select_device(cfg.device)
        self._dtype = _select_dtype(cfg.dtype, self._device)

        resolved = _resolve_model_classes(cfg.model)
        # torch_dtype is the recommended kwarg name as of transformers 4.45+.
        self._model = resolved.model_cls.from_pretrained(
            cfg.model,
            torch_dtype=self._dtype,
            device_map=self._device,
        ).eval()
        self._processor = resolved.processor_cls.from_pretrained(cfg.model)

    @property
    def model_name(self) -> str:
        return self._cfg.model

    @property
    def vector_dim(self) -> int:
        # ColVision models use a 128-dim projection by default; if the loaded
        # model exposes a config we can read, prefer that.
        cfg = getattr(self._model, "config", None)
        for key in ("projection_dim", "embedding_dim", "vector_dim"):
            v = getattr(cfg, key, None) if cfg is not None else None
            if isinstance(v, int) and v > 0:
                return v
        return 128

    @property
    def device(self) -> str:
        return self._device

    def embed_images(self, images: list["Image"]) -> list[list[list[float]]]:
        import torch

        if not images:
            return []
        batch = self._processor.process_images(images).to(self._device)
        with torch.no_grad():
            out = self._model(**batch)
        return _tensor_to_nested_list(out)

    def embed_query(self, query: str) -> list[list[float]]:
        import torch

        batch = self._processor.process_queries([query]).to(self._device)
        with torch.no_grad():
            out = self._model(**batch)
        # out has shape (1, n_tokens, dim); peel off the batch dim.
        return _tensor_to_nested_list(out)[0]


# ---- helpers ----------------------------------------------------------------


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
    """Pick a torch dtype, defaulting to float32 on CPU for stability."""
    import torch

    if device.startswith("cpu") and requested in ("bfloat16", "float16"):
        # bfloat16 / float16 on CPU is supported on recent Intel but slow and
        # often less stable than float32. Use float32 unless explicitly forced.
        return torch.float32
    mapping = {
        "float32": torch.float32,
        "float16": torch.float16,
        "bfloat16": torch.bfloat16,
    }
    if requested not in mapping:
        raise ValueError(f"Unknown dtype '{requested}' (use float32/float16/bfloat16)")
    return mapping[requested]


def _tensor_to_nested_list(tensor: Any) -> list[list[list[float]]]:
    """Convert a torch tensor of shape (batch, n_tokens, dim) to nested lists.

    Detaches, moves to CPU, casts to float32, and converts via .tolist().
    """
    return tensor.detach().to("cpu").to(_float32()).tolist()


def _float32() -> Any:
    """Return torch.float32 (helper kept torch-import-isolated)."""
    import torch

    return torch.float32
