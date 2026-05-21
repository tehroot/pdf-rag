"""HTTP surface tests for the sidecar.

Run against a pre-injected ``FakeModelHandle`` (see conftest). Verifies the
wire contract the Java side depends on.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from .fakes import make_b64_png


def test_healthz_reports_ok(client: TestClient) -> None:
    r = client.get("/healthz")
    assert r.status_code == 200
    body = r.json()
    assert body["ready"] is True
    assert body["status"] == "ok"


def test_info_shape_matches_java_expectation(client: TestClient) -> None:
    r = client.get("/info")
    assert r.status_code == 200
    body = r.json()
    assert {"model_name", "vector_dim", "supports_pooled", "pooled_methods",
            "max_batch_size", "device"}.issubset(body.keys())
    assert isinstance(body["pooled_methods"], list)
    # With the FakeModelHandle injected, /info reports the fake's identity.
    assert body["model_name"] == "fake-colvision"
    assert body["vector_dim"] == 128
    assert body["supports_pooled"] is True
    assert "rows" in body["pooled_methods"]
    assert "cols" in body["pooled_methods"]


def test_info_reports_configured_batch_size(monkeypatch, client: TestClient) -> None:
    """Settings-driven fields flow through /info; model identity comes from
    the loaded handle."""
    from colpali_server import config as cfg_module
    from colpali_server.main import create_app

    monkeypatch.setenv("COLPALI_MAX_BATCH_SIZE", "16")
    cfg_module.reset_settings_for_testing()
    app = create_app()
    with TestClient(app) as c:
        body = c.get("/info").json()
        assert body["max_batch_size"] == 16


def test_embed_pages_returns_one_per_input(client: TestClient) -> None:
    payload = {
        "pages": [
            {"page_id": "doc-1:1", "image_b64": make_b64_png()},
            {"page_id": "doc-1:2", "image_b64": make_b64_png()},
        ],
        "include_original": True,
        "include_pooled": True,
    }
    r = client.post("/embed_pages", json=payload)
    assert r.status_code == 200, r.text
    body = r.json()
    assert len(body["embeddings"]) == 2
    for emb in body["embeddings"]:
        assert isinstance(emb["original"], list)
        assert isinstance(emb["pooled_rows"], list)
        assert isinstance(emb["pooled_cols"], list)
        assert len(emb["original"]) > 0
        assert len(emb["pooled_rows"]) > 0
        assert len(emb["pooled_cols"]) > 0


def test_embed_pages_respects_batch_limit(monkeypatch, client: TestClient) -> None:
    from colpali_server import config as cfg_module
    from colpali_server.main import create_app

    monkeypatch.setenv("COLPALI_MAX_BATCH_SIZE", "2")
    cfg_module.reset_settings_for_testing()
    app = create_app()
    with TestClient(app) as c:
        payload = {
            "pages": [{"page_id": f"p:{i}", "image_b64": make_b64_png()} for i in range(5)],
            "include_original": True,
            "include_pooled": True,
        }
        r = c.post("/embed_pages", json=payload)
        assert r.status_code == 400
        assert "max_batch_size" in r.json()["detail"]


def test_embed_pages_include_flags_control_output(client: TestClient) -> None:
    payload = {
        "pages": [{"page_id": "p:1", "image_b64": make_b64_png()}],
        "include_original": False,
        "include_pooled": True,
    }
    r = client.post("/embed_pages", json=payload)
    assert r.status_code == 200
    emb = r.json()["embeddings"][0]
    assert emb["original"] == []
    assert len(emb["pooled_rows"]) > 0


def test_embed_query_returns_vectors(client: TestClient) -> None:
    r = client.post("/embed_query", json={"query": "rate limiting strategy"})
    assert r.status_code == 200
    body = r.json()
    assert isinstance(body["vectors"], list)
    assert len(body["vectors"]) > 0
    assert all(isinstance(v, list) for v in body["vectors"])


def test_embed_query_rejects_blank(client: TestClient) -> None:
    r = client.post("/embed_query", json={"query": "   "})
    assert r.status_code == 400


def test_embed_query_rejects_missing_field(client: TestClient) -> None:
    r = client.post("/embed_query", json={})
    assert r.status_code == 422
