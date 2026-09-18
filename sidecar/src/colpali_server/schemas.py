"""Pydantic models for the sidecar's HTTP wire shapes.

The Java side (``ColPaliClient``) consumes these exact shapes — any change here
needs a corresponding update on that side.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

#: Wire encodings for the three vector arrays of a page (see EmbedPagesRequest).
Encoding = Literal["json", "f32b64", "f16b64"]
ENCODINGS: list[str] = ["json", "f32b64", "f16b64"]


# ---- /info ------------------------------------------------------------------


class InfoResponse(BaseModel):
    model_name: str
    vector_dim: int
    supports_pooled: bool
    pooled_methods: list[str]
    max_batch_size: int
    device: str
    # Encodings this server accepts in EmbedPagesRequest.encoding. Older
    # servers omit the field; older clients ignore it.
    encodings: list[str] = Field(default_factory=lambda: list(ENCODINGS))


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
    # How the response carries each vector array:
    #   json   — list-of-lists of floats (PageEmbedding below). ~5 MB/page.
    #   f32b64 — base64 of the row-major little-endian float32 bytes, plus a
    #            per-page "dim"; rows = len(bytes) / (4*dim). ~2.2 MB/page,
    #            parsed in one pass, bit-identical to the json values.
    #   f16b64 — same with float16 (~1.1 MB/page). Exact for the model's
    #            bf16 outputs down to 6.1e-5 in magnitude; below that the
    #            float16 subnormal range loses bits.
    # An empty array is an empty string. Clients that predate this field
    # send nothing and get json.
    encoding: Encoding = "json"


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
