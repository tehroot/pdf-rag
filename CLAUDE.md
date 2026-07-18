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

# Whole stack via Docker. A committed docker-compose.override.yml symlink →
# docker-compose.gpu.yml is auto-loaded, so plain compose is GPU-by-default on
# an NVIDIA host. On a CPU host, bypass the override with an explicit base file.
docker compose up -d                       # GPU host: qdrant + llama + colpali(cuda) + pdf-rag-http
docker compose -f docker-compose.yml up -d # CPU host: ignores the GPU override

# Dev-pipeline wrappers (scripts/, see scripts/README.md) — handle .env, the
# GPU overlay, model download, and health checks for you:
scripts/bootstrap.sh                       # one-time: .env + embedding model + ./incoming
scripts/up.sh [--gpu]                      # start the stack (CPU, or CUDA sidecar)
scripts/status.sh / logs.sh / down.sh      # health probes / logs / teardown
scripts/build-images.sh [--gpu]            # docker compose build (no local Maven needed)
scripts/test.sh [--core|--full|--sidecar]  # run test suites
scripts/smoke.sh                           # end-to-end wiring check against a running stack
scripts/pipeline.sh [--gpu]                # full loop: test → build images → up → smoke
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

One exception: `server-http` also hosts a plain JAX-RS REST surface
(`server-http/src/main/java/org/hayden/rest/`, `quarkus-rest-jackson`
dependency) for the directory-ingest endpoint, served on the same port as
`/mcp`. The *logic* stays backend-agnostic in `core`
(`DirectoryIngestService`); only the HTTP binding is in `server-http` (stdio
has no REST). See [docs/components/directory-ingest.md](docs/components/directory-ingest.md).

## Architecture

```
core/src/main/java/org/hayden/
├── tools/IngestTools.java              # MCP @Tool surface (7 tools)
├── ingest/
│   ├── IngestService.java              # dispatcher: picks Backend by arg / default; ingest(req) + ingest(req, explicitDocId)
│   ├── IngestRequest / SearchRequest   # tool input records
│   ├── IngestResult / SearchResponse / SearchHit / InspectPageResult / PageText / DropVisualIndexResult
│   ├── DirectoryIngestService.java     # scan a dir → per-file ingest (REST endpoint backs onto this)
│   ├── DirectoryIngestRequest / DirectoryIngestResponse / DirectoryFileOutcome
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
    │   ├── StructuredExtractor.java    # block extraction: Tika XHTML / PDF font heuristics
    │   ├── StructuralChunker.java      # packs Blocks into chunks + heading breadcrumbs
    │   ├── Block.java                  # HEADING/PARAGRAPH/LIST/TABLE + headingPath
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
    │       ├── ResultDeduper.java      # collapses overlapping chunks in results
    │       └── FusionEngine.java       # resolves mode + dispatches + annotates
    └── openwebui/
        ├── OpenWebUiBackend.java       # implements Backend
        ├── OpenWebUiClient.java        # REST: /knowledge, /files, multipart upload
        ├── KnowledgeService.java       # find-or-create KB
        ├── FileUploadService.java      # upload + poll until processed
        ├── OpenWebUiException.java
        └── dto/
```

`IngestTools` exposes eight `@Tool` methods, all `@Blocking`:
`ingest_document`, `search_documents`, `list_knowledge_bases`,
`delete_document` (Qdrant; by doc_id), `get_file_status` (Open WebUI),
`inspect_page` (Qdrant visual), `get_ingest_status` (async queue),
`drop_visual_index` (admin).

`server-http` additionally exposes a plain REST surface (`org.hayden.rest.*`,
`quarkus-rest-jackson`) for bulk/operational use: `POST /ingest/directory`,
`GET /ingest/status/{jobId}`, `GET /ingest/jobs` (list, `?status=` filter),
`DELETE /ingest/document` (by `doc_id` or `source_path`), plus KB status on
`GET /kb` (listing with per-KB + total distinct-document counts via the
Qdrant facet API) and `GET /kb/{name}`. Logic is in `core`
(`DirectoryIngestService`, `IngestService`); see
[docs/components/directory-ingest.md](docs/components/directory-ingest.md).
In the Docker deployment, paths in `POST /ingest/directory` resolve *inside
the container*: the `./incoming` inbox is at `/docs` (`INGEST_INBOX`) and the
host's `$HOME` at `/host` (`INGEST_HOST_ROOT`, compose-level var — set `/` on
Linux for the whole host FS), both read-only.

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
   → `QdrantClient.upsertPoints` to `<kb>` collection. With
   `ingest.chunk.strategy=structural` the extract+chunk steps become
   `StructuredExtractor.extractBlocks` → `StructuralChunker.chunkBlocks`
   (heading-aware packing; embedded text gets a heading-breadcrumb prefix via
   `Chunk.embeddingText()`, stored `text` stays clean, payload gains
   `heading_path`); falls back to sliding per-file on extraction failure.
   Payload indexes (doc_id, filename, chunk_index, page_start, page_end) are
   ensured idempotently on every ingest.
6. **If `enable_visual_index=true`:** `ColPaliPipeline.ingestPages(req, file, docId)`
   → `PageRasterizer.renderAll` → `TextLayerProbe.probe` →
   `PageImageStore.store` → `ColPaliClient.embedPages` →
   `QdrantClient.upsertMultivectorPoints` to `<kb>_pages` collection
   (named vectors: `original` + `pooled_rows` + `pooled_cols`, MAX_SIM
   comparator, binary quantization on `original`).

### Sync vs async routing (split visual ingest)

After step 4 (sidecar health check) and before step 5, `QdrantBackend.ingest`
calls `shouldQueue(file, visualRequested)`:

- **Fully sync** (steps 5+6 run immediately, returns full `IngestResult`) if
  any of: text-only ingest, non-PDF file, PDF below
  `ingest.queue.sync_threshold_pages` (default 20).
- **Split** otherwise: `<kb>_pages` is created eagerly (dim from sidecar
  `/info` — keeps the visual-capability flag truthful for mode validation
  while jobs drain), step 5 (text) runs **synchronously**, and only step 6
  (visual) is queued as a `JobKind.VISUAL` job. Returns
  `IngestResult.queuedVisual{jobId, "queued", N chunks, 0 pages}` — the doc
  is text-searchable immediately; the queue is a pure VLM lane (continuous
  GPU work, no Tika/bge gaps). Chunks and pages join at search time via the
  shared docId, so no ingest-time link is needed.

Queued jobs persist to `${INGEST_QUEUE_PATH}/<jobId>.json` and are drained by
the `IngestWorker` thread pool (`ingest.queue.worker_threads`, default 1;
`2` overlaps one worker's rasterizing with another's GPU embedding — the GPU
compose overlay defaults to 2). The
worker re-fetches the file from the persisted request and dispatches on
`job.effectiveKind()`: `VISUAL` → pages only; `FULL` (legacy jobs persisted
before the split, `kind == null`) → both pipelines as before. The agent polls
`get_ingest_status(job_id)` to track progress.

At-least-once on restart: any `IN_PROGRESS` job at startup is requeued
(`retryCount++`) up to `ingest.queue.max_retries` (default 3). See
[docs/components/ingest-queue.md](docs/components/ingest-queue.md) and
[docs/plans/split-visual-ingest-v1.md](docs/plans/split-visual-ingest-v1.md).

### Qdrant search pipeline (fusion)

`QdrantBackend.search` → `FusionEngine.search`:

1. Resolve `retrieval_mode` (auto / fusion / text_only / colpali_only) against
   KB capability. Apply fallback matrix, collect warnings.
2. Run the right pipelines (text + visual for fusion; just one for the others).
3. Pre-fusion list sizes: `nText = 4 × top_k`, `nPages = 2 × top_k`.
4. Apply `FusionStrategy` (`RrfFusion` default, `WeightedScoreFusion`
   alternative). Joins chunks to pages via `(docId, pageStart..pageEnd)`.
   Strategies return `top_k × ingest.search.dedup.headroom` hits;
   `ResultDeduper` then collapses overlapping chunks of the same doc
   (char-range overlap when offsets present, chunk-index adjacency otherwise)
   back down to `top_k`, backfilling from deeper candidates.
   Set `INGEST_SEARCH_DEBUG_CANDIDATES=true` to log pre-fusion candidate
   lists + post-fusion scores at INFO on every search (the retrieval-accuracy
   debugging substrate; see docs/eval/retrieval-eval.md).
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

- **Point IDs are UUID v5, deterministic — and docId is random EXCEPT for
  directory ingest.** `UuidV5.forChunk(docId, chunkIndex)` /
  `forPage(docId, pageNumber)` produce identical IDs given identical inputs.
  The MCP `ingest_document` path uses `QdrantBackend.ingest(req)` → a fresh
  `docId = UUID.randomUUID()` every call, so re-ingesting the same file ALWAYS
  creates new points and the old copy's chunks remain. The **directory-ingest**
  path instead supplies a deterministic `docId = UuidV5.forSource(kb, absPath)`
  via `ingest(req, explicitDocId)`, so re-scanning a directory overwrites each
  file's points in place (idempotent). `doIngest` **deletes the docId's prior
  points before writing** (`chunks.deleteDoc` / `pages.deleteDoc`), so a changed
  file that yields fewer chunks leaves no stale tail; on the random-docId MCP
  path that delete matches nothing (a cheap no-op — the old copy under the
  previous random id still remains). Before/after chunking comparisons still
  want fresh KBs (see docs/eval/retrieval-eval.md); no dedupe by source URL.

- **`<kb>_pages` is the visual-index capability flag.** Implicit state.
  `ColPaliPipeline.isEnabledFor(kbName)` calls `qdrant.getCollection(<kb>_pages)
  != null`. No separate metadata store.

- **Multivector upserts MUST stay batched small.** A ColQwen2-class page point
  is ~1.5–2 MB as JSON (original + pooled multivectors) and Qdrant rejects
  request bodies over its ~32 MB cap — an unbatched multi-page upsert fails
  with an I/O error *after* all render/embed work is spent (this killed 100+
  real visual jobs before `ingest.qdrant.multivector-upsert-batch-size`
  existed). The text side's `INGEST_QDRANT_UPSERT_BATCH=128` is tuned for
  ~8 KB chunk points; never reuse it for pages.

- **Concurrent ingests race on collection creation.** Parallel directory
  ingest and multi-worker visual queues can both GET-404 then PUT-create the
  same collection; `ensureCollection`/`ensureMultivectorCollection` tolerate
  the loser's conflict by re-reading and validating. Don't "simplify" that
  try/catch away.

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
| `INGEST_CHUNK_SIZE_CHARS` | chunk size in characters (compose defaults it to `700` — bge's 512-token cap) | `1500` |
| `INGEST_CHUNK_OVERLAP_CHARS` | adjacent-chunk overlap | `200` |
| `INGEST_CHUNK_STRATEGY` | `sliding` or `structural` (heading-aware + breadcrumbs) | `sliding` |
| `INGEST_CHUNK_HEADING_FONT_RATIO` | PDF heading threshold vs body font | `1.15` |
| `INGEST_CHUNK_BREADCRUMB_MAX_CHARS` | cap on breadcrumb prefix in embedded text | `120` |
| `INGEST_QDRANT_UPSERT_BATCH` | chunk points per Qdrant upsert call (text side) | `128` |
| `INGEST_QDRANT_MULTIVECTOR_UPSERT_BATCH` | page points per multivector upsert (visual side; see gotcha) | `8` |
| `INGEST_DIRECTORY_PARALLELISM` | files ingested concurrently per `POST /ingest/directory` | `4` |
| `COLPALI_PREFETCH_MULTIPLIER` | multistage prefetch = N × top_k | `10` |
| `INGEST_SEARCH_DEBUG_CANDIDATES` | log candidate lists + scores at INFO | `false` |
| `INGEST_SEARCH_DEDUP` | collapse overlapping chunks in results | `true` |
| `COLPALI_SIDECAR_URL` | sidecar root | `http://localhost:8090` |
| `COLPALI_BATCH_SIZE` | client-side batch for `/embed_pages` | `8` |
| `INGEST_PAGE_STORE_IMPL` | `filesystem` (only v1) | `filesystem` |
| `INGEST_PAGE_STORE_ROOT` | filesystem root for PNGs | `${user.home}/.pdf-rag-ingest/page-images` |
| `OPEN_WEBUI_BASE_URL` | (legacy backend) | `http://localhost:3000` |
| `OPEN_WEBUI_API_KEY` | (legacy) Bearer token | *(empty)* |
| `PORT` | server-http port | `8080` |
| `MCP_CORS_ORIGINS` | CORS allow-list (Streamable HTTP) | `*` |
| `SWAGGER_UI_ALWAYS_INCLUDE` | serve Swagger UI on the built server-http, not just dev (build-time; `/q/openapi` schema is always served) | `true` |

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
| PUT | `/collections/{name}/index?wait=true` | create payload index (idempotent via payload_schema diff) |
| PUT | `/collections/{name}/points?wait=true` | upsert (single or multivector) |
| POST | `/collections/{name}/points/delete?wait=true` | delete points by `doc_id` filter |
| POST | `/collections/{name}/points/search` | single-vector ANN search |
| POST | `/collections/{name}/points/query` | multistage prefetch+rerank query |
| POST | `/collections/{name}/facet` | distinct doc_id count (NOT under `/points` — verified live) |

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

HTTP: `docker compose up -d` (GPU host; on a CPU host use `docker compose -f
docker-compose.yml up -d` to skip the GPU override), then point an MCP client
at `http://localhost:8080/mcp`. The same port also serves the REST surface
(`/ingest/*`) and its OpenAPI docs — Swagger UI at
`http://localhost:8080/q/swagger-ui`, schema at `http://localhost:8080/q/openapi`
— which double as a quick "is the REST layer up?" check.

## Component walkthroughs

Detailed per-class docs in `docs/components/` — start with
[docs/components/README.md](docs/components/README.md) for the index and
data-flow diagram. The most consequential new pieces:

- `docs/components/fusion-engine.md` — RRF + weighted strategies + confidence
- `docs/components/colpali-pipeline.md` — visual ingest + search orchestration
- `docs/components/colpali-sidecar.md` — the Python service
- `docs/components/qdrant-backend.md` — the orchestrator pattern
- `docs/components/qdrant-client.md` — multivector + multistage REST shapes
