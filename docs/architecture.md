# Architecture

## Goal

Give an LLM agent two MCP tool calls — `ingest_document` and `search_documents` —
plus an `inspect_page` escape hatch, that together let it write to and query
a multimodal RAG store without having to know about chunking, embedding,
vector storage, or the difference between text-pipeline and visual-pipeline
retrieval.

Two backends are wired:

- **Qdrant** (default): we own the full RAG. Text extraction (Tika + PDFBox),
  chunking, embedding (OpenAI-compatible — llama-server by default), AND
  optionally a parallel visual pipeline (ColPali sidecar + page-image store +
  multivector Qdrant collection). Search fuses both pipelines.
- **Open WebUI** (parallel target): we hand a raw file to Open WebUI and let
  it do extraction/chunking/embedding/storage. No direct search for this
  backend.

Backends are picked per tool call via the `backend` arg, or fall back to
`ingest.backend.default` (env `INGEST_BACKEND`).

## Module layout

```
pdf-rag-ingest/
├── pom.xml                 parent (multi-module, dependencyManagement only)
├── core/                   ALL Java logic + ALL Java tests. No transport.
│   └── src/main/java/org/hayden/
│       ├── tools/IngestTools.java        MCP surface (5 @Tool methods)
│       ├── ingest/
│       │   ├── IngestService.java        dispatcher: name → Backend
│       │   ├── IngestRequest / SearchRequest         tool inputs
│       │   ├── IngestResult / SearchResponse / SearchHit       results
│       │   ├── InspectPageResult         inspect_page result
│       │   ├── PageText                  per-page extraction record
│       │   ├── FileFetcher.java          url / path / inline → FetchedFile
│       │   └── IngestException.java
│       └── backend/
│           ├── Backend.java              interface
│           ├── KnowledgeBaseSummary.java
│           ├── qdrant/
│           │   ├── QdrantBackend.java    thin orchestrator
│           │   ├── ChunkPipeline.java    text-side pipeline
│           │   ├── ColPaliPipeline.java  visual-side pipeline
│           │   ├── PageHit.java          page-level hit record
│           │   ├── TextExtractor.java    Tika + PDFBox per-page
│           │   ├── Chunker.java          page-tagged sliding window
│           │   ├── Embedder.java         OpenAI-compatible /v1/embeddings
│           │   ├── QdrantClient.java     REST: collections + points +
│           │   │                              multivector + multistage query
│           │   ├── PageRasterizer.java   PDF → PNG via PDFBox renderer
│           │   ├── TextLayerProbe.java   per-page text_quality 0|1|2
│           │   ├── ColPaliClient.java    HTTP client for the sidecar
│           │   ├── PageImageStore.java   interface for PNG storage
│           │   ├── FilesystemPageImageStore.java   v1 impl
│           │   ├── UuidV5.java           deterministic point IDs
│           │   ├── Chunk.java            page-tagged chunk record
│           │   └── fusion/
│           │       ├── FusionStrategy.java   interface
│           │       ├── RrfFusion.java        rank fusion (default)
│           │       ├── WeightedScoreFusion.java   score fusion (alt)
│           │       ├── FusionConfig.java     tunable knobs record
│           │       ├── ConfidenceCalculator.java   per-hit + response label
│           │       └── FusionEngine.java     resolves mode, runs pipelines
│           └── openwebui/
│               ├── OpenWebUiBackend.java implements Backend
│               ├── OpenWebUiClient.java
│               ├── KnowledgeService.java
│               ├── FileUploadService.java
│               ├── OpenWebUiException.java
│               └── dto/                  records, @JsonIgnoreProperties
├── server-stdio/           thin shell: core + quarkus-mcp-server-stdio
├── server-http/            thin shell: core + quarkus-mcp-server-http
└── sidecar/                Python ColPali sidecar (separate venv / Docker image)
    ├── pyproject.toml
    ├── Dockerfile.cpu / Dockerfile.cuda
    └── src/colpali_server/...
```

### Why three Java modules

`quarkiverse-mcp-server` ships stdio and Streamable HTTP transports as
**separate, mutually-exclusive Maven artifacts**. A single Quarkus build can
only pull one of them. We resolve this with the three-module layout:

1. Every CDI bean — including `@Tool` methods — lives in `core`, which depends
   only on `quarkus-mcp-server-core` (no transport).
2. `server-stdio` and `server-http` are near-empty shells that depend on
   `core` plus exactly one transport extension.
3. Each transport module's `application.properties` has
   `quarkus.index-dependency.core.*` so Quarkus's Arc indexes `core`'s
   classes at build time.

**Operational rule:** to add or change a tool or a backend, edit `core/` only.
Both transports pick it up via CDI; the transport modules need no changes.

### Why the Python sidecar lives in this repo

The ColPali sidecar is Python because the ML stack (`colpali-engine`,
`transformers`, `torch`) is Python-only. Two options for hosting it:

- **Separate repo** — strict process and language separation.
- **`sidecar/` subdirectory in this repo** ← what we chose. Single CI surface,
  one PR for paired Java + Python changes, simpler `docker-compose up`.

The HTTP contract between Java and Python is the boundary. Either party can
be re-implemented without touching the other.

## Dispatcher

`IngestService` is a thin façade in front of CDI-discovered `Backend` beans:

```java
@Inject Instance<Backend> backends;
@ConfigProperty(name = "ingest.backend.default") String defaultBackend;

public IngestResult ingest(IngestRequest req) { return pick(req.backend()).ingest(req); }
public SearchResponse search(SearchRequest req) { return pick(req.backend()).search(req); }
```

`pick()` matches `Backend.name()` (case-insensitive). Unknown names fail fast
with a useful error listing the known backends. `list_knowledge_bases` is the
one tool that can fan out to *all* backends and merge the result.

## Qdrant backend — two pipelines

`QdrantBackend` is a thin orchestrator (~180 lines). The real work happens
in two collaborators:

```
QdrantBackend.ingest
  ├──► FileFetcher (URL / path / inline → FetchedFile, shared with OpenWebUi)
  │
  ├──► ChunkPipeline.ingestChunks         ◄── always runs
  │       │
  │       ├──► TextExtractor.extractPerPage    (PDFBox per-page for PDFs,
  │       │                                     Tika single-blob for others)
  │       ├──► Chunker.chunkPerPage             (page-tagged chunks)
  │       ├──► Embedder.embed                   (llama-server / OpenAI-compat)
  │       └──► QdrantClient.upsertPoints  → <kb> collection (single-vector cosine)
  │
  └──► ColPaliPipeline.ingestPages        ◄── if enable_visual_index=true
          │
          ├──► PageRasterizer.renderAll        (PDFBox PDFRenderer → PNG bytes)
          ├──► TextLayerProbe.probe            (per-page text_quality 0|1|2)
          ├──► PageImageStore.store            (PNG → filesystem)
          ├──► ColPaliClient.embedPages        (HTTP → Python sidecar)
          └──► QdrantClient.upsertMultivectorPoints
                                         → <kb>_pages collection
                                           (named vectors: original +
                                            pooled_rows + pooled_cols,
                                            MAX_SIM comparator, binary
                                            quantization on original)
```

Both pipelines share a `docId` (UUID generated once at the top of
`QdrantBackend.ingest`) — that's the chunk-to-page join key for fusion.

### Visual index opt-in

Per-KB, decided at first ingest. Implicit state: a `<kb>_pages` collection
exists ↔ visual indexing is enabled for that KB. No separate metadata store.

- **Per-call**: `enable_visual_index` arg on `ingest_document`.
- **Per-deployment**: `INGEST_DEFAULT_VISUAL_INDEX` env (default `true`).
- **Mode-mismatched ingest on existing KB**: hard-reject with a clear "create
  a new KB or change the arg" message. No `force_mode_change` in v1.

### Sidecar-down behavior

Asymmetric:
- **Ingest** with visual requested + sidecar down → hard-fail. Don't ingest
  half a document.
- **Search** with visual-enabled KB + sidecar down → soft-degrade to text-only
  with `fusion_mode: "text_only_fallback"` in the response.

### Async ingest routing

`QdrantBackend.ingest` checks `shouldQueue(file, visualRequested)` before
running. Large PDFs (page count ≥ `ingest.queue.sync_threshold_pages`,
default 20) with visual indexing enabled get queued via `IngestQueue`; the
`IngestWorker` thread pool drains the queue in the background. The agent
gets back `IngestResult.queued{jobId, "queued", 0 chunks}` immediately and
polls `get_ingest_status(jobId)` until terminal.

Text-only ingests and small PDFs run synchronously (existing behavior).

Queue state persists to `${INGEST_QUEUE_PATH}/<jobId>.json`. On JVM restart,
`IN_PROGRESS` jobs requeue with `retryCount++` (capped at
`ingest.queue.max_retries`, default 3).

See [components/ingest-queue.md](components/ingest-queue.md) for the queue
lifecycle and at-least-once recovery semantics.

## Search-time fusion

```
QdrantBackend.search
   │
   ▼
FusionEngine.search
   │
   │   1. Resolve retrieval_mode against KB capability
   │      (auto / fusion / text_only / colpali_only)
   │      Apply fallback matrix; collect warnings.
   │
   │   2. Run the appropriate pipelines.
   │      - text_only:    ChunkPipeline.searchChunks only
   │      - colpali_only: ColPaliPipeline.searchPages only
   │      - fusion:       both, with N=4×topK chunks + N=2×topK pages
   │
   │   3. Apply FusionStrategy (RrfFusion default, WeightedScoreFusion alt)
   │      Join chunks to pages via (docId, pageStart..pageEnd) overlap.
   │      Surface orphan pages (visual rank, no text chunk in same doc).
   │
   │   4. Annotate via ConfidenceCalculator
   │      Per-hit: 0.4 × text * text_trust + 0.4 × visual + 0.2 × agreement
   │      Buckets: high (>0.7) / medium (>0.4) / low.
   │      Response confidence = max(per-hit confidence in top-K).
   │
   ▼
SearchResponse{backend, kbName, fusionMode, confidence, warnings, hits}
```

### Fallback matrix

| `retrieval_mode` | KB has visual index? | Resolved mode |
|------------------|----------------------|----------------|
| `auto` (default) | yes | `fusion` |
| `auto` (default) | no | `text_only` |
| `fusion` | yes | `fusion` |
| `fusion` | no | `text_only_fallback` (with warning) |
| `text_only` | (either) | `text_only` |
| `colpali_only` | yes | `colpali_only` |
| `colpali_only` | no | (throws) |

Sidecar down at query time → `text_only_fallback` with warning, regardless of
the requested mode. Ingest hard-fails the same case.

## Page-image storage

`<kb>_pages` Qdrant payload carries only a `page_image_key` (opaque string).
The actual PNG bytes live in `PageImageStore` — v1 is `FilesystemPageImageStore`
writing under `${INGEST_PAGE_STORE_ROOT}/<kb_name>/<doc_id>/<NNNNNN>.png`.
vNext adds S3-compatible adapter (RustFS / MinIO / AWS / Backblaze).

Per-KB / per-doc cleanup is recursive directory removal.
`inspect_page` retrieves bytes by key + decodes PNG dimensions from the IHDR
header without fully decoding the image.

## Gotchas (non-obvious, will bite you)

### HTTP/1.1 must be pinned

`OpenWebUiClient.init()`, `FileFetcher.init()`, `Embedder.init()`,
`ColPaliClient.init()`, `QdrantClient.init()` all build their `HttpClient`
with `.version(HttpClient.Version.HTTP_1_1)`. Without this:

- `java.net.http` defaults to HTTP/2 and on plaintext requests sends a
  cleartext h2c upgrade trio.
- Uvicorn (which fronts the Python sidecar AND Open WebUI) and llama.cpp's
  `llama-server` reject the trio. Result: `400 Invalid HTTP request received`
  or a closed connection.

Any new `HttpClient` in this project must pin HTTP/1.1.

### Async-processing race (Open WebUI)

`POST /api/v1/files/` returns immediately; extraction + embedding happen in
a background worker. Calling `/knowledge/{id}/file/add` before
`/files/{id}/process/status` reports `completed` returns `400 "content
provided is empty"`. `FileUploadService.waitUntilProcessed` is the guard.

### Qdrant collection dim is immutable

Single-vector collections track `vectors.size`. Multivector collections
track per-named-vector sizes. Switching `EMBED_MODEL` or `COLPALI_MODEL`
without recreating the collection fails loudly at ensureCollection time.
Recovery: delete the collection (and the `<kb>_pages` sibling for visual KBs)
or use a new KB name.

### Mode mismatch on existing KB

Ingesting `enable_visual_index=false` into a KB that was created with visual
on (or vice versa) hard-fails. No `force_mode_change`. Create a new KB to
switch modes.

### Cross-module CDI discovery needs a Jandex index

`core/pom.xml` runs `jandex-maven-plugin`. Each transport module's
`application.properties` has `quarkus.index-dependency.core.*`. Without
both, `tools/list` returns `[]`.

### stdio: stdout is sacred

The stdio transport reads JSON-RPC frames on stdin and writes them on stdout.
Logs go to stderr (`quarkus.log.console.stderr=true`). Never
`System.out.println` from `core/`.

### Point ID determinism

`UuidV5.forChunk(docId, chunkIndex)` and `UuidV5.forPage(docId, pageNumber)`
produce stable UUIDs given the same input. Same doc + same chunking →
idempotent overwrite on re-ingest. Different chunking (because
`chunk.size-chars` changed) yields different IDs → duplicate copy. No
dedupe by source URL.

## Configuration surface

All `@ConfigProperty` keys are in
`core/src/main/resources/application.properties` and inherited by both
transports.

| Key | Default | Notes |
|-----|---------|-------|
| `ingest.backend.default` | `qdrant` | from `INGEST_BACKEND` |
| `ingest.openwebui.base-url` | `http://localhost:3000` | OpenWebUi backend |
| `ingest.openwebui.api-key` | *(empty)* | Bearer token |
| `ingest.openwebui.poll.initial-ms` / `max-ms` | `200` / `2000` | OpenWebUi async poll backoff |
| `ingest.qdrant.url` | `http://localhost:6333` | from `QDRANT_URL` |
| `ingest.qdrant.api-key` | *(empty)* | from `QDRANT_API_KEY` |
| `ingest.qdrant.distance` | `Cosine` | applied on collection create |
| `ingest.embed.base-url` | `http://localhost:8081/v1` | llama-server / OpenAI-compat |
| `ingest.embed.api-key` | *(empty)* | `Authorization: Bearer …` |
| `ingest.embed.model` | `bge-large-en-v1.5` | from `EMBED_MODEL` |
| `ingest.embed.batch-size` | `64` | per-call to `/embeddings` |
| `ingest.chunk.size-chars` | `1500` | from `INGEST_CHUNK_SIZE_CHARS` |
| `ingest.chunk.overlap-chars` | `200` | from `INGEST_CHUNK_OVERLAP_CHARS` |
| `ingest.extract.max-chars` | `10000000` | Tika body buffer + PDFBox cap |
| `ingest.max-file-bytes` | `104857600` | 100 MB cap in FileFetcher |
| `ingest.visual_index.default_enabled` | `true` | from `INGEST_DEFAULT_VISUAL_INDEX` |
| `ingest.colpali.sidecar-url` | `http://localhost:8090` | from `COLPALI_SIDECAR_URL` |
| `ingest.colpali.render-dpi` | `150` | PDFBox render quality |
| `ingest.colpali.batch-size` | `8` | client-side batch for `/embed_pages` |
| `ingest.page_store.impl` | `filesystem` | v1 only |
| `ingest.page_store.root` | `${user.home}/.pdf-rag-ingest/page-images` | from `INGEST_PAGE_STORE_ROOT` |
| `ingest.text_quality.threshold_low` / `_full` | `50` / `500` | PDFBox char-count buckets |
| `ingest.queue.persistence_path` | `${user.home}/.pdf-rag-ingest/queue` | from `INGEST_QUEUE_PATH`; async-job file store |
| `ingest.queue.worker_threads` | `1` | from `INGEST_QUEUE_WORKERS`; concurrent background ingests |
| `ingest.queue.sync_threshold_pages` | `20` | from `INGEST_ASYNC_THRESHOLD_PAGES`; PDFs ≥ this go to the queue |
| `ingest.queue.max_retries` | `3` | crash-recovery retry cap |
| `ingest.queue.poll_timeout_ms` | `1000` | worker poll interval |
| `ingest.retrieval.default_mode` | `auto` | from `RETRIEVAL_MODE` |
| `ingest.fusion.strategy` | `rrf` | from `FUSION_STRATEGY` |
| `ingest.fusion.rrf.k` | `60` | RRF constant |
| `ingest.fusion.weighted.text` / `.visual` | `0.5` / `0.5` | weighted-fusion weights |
| `ingest.fusion.weighted.text_score_floor` / `visual_score_floor` | `1.0` / `50.0` | empirical normalizers |
| `ingest.search.n_text_multiplier` | `4` | pre-fusion chunks pulled = N × topK |
| `ingest.search.n_pages_multiplier` | `2` | pre-fusion pages pulled = N × topK |
| `ingest.confidence.weight_text` / `_visual` / `_agreement` | `0.4` / `0.4` / `0.2` | heuristic weights |
| `ingest.confidence.threshold_high` / `_medium` | `0.7` / `0.4` | bucket boundaries |
| `quarkus.http.port` | `8080` | server-http only |
| `quarkus.http.cors.origins` | `*` | server-http only |

Python sidecar config has its own `COLPALI_*` env prefix; see
[components/colpali-sidecar.md](components/colpali-sidecar.md).

## Testing

Java side: **174 tests, all plain JUnit 5 + WireMock**, no `@QuarkusTest`.
Beans constructed by hand, `@ConfigProperty` fields set via reflection,
`@PostConstruct init()` invoked reflectively. `mvn -pl core test` runs in
under 15 seconds without any live services.

Python sidecar: **26 tests, pytest with FastAPI TestClient**, autouse fixture
pre-injects a `FakeModelHandle` so tests don't need torch. `pytest -q` runs in
under 2 seconds.

See per-component docs for what each test class covers.
