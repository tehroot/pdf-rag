# ColPali sidecar (Python)

`sidecar/` — a separate Python project, lives in this repo as a subdirectory.
A small FastAPI service that wraps a ColVision model (ColPali / ColQwen2 /
ColSmolVLM / ColFlor) behind the HTTP contract the Java side expects.

The Java side is **model-agnostic** via `/info` — switching models or
sidecar implementations (PyTorch / ONNX / llama.cpp) is a deploy-time
concern, not a code change in `pdf-rag-ingest`.

## What it does

Four HTTP endpoints:

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/healthz` | Liveness probe; reports whether the model finished loading. |
| GET | `/info` | Self-report: model name, vector dim, batch size, device. |
| POST | `/embed_pages` | Embed a list of page images (base64 PNG) into multi-vectors. Returns `original`, `pooled_rows`, `pooled_cols`. |
| POST | `/embed_query` | Embed a query string into a multi-token vector. |

## Project layout

```
sidecar/
├── pyproject.toml              # core deps + [ml] extras + [dev] extras
├── README.md                   # deployment notes + hardware/model picker
├── Dockerfile.cpu              # python:3.11-slim + CPU-only torch
├── Dockerfile.cuda             # pytorch/pytorch:2.4.1-cuda12.4-cudnn9-runtime
├── .gitignore
├── src/colpali_server/
│   ├── __init__.py
│   ├── config.py               # pydantic-settings; COLPALI_* env vars
│   ├── schemas.py              # pydantic models for wire shapes
│   ├── model.py                # ModelHandle Protocol + RealModelHandle
│   ├── loader.py               # load_model(settings) → ModelHandle
│   ├── pooling.py              # row/col mean pooling (pure Python)
│   ├── inference.py            # decode b64 → embed → pool → respond
│   └── main.py                 # FastAPI app + lifespan + endpoints
└── tests/
    ├── conftest.py             # pytest fixtures (auto-inject FakeModelHandle)
    ├── fakes.py                # FakeModelHandle + make_b64_png helper
    ├── test_api.py             # HTTP surface tests
    ├── test_inference.py       # inference + HTTP integration with fake
    └── test_pooling.py         # pure-math pooling unit tests
```

## Configuration

All via environment variables with the `COLPALI_` prefix:

| Variable | Default | Purpose |
|----------|---------|---------|
| `COLPALI_MODEL` | `vidore/colqwen2-v1.0` | HuggingFace model id. |
| `COLPALI_DEVICE` | `auto` | `cuda` / `cpu` / `mps` / `auto`. |
| `COLPALI_DTYPE` | `bfloat16` | Model dtype. CPU falls back to float32. |
| `COLPALI_MAX_BATCH_SIZE` | `8` | Pages per `/embed_pages` request. |
| `COLPALI_POOL_GRID` | `32` | Patch-grid side for row/col pooling. |
| `COLPALI_HOST` | `0.0.0.0` | Bind host. |
| `COLPALI_PORT` | `8090` | Bind port. |
| `COLPALI_ENABLE_POOLED` | `true` | Whether to return pooled vectors. |
| `COLPALI_ENABLE_ORIGINAL` | `true` | Whether to return original vectors. |

## Wire shapes

### `GET /info`

```json
{
  "model_name": "vidore/colqwen2-v1.0",
  "vector_dim": 128,
  "supports_pooled": true,
  "pooled_methods": ["rows", "cols"],
  "max_batch_size": 8,
  "device": "cuda:0"
}
```

The Java `ColPaliClient.SidecarInfo` DTO deserializes this verbatim. New
fields are added optionally — the Java side ignores unknown fields.

### `POST /embed_pages`

Request:
```json
{
  "pages": [
    {"page_id": "doc-uuid:1", "image_b64": "iVBORw0KG..."}
  ],
  "include_original": true,
  "include_pooled": true
}
```

Response:
```json
{
  "embeddings": [
    {
      "page_id": "doc-uuid:1",
      "original": [[0.1, 0.2, ...], ...],         // ~1030 × 128
      "pooled_rows": [[...], ...],                 // 38 × 128 (32 row averages + 6 specials)
      "pooled_cols": [[...], ...]                  // 38 × 128
    }
  ]
}
```

`include_original` / `include_pooled` let the Java side request just what it
needs (small storage saving in dev / debug scenarios).

### `POST /embed_query`

Request:
```json
{ "query": "rate limiting strategy" }
```

Response:
```json
{ "vectors": [[0.1, 0.2, ...], ...] }   // n_query_tokens × vector_dim
```

### `GET /healthz`

```json
{ "status": "ok", "ready": true }
```

`ready: false` while the model is still loading at startup (which can take
minutes for ColQwen2 on a cold cache).

## Internal architecture

### `ModelHandle` — the abstraction

```python
class ModelHandle(Protocol):
    @property
    def model_name(self) -> str: ...
    @property
    def vector_dim(self) -> int: ...
    @property
    def device(self) -> str: ...
    def embed_images(self, images: list[Image]) -> list[list[list[float]]]: ...
    def embed_query(self, query: str) -> list[list[float]]: ...
```

Two implementations:

- `RealModelHandle` — production. Lazy-imports `torch` + `transformers` +
  `colpali_engine`. Wraps the actual ColVision model. Loading takes
  ~minutes for ColQwen2-2B on cold cache.
- `FakeModelHandle` (in `tests/fakes.py`) — synthetic vectors shaped like
  ColPali output. Used by every test so we don't need torch / a real model
  to run unit tests.

### Model-class registry

The `colpali_engine` library has distinct classes for each model family:
`ColQwen2`, `ColPali`, `ColIdefics3`, etc. `model.py` carries a registry
that maps model-name substrings to the right `(model_cls, processor_cls)`
pair:

```python
_MODEL_REGISTRY = [
    (("colqwen2.5",),     ("ColQwen2_5",  "ColQwen2_5_Processor")),
    (("colqwen",),        ("ColQwen2",     "ColQwen2Processor")),
    (("colpali",),        ("ColPali",      "ColPaliProcessor")),
    (("colsmolvlm",
      "colsmol",
      "smolvlm"),         ("ColIdefics3",  "ColIdefics3Processor")),
    (("colflor",
      "florence"),        ("ColPali",      "ColPaliProcessor")),
]
```

First substring match wins. Lazy `getattr` lookup against `colpali_engine.models`
so we don't have a hard dep on specific class names at import time —
upstream API drift gets a clear runtime error, not an import explosion.

### Pooling

Pure Python (no numpy / torch). `pooling.py` averages the 32×32 patch grid
rows or columns and appends the special tokens unchanged:

```python
def mean_pool_rows(embedding, grid_size=32, n_special_tokens=6):
    n_patches = grid_size * grid_size       # 1024 for ColPali
    patches = embedding[:n_patches]
    specials = embedding[n_patches:n_patches + n_special_tokens]

    pooled = []
    for row in range(grid_size):
        accum = [0.0] * dim
        for col in range(grid_size):
            for j in range(dim):
                accum[j] += patches[row * grid_size + col][j]
        pooled.append([v / grid_size for v in accum])

    return pooled + specials
```

Returns `grid_size + n_special_tokens` vectors (38 for ColPali). The
column-pooling variant transposes the inner loop.

Adapts gracefully if the input doesn't have exactly `grid_size² + n_special`
tokens — falls back to the whole embedding rather than throwing.

### Lifespan + test injection

```python
@asynccontextmanager
async def lifespan(app):
    if _state["handle"] is None:
        _state["handle"] = load_model(settings())   # heavy load — minutes
    _state["ready"] = True
    yield

def set_model_for_testing(handle):
    """Tests pre-inject a FakeModelHandle. Lifespan sees it's set and skips load."""
    _state["handle"] = handle
    _state["ready"] = handle is not None
```

The autouse `conftest.py` fixture injects a `FakeModelHandle` before every
test — bootstrap tests, inference tests, and HTTP integration tests all
work without torch installed.

## Dockerfiles

`Dockerfile.cpu`:

```dockerfile
FROM python:3.11-slim AS base
RUN apt-get install -y build-essential libgl1 libglib2.0-0
WORKDIR /app
COPY pyproject.toml ./
# Install CPU-only torch from PyTorch's CPU index FIRST — keeps the image
# from pulling in multi-GB CUDA wheels.
RUN pip install --index-url https://download.pytorch.org/whl/cpu "torch>=2.4,<3.0"
COPY src/ ./src/
RUN pip install -e ".[ml]"
ENV COLPALI_MODEL=vidore/colsmolvlm-v0.1   # smaller model for CPU
HEALTHCHECK CMD python -c "..."
CMD ["colpali-server"]
```

`Dockerfile.cuda` uses `pytorch/pytorch:2.4.1-cuda12.4-cudnn9-runtime` as the
base (torch + CUDA pre-installed) and defaults to `COLPALI_MODEL=vidore/colqwen2-v1.0`.

Both include a `HEALTHCHECK` that hits `/healthz` and checks `ready: true`.

## Hardware / model picker

| Hardware | Recommended model | Notes |
|----------|--------------------|-------|
| Dual Xeon CPU (no GPU) | `vidore/colsmolvlm-v0.1` (500M) or `vidore/colflor` | Async ingest mandatory; ~1-3 pages/sec. |
| NVIDIA A2 (16 GB) | `vidore/colqwen2-v1.0` (2B) | Production sweet spot; ~5-15 pages/sec FP16. |
| NVIDIA A10 / L4 (24 GB) | `vidore/colqwen2.5-v0.1` (7B) | Higher quality, multilingual. |
| A100 / H100 | Same; much faster. | 30-80+ pages/sec. |

The wire shape (`/info`) is identical regardless — the Java side adapts.

## Failure modes

| Case | Behavior |
|------|----------|
| Model not yet loaded (startup) | `/healthz` → `ready: false`; embed endpoints → 503. |
| Unknown model name | `RealModelHandle.__init__` raises at startup with a clear message — container fails healthcheck and won't go live. |
| Bad base64 in `/embed_pages` | 400 with the base64 decode error. |
| Empty/blank query | 400 ("query must be non-empty"). |
| Batch size > max | 400 with the configured limit. |
| Inference OOM (GPU) | Container crashes; orchestrator restarts. Real fix: lower `COLPALI_MAX_BATCH_SIZE` or pick a smaller model. |
| ML deps not installed (e.g. someone tried to run the CPU image without `[ml]`) | `RealModelHandle.__init__` raises `ImportError`; lifespan fails fast. |

## Why it's like this

- **Separate process, HTTP boundary.** ColPali is Python-only territory
  today — `colpali-engine` + `transformers` + `torch`. Embedding it directly
  in the JVM is impractical. A small HTTP service is the clean separation.
- **Model-agnostic wire shape.** `/info` lets the Java side adapt to whatever
  model the operator chose. Switching from ColPali to ColQwen2 to
  ColSmolVLM doesn't require recompiling the Java side.
- **Lazy torch imports.** `model.py` only imports torch when
  `RealModelHandle.__init__` runs. The bootstrap test path runs without ML
  deps installed; only deploy-time needs the full `[ml]` extras.
- **Test injection via module-level state.** `set_model_for_testing(handle)`
  pre-populates the lifespan state so the test suite never tries to load a
  real model. Simple, no FastAPI dependency-override gymnastics.
- **Pooling in pure Python.** `pooling.py` doesn't need torch — it's
  list-of-lists arithmetic. Lets us unit-test the pooling math in isolation
  with simple fixtures.
- **CPU torch from a separate index.** Pinning to `--index-url
  download.pytorch.org/whl/cpu` in the CPU Dockerfile keeps the image from
  pulling multi-GB CUDA wheels we don't need.

## Tests

26 tests, all run without torch installed (autouse `FakeModelHandle` injection):

- `test_pooling.py` (9): row/col pooling correctness, grid-size adapt,
  special-token preservation, sanity checks.
- `test_inference.py` (8): inference layer + HTTP integration with the fake
  model, include-flag handling, invalid base64 rejection.
- `test_api.py` (9): HTTP surface — health, info shape, batch limit, blank
  query rejection.

To run:
```bash
cd sidecar
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/pytest -q
```
