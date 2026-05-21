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
from .pooling import mean_pool_cols, mean_pool_rows, n_special_tokens_for_model
from .schemas import (
    EmbedPagesRequest,
    EmbedPagesResponse,
    EmbedQueryRequest,
    EmbedQueryResponse,
    PageEmbedding,
)

if TYPE_CHECKING:
    from PIL.Image import Image


def embed_pages_inference(
    handle: ModelHandle, req: EmbedPagesRequest, cfg: Settings
) -> EmbedPagesResponse:
    """Decode page images, embed via the model, apply pooling."""
    images = [_decode_image(p.image_b64) for p in req.pages]
    raw = handle.embed_images(images)
    if len(raw) != len(req.pages):
        raise RuntimeError(
            f"Model returned {len(raw)} embeddings for {len(req.pages)} input pages"
        )

    n_special = n_special_tokens_for_model(handle.model_name)
    grid = cfg.pool_grid

    out: list[PageEmbedding] = []
    for page, embedding in zip(req.pages, raw, strict=True):
        original = embedding if req.include_original else []
        if req.include_pooled and cfg.enable_pooled:
            pooled_rows = mean_pool_rows(embedding, grid_size=grid, n_special_tokens=n_special)
            pooled_cols = mean_pool_cols(embedding, grid_size=grid, n_special_tokens=n_special)
        else:
            pooled_rows = []
            pooled_cols = []
        out.append(
            PageEmbedding(
                page_id=page.page_id,
                original=original,
                pooled_rows=pooled_rows,
                pooled_cols=pooled_cols,
            )
        )
    return EmbedPagesResponse(embeddings=out)


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
