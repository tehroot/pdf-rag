"""The orjson wire path must carry the same numbers as the pydantic path,
in the same JSON shape, and each float must survive a double -> float32
round trip unchanged (that is what the Java client does with it)."""

from __future__ import annotations

import json

import numpy as np

from colpali_server.config import Settings
from colpali_server.inference import embed_pages_bytes, embed_pages_inference
from colpali_server.schemas import EmbedPagesRequest, PageItem

from .fakes import FakeModelHandle, make_b64_png


def _req(include_original: bool = True, include_pooled: bool = True) -> EmbedPagesRequest:
    return EmbedPagesRequest(
        pages=[PageItem(page_id="d:1", image_b64=make_b64_png()),
               PageItem(page_id="d:2", image_b64=make_b64_png(color=(1, 2, 3)))],
        include_original=include_original,
        include_pooled=include_pooled,
    )


def test_bytes_path_matches_pydantic_path() -> None:
    handle = FakeModelHandle(model_name="fake-colvision", vector_dim=8, grid_size=8, n_special_tokens=6)
    cfg = Settings(model="fake-colvision", pool_grid=8, enable_pooled=True)
    req = _req()
    wire = json.loads(embed_pages_bytes(handle, req, cfg))
    model = embed_pages_inference(handle, req, cfg).model_dump()
    assert list(wire.keys()) == ["embeddings"]
    assert len(wire["embeddings"]) == len(model["embeddings"]) == 2
    for w, m in zip(wire["embeddings"], model["embeddings"], strict=True):
        assert set(w.keys()) == {"page_id", "original", "pooled_rows", "pooled_cols"}
        assert w["page_id"] == m["page_id"]
        for key in ("original", "pooled_rows", "pooled_cols"):
            wa = np.asarray(w[key], dtype=np.float32)
            ma = np.asarray(m[key], dtype=np.float32)
            assert wa.shape == ma.shape, key
            # orjson prints the shortest form of the float32 value; parsed as
            # a double and cast back to float32 it is bit-identical.
            np.testing.assert_array_equal(wa, ma, err_msg=key)


def test_bytes_path_honours_include_flags() -> None:
    handle = FakeModelHandle(vector_dim=8, grid_size=8)
    cfg = Settings(model="fake-colvision", pool_grid=8, enable_pooled=True)
    wire = json.loads(embed_pages_bytes(handle, _req(include_original=False), cfg))
    emb = wire["embeddings"][0]
    assert emb["original"] == []
    assert len(emb["pooled_rows"]) > 0 and len(emb["pooled_cols"]) > 0
