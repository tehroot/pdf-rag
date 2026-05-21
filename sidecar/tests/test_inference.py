"""Integration tests for inference.py + the HTTP layer with a FakeModelHandle.

Exercises the embed_pages / embed_query pipelines end-to-end through the
FastAPI app, with the heavy model substituted by ``FakeModelHandle``. Real
model loading is exercised at deploy time; here we lock the wire contract.
"""

from __future__ import annotations

import base64
import io

import pytest
from fastapi.testclient import TestClient

from colpali_server.main import create_app, set_model_for_testing
from colpali_server.schemas import EmbedPagesRequest, EmbedQueryRequest, PageItem
from colpali_server.inference import embed_pages_inference, embed_query_inference
from colpali_server.config import Settings

from .fakes import FakeModelHandle


# ---- pixel-png helpers ------------------------------------------------------


def _make_b64_png(width: int = 4, height: int = 4) -> str:
    from PIL import Image

    img = Image.new("RGB", (width, height), color=(128, 128, 128))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode()


# ---- inference layer (no HTTP) ----------------------------------------------


def test_embed_pages_inference_packages_pooled_and_original():
    handle = FakeModelHandle(model_name="fake-colqwen2", vector_dim=8, grid_size=8, n_special_tokens=6)
    cfg = Settings(model="fake-colqwen2", pool_grid=8, enable_pooled=True)
    req = EmbedPagesRequest(
        pages=[PageItem(page_id="d-1:1", image_b64=_make_b64_png())],
        include_original=True,
        include_pooled=True,
    )

    resp = embed_pages_inference(handle, req, cfg)

    assert len(resp.embeddings) == 1
    emb = resp.embeddings[0]
    assert emb.page_id == "d-1:1"
    # original is 8*8 patches + 6 specials = 70 tokens
    assert len(emb.original) == 70
    assert all(len(v) == 8 for v in emb.original)
    # pooled is 8 row-averages + 6 specials = 14 tokens
    assert len(emb.pooled_rows) == 14
    assert len(emb.pooled_cols) == 14


def test_embed_pages_inference_respects_include_flags():
    handle = FakeModelHandle(model_name="fake", vector_dim=4, grid_size=4, n_special_tokens=2)
    cfg = Settings(model="fake", pool_grid=4, enable_pooled=True)
    req = EmbedPagesRequest(
        pages=[PageItem(page_id="d:1", image_b64=_make_b64_png())],
        include_original=False,
        include_pooled=True,
    )
    resp = embed_pages_inference(handle, req, cfg)
    emb = resp.embeddings[0]
    assert emb.original == []
    assert len(emb.pooled_rows) > 0


def test_embed_query_inference_returns_multivector():
    handle = FakeModelHandle(vector_dim=8)
    req = EmbedQueryRequest(query="rate limiting strategy")
    resp = embed_query_inference(handle, req)
    assert isinstance(resp.vectors, list)
    assert len(resp.vectors) > 0
    assert all(len(v) == 8 for v in resp.vectors)


def test_inference_rejects_invalid_base64():
    handle = FakeModelHandle()
    cfg = Settings()
    req = EmbedPagesRequest(
        pages=[PageItem(page_id="d:1", image_b64="!!! not base64 !!!")],
        include_original=True,
        include_pooled=True,
    )
    with pytest.raises(ValueError, match="base64"):
        embed_pages_inference(handle, req, cfg)


# ---- HTTP layer integration -------------------------------------------------


@pytest.fixture
def fake_client(monkeypatch: pytest.MonkeyPatch) -> TestClient:
    """A TestClient that uses a FakeModelHandle (no torch needed)."""
    monkeypatch.setenv("COLPALI_MAX_BATCH_SIZE", "8")
    # Inject the fake before lifespan runs; lifespan sees the pre-injected
    # handle and skips load_model().
    from colpali_server import config as cfg_module

    cfg_module.reset_settings_for_testing()
    set_model_for_testing(FakeModelHandle(model_name="fake-colvision",
                                          vector_dim=8, grid_size=8, n_special_tokens=6))
    app = create_app()
    with TestClient(app) as c:
        yield c
    set_model_for_testing(None)


def test_http_embed_pages_returns_three_vector_arrays(fake_client: TestClient) -> None:
    payload = {
        "pages": [{"page_id": "p:1", "image_b64": _make_b64_png()}],
        "include_original": True,
        "include_pooled": True,
    }
    r = fake_client.post("/embed_pages", json=payload)
    assert r.status_code == 200, r.text
    body = r.json()
    assert len(body["embeddings"]) == 1
    emb = body["embeddings"][0]
    assert emb["page_id"] == "p:1"
    assert len(emb["original"]) > 0
    assert len(emb["pooled_rows"]) > 0
    assert len(emb["pooled_cols"]) > 0


def test_http_info_reports_loaded_model(fake_client: TestClient) -> None:
    r = fake_client.get("/info")
    assert r.status_code == 200
    body = r.json()
    assert body["model_name"] == "fake-colvision"
    assert body["vector_dim"] == 8
    assert body["device"] == "cpu"


def test_http_healthz_ready_when_model_injected(fake_client: TestClient) -> None:
    r = fake_client.get("/healthz")
    assert r.status_code == 200
    assert r.json()["ready"] is True


def test_http_embed_query_round_trip(fake_client: TestClient) -> None:
    r = fake_client.post("/embed_query", json={"query": "rate limiting"})
    assert r.status_code == 200
    body = r.json()
    assert len(body["vectors"]) > 0
    assert all(len(v) == 8 for v in body["vectors"])
