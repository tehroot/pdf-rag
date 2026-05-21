# colpali-server

The Python HTTP sidecar for [pdf-rag-ingest](..). Runs a ColVision model
(ColPali / ColQwen2 / ColSmolVLM) behind a small FastAPI service exposing the
contract the Java `ColPaliClient` expects:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/healthz` | GET | Liveness; reports whether the model finished loading. |
| `/info` | GET | Self-report — model name, vector dim, supported pooling, batch size, device. |
| `/embed_pages` | POST | Embed one or more page images (base64 PNG) into ColPali-style multivectors. Returns `original`, `pooled_rows`, `pooled_cols` per page. |
| `/embed_query` | POST | Embed a query string into a multi-token vector representation. |

The Java side reads `/info` at runtime and adapts to whatever the sidecar reports —
no rebuild required when swapping models.

## Running locally

```bash
# Dev env
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

# Run with defaults (vidore/colqwen2-v1.0 on CPU; very slow but works for smoke tests)
colpali-server

# Pick a smaller model for CPU dev
COLPALI_MODEL=vidore/colsmolvlm-v0.1 colpali-server

# Bind / port
COLPALI_HOST=0.0.0.0 COLPALI_PORT=8090 colpali-server
```

## Configuration

All via environment variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `COLPALI_MODEL` | `vidore/colqwen2-v1.0` | HuggingFace model id. |
| `COLPALI_DEVICE` | `auto` | `cuda` / `cpu` / `mps` / `auto`. |
| `COLPALI_DTYPE` | `bfloat16` | Model dtype. Use `float32` on CPU; `bfloat16` on Ampere+ GPUs. |
| `COLPALI_MAX_BATCH_SIZE` | `8` | Max pages per `/embed_pages` request. |
| `COLPALI_POOL_GRID` | `32` | The patch-grid side used for row/col pooling. |
| `COLPALI_HOST` | `0.0.0.0` | Bind host. |
| `COLPALI_PORT` | `8090` | Bind port. |

## Deployment

CPU image (slim):

```bash
docker build -f Dockerfile.cpu -t colpali-server:cpu .
docker run -p 8090:8090 -e COLPALI_MODEL=vidore/colsmolvlm-v0.1 colpali-server:cpu
```

GPU image (CUDA 12.x):

```bash
docker build -f Dockerfile.cuda -t colpali-server:cuda .
docker run --gpus all -p 8090:8090 colpali-server:cuda
```

For an end-to-end stack (Qdrant + llama-server + colpali-server + pdf-rag-ingest),
see the project root's `docs/deployment.md` and the bundled `docker-compose.yml`.

## Hardware / model picker

| Hardware | Recommended model | Notes |
|----------|--------------------|-------|
| Dual Xeon CPU (no GPU) | `vidore/colsmolvlm-v0.1` (500M) or `vidore/colflor` (770M) | Async ingest mandatory; ~1-3 pages/sec. |
| NVIDIA A2 (16 GB) | `vidore/colqwen2-v1.0` (2B) | Production sweet spot; ~5-15 pages/sec at FP16. |
| NVIDIA A10 / L4 (24 GB) | `vidore/colqwen2.5-v0.1` (7B) | Higher quality, especially multilingual. |
| A100 / H100 | Same; just much faster. | 30-80+ pages/sec. |

The wire shape (`/info`) is identical regardless — the Java side adapts.
