# colpali-server

The Python HTTP sidecar for [pdf-rag-ingest](..). Runs a ColVision model
(ColPali / ColQwen2 / ColSmolVLM / tomoro-colqwen3) behind a small FastAPI
service exposing the contract the Java `ColPaliClient` expects:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/healthz` | GET | Liveness; reports whether the model finished loading. |
| `/info` | GET | Self-report — model name, vector dim, supported pooling, batch size, device, and the `encodings` this server accepts. |
| `/embed_pages` | POST | Embed one or more page images (base64 PNG) into ColPali-style multivectors. Returns `original`, `pooled_rows`, `pooled_cols` per page, in the requested `encoding` (see below). |
| `/embed_query` | POST | Embed a query string into a multi-token vector representation. |

The Java side reads `/info` at runtime and adapts to whatever the sidecar reports —
no rebuild required when swapping models.

## Wire contract for `/embed_pages`

Request fields (`schemas.EmbedPagesRequest`):

| Field | Default | Meaning |
|-------|---------|---------|
| `pages` | — | List of `{page_id, image_b64}`; at most `COLPALI_MAX_BATCH_SIZE` entries or the request is rejected with 400. |
| `include_original` | `true` | Return the full per-token multivector. |
| `include_pooled` | `true` | Return `pooled_rows` / `pooled_cols`. |
| `encoding` | `json` | `json`, `f32b64` or `f16b64`. Clients that omit the field get `json`. |

Encodings:

- `json` — each of the three arrays is a list of lists of floats. This is
  the original shape (`schemas.PageEmbedding`).
- `f32b64` — each array is one base64 string of the row-major
  little-endian float32 bytes. The page also carries `dim` (the vector
  width) and echoes `encoding`. Rows = `len(bytes) / (4 * dim)`. Values are
  bit-identical to `json`.
- `f16b64` — the same with float16 (`len(bytes) / (2 * dim)` rows). Exact
  for the model's bf16 outputs down to 6.1e-5 in magnitude; below that the
  float16 subnormal range loses bits.

For the two base64 encodings an empty array (a side that was not requested)
is the empty string `""`. `/info` lists the accepted values in `encodings`
(`["json", "f32b64", "f16b64"]`); older servers omit that field. The Java
client defaults to `f32b64` (`ingest.colpali.wire-encoding`) and decodes
either form per field, so sidecar and ingest service roll independently.

## How the embed path is built

- Both model handles (`model.RealModelHandle`, `tomoro.TomoroColQwen3Handle`)
  expose `embed_images_array`: one device-to-host copy that returns a
  `(batch, tokens, dim)` float32 numpy array. The list-returning
  `embed_images` remains for fakes and older handles.
- Pooling runs vectorized in `pooling_np.py`. `pooling.py` (pure Python) is
  the reference; `tests/test_pooling_np.py` holds the numpy twins to it
  within float32 rounding.
- `inference.embed_pages_bytes` builds the response as orjson bytes straight
  from the arrays; the `/embed_pages` handler returns those bytes. The
  pydantic `EmbedPagesResponse` is documentation for that endpoint.
  `inference.embed_pages_inference` is the pydantic view of the same result,
  for tests and in-process callers.
- `numpy` and `orjson` are core dependencies (not part of the `[ml]` extra).

## Concurrency

The `/embed_pages` handler runs synchronously on the event loop. The process
serves one batch at a time, and no other endpoint answers during a batch —
`/healthz` included. Consequences: a health probe must allow more time than
one batch (`docker-compose.yml` sets the healthcheck `timeout` to 40 s), and
throughput scales by adding replicas, not by raising concurrency in one
process.

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

Tests (no torch needed; a fake model handle is injected):

```bash
.venv/bin/python -m pytest -q tests
# 67 passed
```

## Configuration

All via environment variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `COLPALI_MODEL` | `vidore/colqwen2-v1.0` | HuggingFace model id. |
| `COLPALI_MODEL_REVISION` | (empty = `main`) | Hub git revision pin. Pin it for `trust_remote_code` models; see `../.env.example`. |
| `COLPALI_DEVICE` | `auto` | `cuda` / `cpu` / `mps` / `auto`. |
| `COLPALI_DTYPE` | `bfloat16` | Model dtype. Use `float32` on CPU; `bfloat16` on Ampere+ GPUs. |
| `COLPALI_MAX_BATCH_SIZE` | `8` | Max pages per `/embed_pages` request. Must be >= the Java client's `COLPALI_BATCH_SIZE`. |
| `COLPALI_POOL_GRID` | `32` | The patch-grid side used for row/col pooling. |
| `COLPALI_MAX_VISUAL_TOKENS` | `1280` | Visual-token cap per page for Qwen3-VL-class (tomoro) models; ignored by colpali-engine models. |
| `COLPALI_ATTN_IMPL` | `sdpa` | Attention implementation for `trust_remote_code` models. |
| `COLPALI_HOST` | `0.0.0.0` | Bind host. |
| `COLPALI_PORT` | `8090` | Bind port. |

On GPU hosts the compose files also pass
`PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True` so the PyTorch caching
allocator hands memory back instead of growing to the whole card
(`docker-compose.gpu.yml`, `deploy/bigdumb-sidecar-compose.yml`).

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

### Several replicas behind a balancer

Because one process serves one batch at a time, the production layout runs
several sidecars behind an nginx least-connections balancer:

- `docker-compose.pool.yml` adds the `colpali-lb` service and points
  `pdf-rag-http` at `http://colpali-lb:8090`. Layer it explicitly:
  `docker compose -f docker-compose.yml -f docker-compose.gpu.yml -f docker-compose.pool.yml up -d`.
- `deploy/colpali-lb.conf` lists the upstreams (the local sidecar plus
  replicas on other hosts). No `max_conns`: requests queue at the sidecars.
  `/healthz` and `/info` use a separate upstream list and report ready when
  every replica is mid-batch.
- `deploy/bigdumb-sidecar-compose.yml` is the replica layout on a second GPU
  host: same image, same model cache, same `COLPALI_*` values, so vectors
  match.

Replicas must run the same model, revision, dtype and `COLPALI_*` settings.
See [../docs/components/visual-dataflow.md](../docs/components/visual-dataflow.md) for the data path and
[../docs/plans/sidecar-pool-v1.md](../docs/plans/sidecar-pool-v1.md) for the measurements and the pitfalls.

## Hardware / model picker

| Hardware | Recommended model | Notes |
|----------|--------------------|-------|
| Dual Xeon CPU (no GPU) | `vidore/colsmolvlm-v0.1` (500M) or `vidore/colflor` (770M) | Async ingest mandatory; ~1-3 pages/sec. |
| NVIDIA A2 (16 GB) | `vidore/colqwen2-v1.0` (2B) | Production sweet spot; ~5-15 pages/sec at FP16. |
| NVIDIA A10 / L4 (24 GB) | `vidore/colqwen2.5-v0.1` (7B) | Higher quality, especially multilingual. |
| A100 / H100 | Same; just much faster. | 30-80+ pages/sec. |

The wire shape (`/info`) is identical regardless — the Java side adapts.
