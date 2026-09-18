"""Base64 float encodings must decode to the same arrays the json path carries."""

from __future__ import annotations

import base64
import json

import numpy as np

from colpali_server.config import Settings
from colpali_server.inference import embed_pages_bytes
from colpali_server.schemas import EmbedPagesRequest, PageItem

from .fakes import FakeModelHandle, make_b64_png


def _run(encoding: str) -> tuple[dict, dict]:
    handle = FakeModelHandle(vector_dim=8, grid_size=8)
    cfg = Settings(model="fake-colvision", pool_grid=8, enable_pooled=True)
    pages = [PageItem(page_id="d:1", image_b64=make_b64_png())]
    ref = json.loads(embed_pages_bytes(handle, EmbedPagesRequest(pages=pages, encoding="json"), cfg))["embeddings"][0]
    got = json.loads(embed_pages_bytes(handle, EmbedPagesRequest(pages=pages, encoding=encoding), cfg))["embeddings"][0]
    return ref, got


def _decode(b64: str, dim: int, dtype: str) -> np.ndarray:
    raw = base64.b64decode(b64)
    return np.frombuffer(raw, dtype=dtype).reshape(-1, dim).astype(np.float32)


def test_f32b64_is_bit_identical_to_json() -> None:
    ref, got = _run("f32b64")
    assert got["encoding"] == "f32b64" and got["dim"] == 8
    for key in ("original", "pooled_rows", "pooled_cols"):
        np.testing.assert_array_equal(_decode(got[key], 8, "<f4"), np.asarray(ref[key], dtype=np.float32), err_msg=key)


def test_f16b64_matches_within_half_precision() -> None:
    ref, got = _run("f16b64")
    assert got["encoding"] == "f16b64"
    for key in ("original", "pooled_rows", "pooled_cols"):
        r = np.asarray(ref[key], dtype=np.float32)
        np.testing.assert_allclose(_decode(got[key], 8, "<f2"), r, rtol=1e-3, atol=6.2e-5, err_msg=key)


def test_empty_array_is_empty_string() -> None:
    handle = FakeModelHandle(vector_dim=8, grid_size=8)
    cfg = Settings(model="fake-colvision", pool_grid=8, enable_pooled=True)
    req = EmbedPagesRequest(pages=[PageItem(page_id="d:1", image_b64=make_b64_png())],
                            include_original=False, encoding="f32b64")
    emb = json.loads(embed_pages_bytes(handle, req, cfg))["embeddings"][0]
    assert emb["original"] == "" and len(emb["pooled_rows"]) > 0


def test_info_advertises_encodings(client) -> None:
    body = client.get("/info").json()
    assert body["encodings"] == ["json", "f32b64", "f16b64"]


def test_http_embed_pages_f32b64_round_trip(client) -> None:
    payload = {"pages": [{"page_id": "p:1", "image_b64": make_b64_png()}],
               "include_original": True, "include_pooled": True, "encoding": "f32b64"}
    r = client.post("/embed_pages", json=payload)
    assert r.status_code == 200, r.text
    emb = r.json()["embeddings"][0]
    arr = _decode(emb["original"], emb["dim"], "<f4")
    assert arr.shape[1] == emb["dim"] and arr.shape[0] > 0
