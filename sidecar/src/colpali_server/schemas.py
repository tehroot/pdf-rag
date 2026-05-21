"""Pydantic models for the sidecar's HTTP wire shapes.

The Java side (``ColPaliClient``) consumes these exact shapes — any change here
needs a corresponding update on that side.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


# ---- /info ------------------------------------------------------------------


class InfoResponse(BaseModel):
    model_name: str
    vector_dim: int
    supports_pooled: bool
    pooled_methods: list[str]
    max_batch_size: int
    device: str


# ---- /healthz ---------------------------------------------------------------


class HealthResponse(BaseModel):
    status: str
    ready: bool


# ---- /embed_pages -----------------------------------------------------------


class PageItem(BaseModel):
    page_id: str = Field(
        ...,
        description="Caller-provided stable id (typically '<doc_id>:<page_number>').",
    )
    image_b64: str = Field(..., description="Base64-encoded PNG bytes.")


class EmbedPagesRequest(BaseModel):
    pages: list[PageItem]
    include_original: bool = True
    include_pooled: bool = True


class PageEmbedding(BaseModel):
    page_id: str
    # 2-D float arrays (list-of-lists). Each inner list is a per-token vector;
    # outer list length is the number of patch tokens (or pooled rows/cols).
    original: list[list[float]] = Field(default_factory=list)
    pooled_rows: list[list[float]] = Field(default_factory=list)
    pooled_cols: list[list[float]] = Field(default_factory=list)


class EmbedPagesResponse(BaseModel):
    embeddings: list[PageEmbedding]


# ---- /embed_query -----------------------------------------------------------


class EmbedQueryRequest(BaseModel):
    query: str


class EmbedQueryResponse(BaseModel):
    vectors: list[list[float]]
