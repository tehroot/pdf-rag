"""Inference glue between the HTTP layer and the model handle.

The functions here are deliberately small: decode inputs, call the model,
apply pooling, package responses. Real torch work lives inside the
``ModelHandle`` impl; this module is testable with a fake model and basic
Pillow-only operations.
"""

from __future__ import annotations

import base64
import io
from typing import TYPE_CHECKING

from .config import Settings
from .model import ModelHandle
import numpy as np
import orjson

from .pooling import n_special_tokens_for_model
from .pooling_np import bucket_pool_np, mean_pool_cols_np, mean_pool_rows_np
from .schemas import (
    EmbedPagesRequest,
    EmbedPagesResponse,
    EmbedQueryRequest,
    EmbedQueryResponse,
    PageEmbedding,
)

if TYPE_CHECKING:
    from PIL.Image import Image


def embed_pages_arrays(
    handle: ModelHandle, req: EmbedPagesRequest, cfg: Settings
) -> list[dict]:
    """Decode page images, embed, pool — with numpy arrays end to end.

    Returns one dict per page: ``page_id`` plus ``original`` / ``pooled_rows``
    / ``pooled_cols`` as 2-D float32 arrays (empty ``(0, 0)`` arrays when a
    side is not requested). This is the hot path: a handle that implements
    ``embed_images_array`` hands back one ``(batch, tokens, dim)`` array from
    a single device-to-host copy; pooling runs vectorized. The Python-list
    handle protocol (``embed_images``) still works for fakes and older
    handles — its lists are converted once.
    """
    images = [_decode_image(p.image_b64) for p in req.pages]
    embed_array = getattr(handle, "embed_images_array", None)
    if embed_array is not None:
        raw = embed_array(images)
    else:
        raw = np.asarray(handle.embed_images(images), dtype=np.float32)
    if len(raw) != len(req.pages):
        raise RuntimeError(
            f"Model returned {len(raw)} embeddings for {len(req.pages)} input pages"
        )

    n_special = n_special_tokens_for_model(handle.model_name)
    grid = cfg.pool_grid
    sequence_pooling = getattr(handle, "pooling_mode", "grid") == "sequence"
    empty = np.zeros((0, 0), dtype=np.float32)

    out: list[dict] = []
    for page, embedding in zip(req.pages, raw, strict=True):
        embedding = np.asarray(embedding, dtype=np.float32)
        original = embedding if req.include_original else empty
        if req.include_pooled and cfg.enable_pooled:
            if sequence_pooling:
                pooled_rows = bucket_pool_np(embedding, n_buckets=grid, strided=False)
                pooled_cols = bucket_pool_np(embedding, n_buckets=grid, strided=True)
            else:
                pooled_rows = mean_pool_rows_np(embedding, grid_size=grid, n_special_tokens=n_special)
                pooled_cols = mean_pool_cols_np(embedding, grid_size=grid, n_special_tokens=n_special)
        else:
            pooled_rows = empty
            pooled_cols = empty
        out.append({
            "page_id": page.page_id,
            "original": original,
            "pooled_rows": pooled_rows,
            "pooled_cols": pooled_cols,
        })
    return out


def _encode_array(arr: np.ndarray, encoding: str) -> str:
    """Row-major little-endian bytes, base64; "" for an empty array."""
    if arr.size == 0:
        return ""
    dtype = "<f2" if encoding == "f16b64" else "<f4"
    return base64.b64encode(np.ascontiguousarray(arr, dtype=dtype).tobytes()).decode("ascii")


def embed_pages_bytes(handle: ModelHandle, req: EmbedPagesRequest, cfg: Settings) -> bytes:
    """The /embed_pages response body: same JSON shape as ``EmbedPagesResponse``,
    serialized straight from the numpy arrays with orjson.

    With ``req.encoding`` other than ``json`` the three arrays are base64
    strings of raw little-endian floats and the page carries ``dim`` (see
    ``EmbedPagesRequest``). For 12 pages x 1,280 x 320 that is 27 MB
    (float32) or 13 MB (float16) on the wire instead of 62 MB of JSON
    numbers, and the client decodes it in one pass instead of parsing
    4.9 million numbers into boxed objects — the ingest JVM's heap ceiling
    on the R530 (2026-09-17).

    Why not the pydantic model: for 12 pages x 1,280 tokens x 320 dims,
    ``tolist()`` + model validation + ``dump_json`` cost ~2.3 s per batch on
    the R530 (py-spy, 2026-09-17), on the sidecar's single thread while the
    GPU sat idle. orjson serializes float32 arrays in a few tens of ms and
    prints each float32 in its shortest form, which is the same float32 once
    the Java client casts ``double -> float``, and about half the bytes.
    """
    pages = embed_pages_arrays(handle, req, cfg)
    if req.encoding != "json":
        for p in pages:
            dim = 0
            for key in ("original", "pooled_rows", "pooled_cols"):
                if p[key].size:
                    dim = int(p[key].shape[1])
            for key in ("original", "pooled_rows", "pooled_cols"):
                p[key] = _encode_array(p[key], req.encoding)
            p["dim"] = dim
            p["encoding"] = req.encoding
    return orjson.dumps({"embeddings": pages}, option=orjson.OPT_SERIALIZE_NUMPY)


def embed_pages_inference(
    handle: ModelHandle, req: EmbedPagesRequest, cfg: Settings
) -> EmbedPagesResponse:
    """Pydantic view of ``embed_pages_arrays`` (tests and in-process callers)."""
    pages = embed_pages_arrays(handle, req, cfg)
    return EmbedPagesResponse(embeddings=[
        PageEmbedding(
            page_id=p["page_id"],
            original=p["original"].tolist(),
            pooled_rows=p["pooled_rows"].tolist(),
            pooled_cols=p["pooled_cols"].tolist(),
        )
        for p in pages
    ])


def embed_query_inference(handle: ModelHandle, req: EmbedQueryRequest) -> EmbedQueryResponse:
    """Embed a single query string into its multi-token vector representation."""
    vectors = handle.embed_query(req.query)
    return EmbedQueryResponse(vectors=vectors)


def _decode_image(image_b64: str) -> "Image":
    """Decode a base64-encoded PNG into a PIL Image."""
    from PIL import Image

    try:
        raw = base64.b64decode(image_b64, validate=True)
    except (ValueError, base64.binascii.Error) as e:
        raise ValueError(f"Invalid base64 image data: {e}") from e
    return Image.open(io.BytesIO(raw)).convert("RGB")
