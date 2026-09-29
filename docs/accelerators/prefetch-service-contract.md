# Prefetch service contract

One HTTP contract, several engines. The ingest service asks a prefetch
service for the top-k candidate pages for a query multivector and
reranks those candidates in Qdrant on `original` as it does today. The
GPU index on big-dumb, the Xeon Phi, and a CPU fallback all implement
the same routes; only the engine behind them differs.

## Routes

| Route | Body | Returns | Notes |
|---|---|---|---|
| `POST /v1/prefetch` | `{"query": [[320 floats] × 14-20], "k": 200, "kb": "dtic_archive"}` | `{"generation": 41, "ids": ["<uuid>", …], "scores": [f32 …], "took_ms": 38}` | ids are Qdrant point UUIDs, so the caller reranks by `has_id` with no translation |
| `POST /v1/append` | `{"kb": …, "points": [{"id": "<uuid>", "pooled_rows": [[320] × 32]}]}` | `{"generation": 42, "count": 934900}` | f32 in; the service quantizes, so every backend applies the same math |
| `POST /v1/tombstone` | `{"kb": …, "ids": [...]}` | `{"generation": 43}` | mask bits; compaction is the service's business |
| `POST /v1/reload` | `{"kb": …}` | `202` and a task id | rebuild from Qdrant over gRPC; the old index serves until the new one is loaded |
| `GET /v1/status` | | `{"kb", "generation", "count", "tombstoned", "engine": "cuda:1" or "phi:mic0", "vram_gb", "temp_c", "loaded_at", "ready": true}` | the ingest service reads `ready` and `generation` |

Plain HTTP with JSON on the LAN, no auth beyond the network, the same
trust model as the ColPali sidecar. A query body is 12 KB.

## Semantics every backend must share

- **One quantization rule, owned by the service.** Pages: symmetric int8
  with one scale per page (absmax/127), which factors out of the max over
  rows and the sum over query rows, so the whole MaxSim runs in int32 and
  scales once at the end. Queries: int8 with one scale per query row.
  Both measured kernels do exactly this; backends do not deviate, so the
  GPU and the Phi produce the same candidate sets for the same input and
  are testable against the same ground truth.
- **`k` is a candidate count**, capped at 500. Rescoring of the final
  candidates happens in Qdrant on `original`, not in the service. The
  service returns int8-ranked ids only; its memory is the int8 matrix plus
  scales, on any engine (9.6 GB for `pooled_rows` at 934,834 pages).
- **Generation** is a monotonic integer bumped on every append,
  tombstone and reload. The ingest service records the generation it
  observed at the end of each bulk load. If a prefetch response carries a
  lower generation than the caller's last ingest, the caller also runs
  Qdrant's own HNSW prefetch and unions the candidates: new documents are
  never invisible, only served the slow way until `/v1/append` catches up.
- **Batching is the backend's concern.** The engine collects requests for
  up to about 10 ms and sweeps the matrix once for the batch. The contract
  exposes single requests; callers never batch.
- **Errors**: `503` with `ready: false` while loading, which the caller
  treats exactly like a connection failure.

## The caller (`ColPaliPipeline`)

```
embed query (sidecar)
  → if ingest.colpali.prefetch-url is set and /v1/status says ready:
        POST /v1/prefetch (timeout 500 ms) → ids
        if response.generation < last_ingest_generation:
            ids ∪= Qdrant HNSW prefetch (today's path)
    else:
        Qdrant HNSW prefetch (today's path, unchanged)
  → Qdrant query on `original`, filter has_id(ids), rescoring on, limit top_k
```

Config: `ingest.colpali.prefetch-url` (empty = off; nothing changes for a
deployment without the service), `ingest.colpali.prefetch-timeout-ms`
(500), `ingest.colpali.prefetch-k` (200). The per-KB generation lives in
the job queue's metadata where the bulk runner can read and set it. The
existing `hnsw_ef` and oversampling settings stay for the fallback path.

## Backends

- **GPU on big-dumb (first, production).** Python, torch, the optimized
  int8 kernel (`gpu_prefetch_bench_v2.py` lineage), the matrix in a fixed
  allocation on GPU 1 of the CMP 170HX pair, the f32 export as persistence
  on `/srv_big` NVMe, reload from Qdrant over gRPC. Container with
  `--gpus device=1` and a memory cap; the chat LLM's `--tensor-split`
  gives that card correspondingly less. Reference implementation of the
  contract.
- **Xeon Phi (second, experiment).** A C server in native mode on the
  card implements the prefetch sweep over `mic0`; a proxy in the CentOS 7
  guest implements the routes, quantizes queries, holds the id table and
  the matrix file, and forwards. See [xeon-phi-bringup.md](xeon-phi-bringup.md).
- **CPU fallback (optional, last).** The same C kernel built for AVX-512
  on big-dumb or AVX2 on the R530 behind the same routes, at 0.3 to 1.5 s
  per query. Qdrant's own path already covers the degraded case, so this
  is built only if a third engine is ever wanted.

## What crosses the wire per request (Phi backend, for clarity)

| Flow | Direction | Size | When |
|---|---|---|---|
| prefetch | query rows int8 + scales, proxy → card; top-k indices + scores, card → proxy | 6 KB in, ~2 KB out | every query |
| append | quantized pages, proxy → card | 10 KB per page | after ingests |
| tombstone | indices | bytes | on deletes |
| reload | the int8 matrix, proxy → card | 9.6 GB once | card boot or rebuild |
| status | counters, temperature | bytes | polled |

The matrix never moves per query. The GPU backend has the same split with
the socket removed: proxy and engine are one process and the matrix sits
in VRAM.

## Persistence and rebuild

The service never invents data; Qdrant is the source. `/v1/reload`
scrolls `pooled_rows` over gRPC, quantizes, writes the matrix file and
loads it. Appends update the file and the resident copy together. The
bulk runner calls `/v1/append` per completed batch, or `/v1/reload` once
after a large load, and records the generation. On the Phi the card's RAM
is volatile: the proxy pushes the matrix at every card boot (minutes over
`mic0` TCP, under a minute over SCIF).

## Tests before it touches the ingest service

1. Contract tests against both backends with the 50 benchmark queries:
   candidate-set overlap between engines above 0.99 at `k` 200, recall
   against the exact ground truth at the measured 0.995.
2. Append then prefetch: an appended page is retrievable on the next
   request; tombstoned, it is not.
3. Reload under load: queries keep answering from the old index until the
   new one is ready.
4. The ingest service against a stopped service: the fallback path
   answers with today's numbers.

Benchmark assets on the R530: `/srv/pdf-corpus/ingest/recall/`
(queries, exact ground truth, `recall_check.py`, `concurrency.py`) and
`/srv/pdf-corpus/gpu-prefetch/` (exports, kernels, results).
