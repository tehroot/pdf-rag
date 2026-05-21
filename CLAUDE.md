# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An MCP server that ingests documents into a vector store and lets an agent
search them. Two pluggable backends:

- **Qdrant** (default) — text pipeline (Tika + Chunker + Embedder) PLUS an
  optional parallel visual pipeline (PDFBox renderer → ColPali sidecar →
  Qdrant multivector collection). Search fuses both via RRF or weighted
  score and returns hits with per-hit + response-level confidence.
- **Open WebUI** — legacy / parallel target. Upload the raw file, poll
  until processing completes, attach the file id to a KB. No direct search.

Java 21, Quarkus 3.33.1, `quarkus-mcp-server` 1.12.0. Python 3.11+ for the
sidecar. Maven via `mvnvm`.

## Commands

```bash
mvn package                              # build everything; runs all Java tests
mvn package -DskipTests                  # skip tests
mvn -pl core test                        # core tests only — fast, no external services
mvn -pl core test -Dtest=RrfFusionTest   # single class
mvn -pl core test -Dtest=QdrantClientTest#queryMultistage_sendsExpectedShape_andParsesHits  # single method
mvn -pl server-http -am package -DskipTests   # rebuild one transport + deps

# Run (Qdrant backend: needs QDRANT_URL, EMBED_BASE_URL, EMBED_MODEL,
#                     plus COLPALI_SIDECAR_URL if visual indexing enabled)
java -jar server-stdio/target/quarkus-app/quarkus-run.jar    # stdio transport
java -jar server-http/target/quarkus-app/quarkus-run.jar     # Streamable HTTP at :8080/mcp
mvn -pl server-http quarkus:dev                              # HTTP transport, live reload

# Python sidecar
cd sidecar
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/pytest -q                       # 26 tests, no torch needed (uses FakeModelHandle)
.venv/bin/pip install -e ".[ml]"          # add real ml deps for actual model loading
.venv/bin/colpali-server                   # run sidecar on :8090

# Whole stack via Docker
docker compose up -d                       # qdrant + llama-server + colpali-server + pdf-rag-http
```

Tests are plain JUnit 5 + WireMock — **not** `@QuarkusTest`. They construct
beans by hand, set `@ConfigProperty` fields by reflection, and reflectively
invoke `@PostConstruct init()`. `mvn -pl core test` is under 15 seconds and
needs no live Qdrant, sidecar, or Open WebUI.

## Module layout

```
core/          ALL Java logic: backends, tool surface, fusion, tests.
server-stdio/  thin: core + quarkus-mcp-server-stdio
server-http/   thin: core + quarkus-mcp-server-http
sidecar/       Python ColPali HTTP service (separate venv / image)
```

Quarkus MCP ships stdio and HTTP as **separate, mutually-exclusive Maven
artifacts**, so a single app can't expose both. All real code (`@Tool`
methods included) lives in `core` as CDI beans; each transport module is a
near-empty shell. **To add or change a tool, edit `core` only** — both
transports pick it up via CDI.

## Architecture

```
core/src/main/java/org/example/
├── tools/IngestTools.java              # MCP @Tool surface (5 tools)
├── ingest/
│   ├── IngestService.java              # dispatcher: picks Backend by arg / default
│   ├── IngestRequest / SearchRequest   # tool input records
│   ├── IngestResult / SearchResponse / SearchHit / InspectPageResult / PageText / DropVisualIndexResult
│   ├── FileFetcher.java                # url / path / inline → FetchedFile (shared)
│   └── IngestException.java
├── jobs/
│   ├── IngestJob.java                  # record: status + request + result + retry counter
│   ├── JobStatus.java                  # enum: QUEUED / IN_PROGRESS / COMPLETED / FAILED
│   ├── IngestQueue.java                # in-memory + file-backed persistence
│   └── IngestWorker.java               # background thread pool draining the queue
└── backend/
    ├── Backend.java                    # interface: name() / ingest() / search() / listKnowledgeBases()
    ├── KnowledgeBaseSummary.java
    ├── qdrant/
    │   ├── QdrantBackend.java          # thin orchestrator
    │   ├── ChunkPipeline.java          # text side: extract → chunk → embed → upsert
    │   ├── ColPaliPipeline.java        # visual side: render → embed → upsert
    │   ├── PageHit.java
    │   ├── TextExtractor.java          # Tika (single-blob) + PDFBox (per-page for PDFs)
    │   ├── Chunker.java                # sliding window, page-tagged chunks
    │   ├── Embedder.java               # OpenAI-compatible /v1/embeddings
    │   ├── QdrantClient.java           # REST: collections + points + multivector + multistage
    │   ├── PageRasterizer.java         # PDFBox PDFRenderer → PNG
    │   ├── TextLayerProbe.java         # per-page text_quality 0|1|2
    │   ├── ColPaliClient.java          # HTTP client for the Python sidecar
    │   ├── PageImageStore.java         # interface for out-of-Qdrant PNG storage
    │   ├── FilesystemPageImageStore.java   # v1 impl
    │   ├── UuidV5.java                 # deterministic point IDs
    │   ├── Chunk.java
    │   └── fusion/
    │       ├── FusionStrategy.java     # interface
    │       ├── RrfFusion.java          # default
    │       ├── WeightedScoreFusion.java
    │       ├── FusionConfig.java
    │       ├── ConfidenceCalculator.java
    │       └── FusionEngine.java       # resolves mode + dispatches + annotates
    └── openwebui/
        ├── OpenWebUiBackend.java       # implements Backend
        ├── OpenWebUiClient.java        # REST: /knowledge, /files, multipart upload
        ├── KnowledgeService.java       # find-or-create KB
        ├── FileUploadService.java      # upload + poll until processed
        ├── OpenWebUiException.java
        └── dto/
```

`IngestTools` exposes seven `@Tool` methods, all `@Blocking`:
`ingest_document`, `search_documents`, `list_knowledge_bases`,
`get_file_status` (Open WebUI), `inspect_page` (Qdrant visual),
`get_ingest_status` (async queue), `drop_visual_index` (admin).

`IngestService.ingest()` / `.search()` pick a `Backend` by `req.backend()`
or the configured default (`ingest.backend.default`, env `INGEST_BACKEND`),
then delegate.

### Qdrant ingest pipeline

`QdrantBackend.ingest`:

1. `FileFetcher` resolves URL / path / inline → `FetchedFile`.
2. Generate `docId = UUID.randomUUID()`.
3. Validate mode consistency (KB has visual index? request has visual? must match).
4. Pre-flight sidecar health check if visual requested → hard-fail if down.
5. **Always:** `ChunkPipeline.ingestChunks(req, file, docId)` →
   `TextExtractor.extractPerPage` → `Chunker.chunkPerPage` → `Embedder.embed`
   → `QdrantClient.upsertPoints` to `<kb>` collection.
6. **If `enable_visual_index=true`:** `ColPaliPipeline.ingestPages(req, file, docId)`
   → `PageRasterizer.renderAll` → `TextLayerProbe.probe` →
   `PageImageStore.store` → `ColPaliClient.embedPages` →
   `QdrantClient.upsertMultivectorPoints` to `<kb>_pages` collection
   (named vectors: `original` + `pooled_rows` + `pooled_cols`, MAX_SIM
   comparator, binary quantization on `original`).

### Sync vs async routing

After step 4 (sidecar health check) and before step 5, `QdrantBackend.ingest`
calls `shouldQueue(file, visualRequested)`:

- **Sync** (steps 5+6 run immediately, returns full `IngestResult`) if any of:
  text-only ingest, non-PDF file, PDF below `ingest.queue.sync_threshold_pages`
  (default 20).
- **Queue** (returns `IngestResult.queued{jobId, "queued", 0 chunks, 0 pages}`)
  otherwise.

Queued jobs persist to `${INGEST_QUEUE_PATH}/<jobId>.json` and are drained by
the `IngestWorker` thread pool (`ingest.queue.worker_threads`, default 1).
The worker re-fetches the file from the persisted request, runs steps 5+6
via `QdrantBackend.ingestForWorker`, and writes the result back to the queue.
The agent polls `get_ingest_status(job_id)` to track progress.

At-least-once on restart: any `IN_PROGRESS` job at startup is requeued
(`retryCount++`) up to `ingest.queue.max_retries` (default 3). See
[docs/components/ingest-queue.md](docs/components/ingest-queue.md).

### Qdrant search pipeline (fusion)

`QdrantBackend.search` → `FusionEngine.search`:

1. Resolve `retrieval_mode` (auto / fusion / text_only / colpali_only) against
   KB capability. Apply fallback matrix, collect warnings.
2. Run the right pipelines (text + visual for fusion; just one for the others).
3. Pre-fusion list sizes: `nText = 4 × top_k`, `nPages = 2 × top_k`.
4. Apply `FusionStrategy` (`RrfFusion` default, `WeightedScoreFusion`
   alternative). Joins chunks to pages via `(docId, pageStart..pageEnd)`.
5. Annotate via `ConfidenceCalculator`:
   `0.4 × text * text_trust + 0.4 × visual + 0.2 × agreement`,
   bucketed high (>0.7) / medium (>0.4) / low. Response confidence = max of
   per-hit. `text_trust = text_quality / 2.0` from the chunk's source page.

### Open WebUI pipeline

Unchanged. `OpenWebUiBackend.ingest`: find-or-create KB → multipart upload
→ poll `/files/{id}/process/status` until completed → `POST
/knowledge/{id}/file/add`. `OpenWebUiBackend.search()` throws unsupported.

### Python sidecar

`sidecar/` — separate Python project. FastAPI service exposing the contract
`ColPaliClient` consumes: `/healthz`, `/info`, `/embed_pages`, `/embed_query`.
Runs ColPali / ColQwen2 / ColSmolVLM / ColFlor via the `colpali-engine`
library. Model name is configurable (`COLPALI_MODEL`). The Java side stays
model-agnostic via `/info`.

## Gotchas (non-obvious, will bite you)

- **`HttpClient` must be pinned to HTTP/1.1.** `OpenWebUiClient.init()`,
  `FileFetcher.init()`, `Embedder.init()`, `ColPaliClient.init()`,
  `QdrantClient.init()` all call `.version(HTTP_1_1)`. Without this,
  `java.net.http` sends a cleartext h2c upgrade trio that uvicorn (sidecar +
  Open WebUI) and llama-server both reject.

- **Open WebUI async processing race.** `POST /api/v1/files/` returns
  immediately; content extraction + embedding happen in the background.
  Don't `/file/add` until status reports `completed`.
  `FileUploadService.waitUntilProcessed` is the guard.

- **Qdrant collection dim is immutable.** Switching `EMBED_MODEL` or
  `COLPALI_MODEL` without re-creating collections fails loudly at
  `ensureCollection` time.

- **Mode mismatch on existing KB is hard-rejected.** Ingesting
  `enable_visual_index=false` into a visual-enabled KB (or vice versa)
  throws with a clear "create a new KB" message. No `force_mode_change` in v1.

- **Sidecar-down asymmetry.** Ingest with visual requested + sidecar down →
  hard-fail. Search with visual-enabled KB + sidecar down → soft-degrade to
  `text_only_fallback` with a warning.

- **Cross-module CDI discovery needs a Jandex index.** `core/pom.xml` runs
  `jandex-maven-plugin`, and each transport module's `application.properties`
  has `quarkus.index-dependency.core.*`. Without both, `tools/list` returns `[]`.

- **stdio: stdout is sacred.** It carries JSON-RPC framing only.
  `server-stdio/application.properties` routes logs to stderr and disables
  the banner. Never `System.out.println` from `core/`.

- **`/api/v1/knowledge/` returns `{items, total}`**, not a bare array. Open
  WebUI's real shape drifts from its docs. `KnowledgePage` wraps it.

- **Point IDs are UUID v5, deterministic.**
  `UuidV5.forChunk(docId, chunkIndex)` and `UuidV5.forPage(docId, pageNumber)`
  produce identical IDs given identical inputs. Same doc + same chunking →
  idempotent overwrite; different chunking → duplicate copy. No dedupe by
  source URL.

- **`<kb>_pages` is the visual-index capability flag.** Implicit state.
  `ColPaliPipeline.isEnabledFor(kbName)` calls `qdrant.getCollection(<kb>_pages)
  != null`. No separate metadata store.

## Configuration

Env vars (consumed via `@ConfigProperty`, see
`core/src/main/resources/application.properties`):

| Var | Purpose | Default |
|---|---|---|
| `INGEST_BACKEND` | `qdrant` or `openwebui` (when tool omits `backend`) | `qdrant` |
| `INGEST_DEFAULT_VISUAL_INDEX` | per-KB visual default (when tool omits `enable_visual_index`) | `true` |
| `RETRIEVAL_MODE` | search default | `auto` |
| `FUSION_STRATEGY` | `rrf` / `weighted` | `rrf` |
| `QDRANT_URL` | Qdrant REST root | `http://localhost:6333` |
| `QDRANT_API_KEY` | `api-key` header (Qdrant Cloud) | *(empty)* |
| `EMBED_BASE_URL` | OpenAI-compatible embeddings root | `http://localhost:8081/v1` |
| `EMBED_API_KEY` | `Authorization: Bearer …` | *(empty)* |
| `EMBED_MODEL` | model name | `bge-large-en-v1.5` |
| `EMBED_BATCH_SIZE` | batch size per `/embeddings` | `64` |
| `INGEST_CHUNK_SIZE_CHARS` | chunk size in characters | `1500` |
| `INGEST_CHUNK_OVERLAP_CHARS` | adjacent-chunk overlap | `200` |
| `COLPALI_SIDECAR_URL` | sidecar root | `http://localhost:8090` |
| `COLPALI_BATCH_SIZE` | client-side batch for `/embed_pages` | `8` |
| `INGEST_PAGE_STORE_IMPL` | `filesystem` (only v1) | `filesystem` |
| `INGEST_PAGE_STORE_ROOT` | filesystem root for PNGs | `${user.home}/.pdf-rag-ingest/page-images` |
| `OPEN_WEBUI_BASE_URL` | (legacy backend) | `http://localhost:3000` |
| `OPEN_WEBUI_API_KEY` | (legacy) Bearer token | *(empty)* |
| `PORT` | server-http port | `8080` |
| `MCP_CORS_ORIGINS` | CORS allow-list (Streamable HTTP) | `*` |

Many more tunables (poll backoffs, fusion weights, confidence thresholds,
text_quality thresholds, etc.) are `ingest.*` keys in the same file. Full
list in [docs/architecture.md](docs/architecture.md).

## Qdrant REST contract (used by `QdrantClient`)

| Method | Path | Use |
|---|---|---|
| GET | `/collections` | list |
| GET | `/collections/{name}` | get (returns 404 → null) |
| PUT | `/collections/{name}` | create (single-vector or multivector named) |
| DELETE | `/collections/{name}` | delete (idempotent on 404) |
| PUT | `/collections/{name}/points?wait=true` | upsert (single or multivector) |
| POST | `/collections/{name}/points/search` | single-vector ANN search |
| POST | `/collections/{name}/points/query` | multistage prefetch+rerank query |

## ColPali sidecar contract (used by `ColPaliClient`)

| Method | Path | Use |
|---|---|---|
| GET | `/healthz` | liveness; reports `ready: bool` |
| GET | `/info` | self-report (model_name, vector_dim, batch_size, device) |
| POST | `/embed_pages` | embed page images (base64 PNG → multivectors) |
| POST | `/embed_query` | embed query string → multi-token vectors |

## Smoke-testing

stdio: pipe JSON-RPC frames into the jar (`initialize` →
`notifications/initialized` → `tools/call`). Logs on stderr, JSON-RPC on
stdout. `list_knowledge_bases` is the cheapest auth+wiring check.
`ingest_document` with a small PDF URL exercises the whole pipeline
(extraction, embedding, optional visual side via sidecar, Qdrant collection
creation, upsert).

HTTP: `docker compose up -d`, then point an MCP client at
`http://localhost:8080/mcp`.

## Component walkthroughs

Detailed per-class docs in `docs/components/` — start with
[docs/components/README.md](docs/components/README.md) for the index and
data-flow diagram. The most consequential new pieces:

- `docs/components/fusion-engine.md` — RRF + weighted strategies + confidence
- `docs/components/colpali-pipeline.md` — visual ingest + search orchestration
- `docs/components/colpali-sidecar.md` — the Python service
- `docs/components/qdrant-backend.md` — the orchestrator pattern
- `docs/components/qdrant-client.md` — multivector + multistage REST shapes
