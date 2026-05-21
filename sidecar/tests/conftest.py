"""Pytest fixtures shared across the sidecar test suite.

Every test runs against a ``FakeModelHandle`` by default — the lifespan
detects the pre-injected handle and skips ``load_model``. Tests that want
the real loader can override by clearing the handle before TestClient
construction.
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from colpali_server import config as cfg_module
from colpali_server.main import create_app, set_model_for_testing

from .fakes import FakeModelHandle


@pytest.fixture(autouse=True)
def _reset_settings_and_inject_fake_model() -> None:
    """Reset the Settings singleton and pre-inject a FakeModelHandle.

    Runs around every test so monkeypatched env vars take effect and so the
    lifespan never tries to load a real model.
    """
    cfg_module.reset_settings_for_testing()
    set_model_for_testing(
        FakeModelHandle(
            model_name="fake-colvision",
            vector_dim=128,
            grid_size=32,
            n_special_tokens=6,
        )
    )
    yield
    set_model_for_testing(None)


@pytest.fixture
def client(monkeypatch: pytest.MonkeyPatch) -> TestClient:
    """A FastAPI TestClient with the autouse fake-model injection."""
    for key in [
        "COLPALI_MODEL",
        "COLPALI_DEVICE",
        "COLPALI_MAX_BATCH_SIZE",
        "COLPALI_ENABLE_POOLED",
        "COLPALI_ENABLE_ORIGINAL",
    ]:
        monkeypatch.delenv(key, raising=False)
    cfg_module.reset_settings_for_testing()
    app = create_app()
    with TestClient(app) as c:
        yield c
