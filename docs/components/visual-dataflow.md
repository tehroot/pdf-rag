# Visual ingest data path: from the PDF to Qdrant, across hosts

How a page becomes a set of vectors in Qdrant when the ColPali sidecars run
on more than one machine. This is the shape the sidecar pool
(`docker-compose.pool.yml`, `deploy/colpali-lb.conf`,
[plans/sidecar-pool-v1.md](../plans/sidecar-pool-v1.md)) put in production
on the R530 + big-dumb, and it is the same shape with one sidecar: only the
number of upstreams behind the balancer changes.

The one-sentence version: **a vector crosses the network once, from the
sidecar that computed it back to the ingest service, and from there it is
written into Qdrant on the ingest service's own host. Sidecars never talk to
Qdrant and hold no state.**

## Components on the path

| Component | Where | Role |
|---|---|---|
| `pdf-rag-http` (the ingest service) | R530 | Owns the job queue, renders pages, calls the sidecar pool, writes Qdrant. The only Qdrant writer. |
| `colpali-lb` (nginx) | R530, same compose network | Least-connections balancer in front of every sidecar. The ingest service knows one URL: `http://colpali-lb:8090`. |
| `colpali-server` | R530 (one, on the A4500) | A sidecar replica like any other. |
| `colpali-0a` … `colpali-1d` | big-dumb (eight, four per CMP 170HX) | Sidecar replicas: same image, same model cache, same `COLPALI_*` env. |
| Qdrant | R530 | Collections `<kb>` (chunks) and `<kb>_pages` (multivectors), on the ZFS pool. |
| Page-image store | R530, ZFS pool | PNG per page, written by the ingest service before embedding. |

## The path for one visual job

```
R530 ───────────────────────────────────────────────────────────────── big-dumb
                                                                       
 IngestWorker (one of N)                                               
   1. render PDF pages → PNG (PDFBox, CPU)                             
   2. store PNGs (page-image store, pool)                              
   3. batch ≤12 PNGs, base64 → JSON ──► colpali-lb ──► least_conn ──►  colpali-0a..1d
                                          │                              (or local
                                          │                              colpali-server)
   5. decode base64 float32 ◄── 27 MB ◄───┘ ◄──── 4. model + pooling ◄──┘
      → float[][] per page
   6. Qdrant upsert, 4 pages per call ──► qdrant:6333 (REST, JSON, wait=true)
                                              7. parse, append segment, persist (pool)
```

1. **Render.** A queue worker picks a `VISUAL` job and rasterizes every
   page with PDFBox on the R530's CPU (about 0.64 s per page). PNGs go to
   the page-image store first, so the worker can retry embedding without
   re-rendering and `inspect_page` can serve the image later.
2. **Request.** The worker takes up to `COLPALI_BATCH_SIZE` pages (12 in
   production), base64-encodes the PNGs into an `/embed_pages` JSON body
   (1 to 25 MB depending on page content) with `encoding: f32b64`, and
   POSTs it to the balancer.
3. **Balance.** nginx picks the upstream with the fewest active
   connections. With no `max_conns`, a busy replica queues the request in
   its accept backlog; each sidecar serves one request at a time (its
   event loop blocks for the batch), so per-request latency is queue depth
   times batch time. Requests are proxied unbuffered; the body is not
   retried on another upstream (a proxied POST cannot be replayed after
   the body streamed), so a replica that dies mid-batch yields a 502,
   which the client treats as transient and requeues the job.
4. **Embed.** The sidecar decodes the PNGs, runs the model on its GPU, pools
   on the arrays, and answers with three arrays per page — `original`
   (up to 1,280 tokens × 320), `pooled_rows`, `pooled_cols` — as base64
   little-endian float32 plus `dim` (about 27 MB for 12 pages; `json`
   encoding for old clients is about 62 MB).
5. **Decode.** The worker turns each base64 string into `float[][]` in one
   pass (`ColPaliClient.decodeVectors`). Nothing is boxed; the arrays are
   handed straight to the Qdrant client in the same JVM.
6. **Upsert.** `QdrantClient` writes `INGEST_QDRANT_MULTIVECTOR_UPSERT_BATCH`
   pages per call (4) to the `<kb>_pages` collection, `wait=true`, so the
   call returns when the write is durable. Point ids are
   `UuidV5(docId, pageNumber)`, so a retry overwrites rather than
   duplicates. Since 2026-09-18 this hop is gRPC (`QdrantGrpcUpserter`,
   port 6334 on the compose network): the float arrays travel as packed
   float32 in protobuf, about 7 MB per 4-page call, nothing formatted or
   parsed. `ingest.qdrant.upsert-transport=rest` selects the old JSON path
   (about 20 MB of decimal text per call, parsed again by Qdrant); a gRPC
   failure falls back to it per batch.
7. **Persist.** Qdrant appends to a segment and flushes to the pool
   (`/tank/qdrant`, spinning mirrors). `original` is stored on disk with
   binary quantization in RAM and no HNSW (`m: 0`); `pooled_rows` /
   `pooled_cols` carry the default HNSW, built by the optimizer as segments
   grow — or not, while the bulk-load setting suspends it.

## Bytes and cost per 12-page batch

| Hop | Transport | Bytes | Who pays |
|---|---|---|---|
| worker → sidecar | HTTP over the LAN via nginx | 1–25 MB PNG | sidecar CPU decodes and preprocesses |
| sidecar → worker | HTTP over the LAN via nginx | 27 MB base64 float32 | worker: one-pass decode |
| worker → Qdrant | gRPC on the R530 bridge, 3 calls × 4 pages | ~21 MB packed float32 | neither side formats or parses; Qdrant ~110% CPU (was ~60 MB JSON at 350–550%) |
| Qdrant → disk | ZFS pool | ~20 MB raw + index and WAL | pool write bandwidth (~250 MB/s observed) |

The LAN is not a constraint: at ~40 batches/min the sidecar→worker hop is
~20 MB/s on a gigabit link. With the gRPC hop the remaining conversions are rendering on the
worker and Qdrant's own segment writes; the pool's write bandwidth is the
next ceiling.

## Consequences of this shape

- **Sidecars are stateless and interchangeable.** A replica can be added,
  removed, or restarted at any time; the only effect is a requeued job.
  Vectors are keyed by `docId` + page number, so which replica embedded a
  page does not matter. (One caveat: across GPU generations bfloat16
  kernels differ in rounding — about 3% of token vectors per page differ
  with cosine < 0.9, pooled vectors match to 0.999, self-MaxSim 0.993
  measured GA102 vs GA100. Accepted for bulk corpora; see the pool plan.)
- **All write load lands on the ingest host.** More sidecars anywhere raise
  throughput only until the ingest host saturates: worker CPU
  (rendering), Qdrant's JSON parsing, or the pool's write bandwidth — in
  that order, as observed on 2026-09-17/18.
- **Queries take the same path in reverse.** `search_documents` embeds the
  query text through the balancer (whichever replica answers) and scores
  against Qdrant on the R530.
- **Nothing off-host writes Qdrant.** Every writer is the ingest service,
  and its page upserts use gRPC (6334) while everything else uses REST
  (6333); Qdrant serves both ports on the same data, so no other client
  is affected by that choice.

## Operating rules that follow

- Recreate services with `--no-deps`; the sidecar health probe blocks
  during a batch and compose would otherwise refuse to start dependents.
- Do not reload the balancer casually during a run: old nginx workers close
  idle keep-alive connections the JDK client still holds (the client
  retries once now, so the cost is re-work, not lost jobs).
- During a bulk load, suspend HNSW building on `<kb>_pages`
  (`indexing_threshold` very high) and restore it afterwards so the graphs
  build once; see the throughput plan's step-4 outcome.
- Size the ingest JVM heap for the worker count: each worker holds a batch
  response, its decoded arrays, and a document's accumulated vectors
  (`PDF_RAG_JAVA_TOOL_OPTIONS`).

## Where the code is

`core/.../jobs/IngestWorker.java` (the loop), `backend/qdrant/ColPaliPipeline.java`
(render → embed → upsert per document), `backend/qdrant/ColPaliClient.java`
(request encoding, response decoding, retry), `backend/qdrant/QdrantClient.java`
(upsert), `sidecar/src/colpali_server/inference.py` (arrays, orjson,
encodings), `deploy/colpali-lb.conf` (the balancer).
