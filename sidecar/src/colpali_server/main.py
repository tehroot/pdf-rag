"""FastAPI entry point for the ColPali sidecar.

The lifespan loads the configured model (via :func:`colpali_server.loader.load_model`)
once at startup. Tests pre-inject a fake ``ModelHandle`` via
:func:`set_model_for_testing` and the lifespan skips the heavy load.
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from fastapi import FastAPI, HTTPException

from .config import Settings, settings
from .model import ModelHandle
from .schemas import (
    EmbedPagesRequest,
    EmbedPagesResponse,
    EmbedQueryRequest,
    EmbedQueryResponse,
    HealthResponse,
    InfoResponse,
)

log = logging.getLogger("colpali_server")


# Module-level state. The lifespan populates ``handle`` from the loader (or
# tests pre-populate it via set_model_for_testing). ``ready`` flips to True
# once a handle is present.
_state: dict[str, Any] = {"handle": None, "ready": False}


def set_model_for_testing(handle: ModelHandle | None) -> None:
    """Test-only helper: inject (or clear) the model handle.

    When set before the FastAPI lifespan starts, the lifespan skips the
    heavy ``load_model`` call and uses the injected handle directly.
    """
    _state["handle"] = handle
    _state["ready"] = handle is not None


def get_model_handle() -> ModelHandle:
    """Return the loaded model handle. Raises 503 if not ready."""
    handle = _state.get("handle")
    if handle is None:
        raise HTTPException(status_code=503, detail="Model not ready")
    return handle


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Application lifespan hook — load the model on startup."""
    cfg = settings()
    if _state["handle"] is None:
        log.info(
            "colpali-server loading model=%s device=%s dtype=%s",
            cfg.model, cfg.device, cfg.dtype,
        )
        from .loader import load_model  # lazy import to avoid torch at module load

        _state["handle"] = load_model(cfg)
        log.info("model loaded; ready")
    else:
        log.info("colpali-server using pre-injected model handle (test mode)")
    _state["ready"] = True
    yield
    log.info("colpali-server shutting down")


def create_app(cfg: Settings | None = None) -> FastAPI:
    """Factory used by uvicorn and the pytest test client."""
    if cfg is None:
        cfg = settings()

    app = FastAPI(
        title="colpali-server",
        version="0.1.0",
        lifespan=lifespan,
    )

    @app.get("/healthz", response_model=HealthResponse)
    async def healthz() -> HealthResponse:
        return HealthResponse(
            status="ok" if _state["ready"] else "loading",
            ready=bool(_state["ready"]),
        )

    @app.get("/info", response_model=InfoResponse)
    async def info() -> InfoResponse:
        handle = _state.get("handle")
        if handle is not None:
            return InfoResponse(
                model_name=handle.model_name,
                vector_dim=handle.vector_dim,
                supports_pooled=cfg.enable_pooled,
                pooled_methods=["rows", "cols"] if cfg.enable_pooled else [],
                max_batch_size=cfg.max_batch_size,
                device=handle.device,
            )
        # Pre-load fallback: report configured values; vector_dim is a guess.
        return InfoResponse(
            model_name=cfg.model,
            vector_dim=128,
            supports_pooled=cfg.enable_pooled,
            pooled_methods=["rows", "cols"] if cfg.enable_pooled else [],
            max_batch_size=cfg.max_batch_size,
            device="not-yet-loaded",
        )

    @app.post("/embed_pages", response_model=EmbedPagesResponse)
    async def embed_pages(req: EmbedPagesRequest) -> EmbedPagesResponse:
        handle = get_model_handle()
        if len(req.pages) > cfg.max_batch_size:
            raise HTTPException(
                status_code=400,
                detail=(
                    f"Batch size {len(req.pages)} exceeds max_batch_size={cfg.max_batch_size}"
                ),
            )
        # Local import keeps the inference module's torch-touching code out of
        # the module-load path for environments without ML deps.
        from .inference import embed_pages_inference

        return embed_pages_inference(handle, req, cfg)

    @app.post("/embed_query", response_model=EmbedQueryResponse)
    async def embed_query(req: EmbedQueryRequest) -> EmbedQueryResponse:
        handle = get_model_handle()
        if not req.query or not req.query.strip():
            raise HTTPException(status_code=400, detail="query must be non-empty")

        from .inference import embed_query_inference

        return embed_query_inference(handle, req)

    return app


# Module-level app instance for `uvicorn colpali_server.main:app`.
app = create_app()


def run() -> None:
    """Console-script entry point: ``colpali-server``."""
    import uvicorn

    cfg = settings()
    logging.basicConfig(level=logging.INFO)
    uvicorn.run(
        "colpali_server.main:app",
        host=cfg.host,
        port=cfg.port,
        log_level="info",
    )


if __name__ == "__main__":
    run()
