# Plan: ColPali + text-RAG fusion (v1)

Status: design converged, ready to implement
Author drafted: 2026-05-20

## Context

Today's `QdrantBackend` pipeline is: PDF → Tika+Tesseract → Chunker → Embedder (llama-server, `bge-large-en-v1.5`) → Qdrant single-vector cosine. Works well for clean born-digital PDFs; degrades on scanned documents where OCR is messy and where spatial/structural document organization carries semantic signal that flat text loses.

v1 adds a parallel **page-as-image** pipeline via ColPali-family vision models, kept behind a model-agnostic HTTP sidecar, and fuses its retrieval with the existing text pipeline. Text-RAG remains the *robust backbone* — always present, fast on CPU, the default fallback for every degradation path. ColPali is a *quality enhancement* that can be enabled/disabled per-KB and per-query.

## Goal

An MCP server in which:

1. Every ingest produces a text-vector index (today's behavior, unchanged).
2. If visual indexing is enabled for the KB (default true, env-overridable), the same ingest also produces a ColPali multi-vector page index in a parallel Qdrant collection.
3. Search defaults to `retrieval_mode=auto`: fuse text and visual when both are available, fall back to text-only when they aren't.
4. Per-call `retrieval_mode` lets the agent force `text_only`, `colpali_only`, or `fusion` explicitly.
5. Fusion is pluggable (`FusionStrategy` interface) with RRF as the default; experimentation with weighted/learned strategies happens at this seam without touching the rest of the system.
6. Per-page `text_quality` (from a PDFBox text-layer probe) gates Tesseract invocation and weights fusion confidence.
7. Each result carries per-hit and response-level `confidence` (high/medium/low) computed from text score, visual score, page text_quality, and pipeline agreement.
8. Ingest is async-capable: ColPali ingest can take minutes per document on CPU-only deployments, so calls return job IDs and the agent polls `get_ingest_status`.
9. A new `inspect_page` tool returns the rendered page image (base64 PNG) so multimodal agents can fall back to direct visual reading when text is suspect.
10. ColPali model + backend (HF Transformers, ONNX-INT8, llama.cpp-GGUF) are an operator deployment choice, not a code change. The sidecar contract is the only thing the Java side knows about.

## Out of scope for v1

- Source-byte caching / document catalog (no retroactive backfill of existing KBs to ColPali; operator must create new KB or wait for v2)
- Per-document mode override (per-page text_quality routing covers it)
- Learned-reranker fusion strategies (the seam is there; we ship RRF and weighted-score; cross-encoder rerank is vNext)
- A/B comparison tool (`compare_retrieval_modes`)
- Live tuning UI for fusion weights
- ColPali-only KB mode at the Backend level (it's a retrieval_mode value; text vectors still get built at ingest if enabled)

## Architecture

```
                 ingest                                  query
              ┌─────────────┐                  ┌────────────────────┐
PDF bytes →   │   PDFBox    │           query → │ Embedder           │
              │  per-page   │                  │ (text vector)      │
              │     ↓       │                  │      +             │
              │ TextLayer-  │                  │ ColPaliClient      │
              │  Probe      │                  │ → sidecar          │
              │   ↓ (gates) │                  └────────┬───────────┘
              │ Tika+       │                           │
              │ Tesseract   │                  ┌────────▼───────────┐
              │   ↓ text    │                  │   ChunkPipeline    │ ← text vector
              │   per page  │                  │  text search       │
              │ Chunker     │  → store         │  (cosine, N_text)  │
              │ (page-      │                  └────────┬───────────┘
              │  tagged)    │                           │
              │   ↓         │                  ┌────────▼───────────┐
              │ Embedder    │                  │   ColPaliPipeline  │ ← ColPali vec
              │   ↓ vectors │                  │  page search       │
              └─────────────┘                  │  prefetch+rerank   │
                                               └────────┬───────────┘
              ┌─────────────┐                           │
PDF bytes →   │   PDFBox    │                  ┌────────▼───────────┐
              │  renderer   │                  │   FusionEngine     │
              │   ↓ pages   │                  │  (RrfFusion default)│
              │ ColPali-    │  → store         │   +                │
              │ Client →    │                  │  Confidence-       │
              │ sidecar     │                  │  Calculator        │
              │ → MV per pg │                  └────────┬───────────┘
              └─────────────┘                           │
                                                        ▼
                                              top-K fused hits with
                                              per-hit and response-level
                                              confidence
```

### Backend orchestration (existing `QdrantBackend`, refactored)

```java
@ApplicationScoped
public class QdrantBackend implements Backend {
    @Inject FileFetcher fetcher;
    @Inject ChunkPipeline chunks;            // refactored from today's QdrantBackend
    @Inject ColPaliPipeline pages;           // new, optional
    @Inject FusionEngine fusion;
    @Inject IngestQueue queue;

    public IngestResult ingest(IngestRequest req) {
        if (req.shouldQueue()) return queue.submit(req);
        return doIngest(req);
    }

    IngestResult doIngest(IngestRequest req) {
        FetchedFile file = fetcher.fetch(req);
        chunks.ingestChunks(req, file);
        if (req.visualIndexEnabled()) {
            pages.ingestPages(req, file);
        }
        return composeResult(...);
    }

    public SearchResponse search(SearchRequest req) {
        ResolvedMode mode = resolveMode(req);
        List<ChunkHit> chunkHits = mode.usesText() ? chunks.searchChunks(req) : List.of();
        List<PageHit> pageHits   = mode.usesVisual() ? pages.searchPages(req) : List.of();
        return fusion.fuse(req, mode, chunkHits, pageHits);
    }
}
```

## Components

### New Java classes (in `core/src/main/java/org/hayden/`)

| File | Purpose |
|------|---------|
| `backend/qdrant/PageRasterizer.java` | PDFBox `PDFRenderer` at configurable DPI (default 150) → PNG bytes per page. |
| `backend/qdrant/TextLayerProbe.java` | Per-page PDFBox text extraction (no OCR); classifies `text_quality: 0\|1\|2`. |
| `backend/qdrant/ColPaliClient.java` | HTTP client for the sidecar. `GET /info`, `POST /embed_pages`, `POST /embed_query`, `GET /healthz`. java.net.http + Jackson, HTTP/1.1 pinned. |
| `backend/qdrant/ColPaliPipeline.java` | Owns `<kb>_pages` collection. `ingestPages(req, file)`, `searchPages(req)`, `listPageCollections()`, `dropVisualIndex(kbName)`, `getRenderedPage(...)`. Reads/writes page images via `PageImageStore`. |
| `backend/qdrant/PageImageStore.java` | Interface for storing rendered page PNGs outside Qdrant. Methods: `store(kbName, docId, pageNumber, pngBytes) → key`, `retrieve(key) → pngBytes`, `deleteForKb(kbName)`, `deleteForDoc(kbName, docId)`. |
| `backend/qdrant/FilesystemPageImageStore.java` | v1 implementation. Layout: `${root}/<kb>/<doc_id>/<NNNNNN>.png`. Configurable root via `ingest.page_store.root`. |
| `backend/qdrant/ChunkPipeline.java` | Extracted from today's `QdrantBackend`. Owns `<kb>` chunks collection. Method signatures: `ingestChunks(req, file)`, `searchChunks(req)`, `lookupChunksByPage(docId, pageNumbers)`. |
| `backend/qdrant/fusion/FusionStrategy.java` | Interface: `List<FusedHit> fuse(List<ChunkHit>, List<PageHit>, Config)`. |
| `backend/qdrant/fusion/RrfFusion.java` | Default. `k_rrf=60`. |
| `backend/qdrant/fusion/WeightedScoreFusion.java` | Alternative. Linear combination after empirical normalization. |
| `backend/qdrant/FusionEngine.java` | Resolves `retrieval_mode`, calls the chosen `FusionStrategy`, attaches confidence. |
| `backend/qdrant/ConfidenceCalculator.java` | Per-hit + response-level confidence computation. Heuristic in v1 with env-overridable weights. |
| `jobs/IngestQueue.java` | In-memory queue with persistent shadow log to survive restarts (file-backed or Qdrant-payload-backed). |
| `jobs/IngestJob.java` | Record: `jobId, status, kbName, sourceValue, submittedAt, startedAt, completedAt, result, warnings`. |
| `jobs/IngestWorker.java` | `@ApplicationScoped @Startup` background worker thread. |

### Modified Java classes

| File | Change |
|------|--------|
| `backend/qdrant/QdrantBackend.java` | Refactored to thin orchestrator (above). |
| `backend/qdrant/QdrantClient.java` | Add multivector collection ops (named vectors with `MAX_SIM` comparator, HNSW disable per vector, optional named vectors, prefetch+rerank query shape). |
| `ingest/Chunker.java` + `Chunk.java` | `Chunk` record gains `pageStart`, `pageEnd`. Chunker accepts per-page text and tags chunks accordingly. |
| `ingest/IngestRequest.java` | Add `enableVisualIndex: Boolean`, `retrievalMode: String`, `asyncOverride: Boolean`. |
| `ingest/IngestResult.java` | Add `jobId: String?`, `warnings: List<String>`. |
| `ingest/SearchRequest.java` | Add `retrievalMode: String`, `fusionStrategy: String?` (operator override). |
| `ingest/SearchResponse.java` + `SearchHit.java` | Add `fusionMode`, `confidence` (response-level), per-hit `confidence`, `pageNumber`, `pageScore`. |
| `ingest/FileFetcher.java` | Add `fromPdfPerPage(...)` helper that uses PDFBox to extract per-page bytes and text. |
| `tools/IngestTools.java` | New tools (below), updated args on existing tools. |

### Tools (final surface)

| Tool | Args | Returns |
|------|------|---------|
| `ingest_document` | `source_type`, `source_value`, `kb_name`, `filename?`, `kb_description?`, `enable_visual_index? (default INGEST_DEFAULT_VISUAL_INDEX)`, `metadata?` | `IngestResult` (sync) OR `{jobId, status:"queued", ...}` (async) |
| `search_documents` | `kb_name`, `query`, `top_k?`, `retrieval_mode? (default RETRIEVAL_MODE=auto)`, `filter?`, `fusion_strategy?` | `SearchResponse` with fusion_mode, confidence, hits with per-hit confidence |
| `list_knowledge_bases` | `backend?` | List with `visual_index_enabled: bool`, `visual_index_vectors: long` |
| `inspect_page` | `kb_name`, `doc_id`, `page_number` | `{base64_png, width, height, doc_id, page_number, source}` |
| `get_ingest_status` | `job_id` | `{status, kb_name, doc_id?, chunk_count?, warnings, error?}` |
| `drop_visual_index` | `kb_name`, `confirm: true` | `{dropped: bool, message}` |
| `get_file_status` | `file_id` | Open WebUI backend only — unchanged |

### Data shapes

#### Chunks collection (`<kb>`)

```yaml
vectors:
  text: { size: 1024, distance: Cosine }      # bge-large default

point:
  id: uuid_v5(namespace, doc_id + ":" + chunk_index)
  vector:
    text: [...]
  payload:
    text: "<chunk text>"
    doc_id: <uuid>
    chunk_index: <int>
    page_start: <int>     # 1-indexed
    page_end: <int>       # 1-indexed; equals page_start when chunk doesn't span
    filename: "..."
    source: "..."
    source_type: "url"|"path"|"inline"
    content_type: "..."
    char_start: <int>
    char_end: <int>
    embed_model: "bge-large-en-v1.5"
    text_quality: 0|1|2
    # plus caller-supplied metadata (with putIfAbsent semantics)
```

#### Pages collection (`<kb>_pages`)

```yaml
vectors:
  original:
    size: 128
    distance: Cosine
    multivector_config: { comparator: max_sim }
    hnsw_config: { m: 0 }                     # disabled, rerank-only
    quantization_config: { binary: { always_ram: true } }
  pooled_rows:
    size: 128
    distance: Cosine
    multivector_config: { comparator: max_sim }
  pooled_cols:
    size: 128
    distance: Cosine
    multivector_config: { comparator: max_sim }

point:
  id: uuid_v5(namespace, doc_id + ":" + page_number)
  vectors:
    original: [[...], [...], ...]             # ~1030 vectors × 128d
    pooled_rows: [[...], ...]                 # 32+6 vectors × 128d
    pooled_cols: [[...], ...]                 # 32+6 vectors × 128d
  payload:
    doc_id: <uuid>
    page_number: <int>
    filename: "..."
    source: "..."
    source_type: "..."
    text_quality: 0|1|2
    page_image_key: "<kb>/<doc_id>/<NNNNNN>.png"
    page_image_size_bytes: <int>
    embed_model: "<colpali_model_name>"
    # plus caller-supplied metadata
```

#### `<kb>_pages` rendered-page object storage

Page images are stored **outside Qdrant** via a `PageImageStore` abstraction. v1 ships a filesystem-backed implementation; the abstraction lets us add S3-compatible blob storage (RustFS, MinIO, AWS S3, etc.) later as a drop-in replacement without touching the rest of the codebase.

Qdrant payload stores only the storage key:

```yaml
page_image_key: "kb-name/doc-uuid/000007.png"
```

Decision: corpora will routinely exceed 1000 pages, making in-payload base64 PNGs (~150 KB × N pages) impractical for Qdrant's WAL and snapshotting.

**Page image layout (filesystem v1):**

```
${ingest.page_store.root}/
  <kb_name>/
    <doc_id>/
      000001.png
      000002.png
      ...
      000NNN.png
```

Page numbers zero-padded for filesystem sort sanity. Per-KB directories let `drop_visual_index` safely `rm -rf` a single KB's images without scanning for them.

**S3-compatible (vNext):** same key structure, different store impl. RustFS is the leading candidate for self-hosted; AWS S3 / MinIO / Backblaze B2 are interchangeable.

### Configuration additions

| Key | Default | Purpose |
|-----|---------|---------|
| `ingest.visual_index.default_enabled` (`INGEST_DEFAULT_VISUAL_INDEX`) | `true` | Default for `enable_visual_index` arg. Operator sets `false` for no-sidecar deployments. |
| `ingest.retrieval.default_mode` (`RETRIEVAL_MODE`) | `auto` | Default for `retrieval_mode`: `auto`, `fusion`, `text_only`, `colpali_only`. |
| `ingest.colpali.sidecar_url` (`COLPALI_SIDECAR_URL`) | `http://localhost:8090` | Sidecar root. |
| `ingest.colpali.connect-timeout-seconds` | `10` | |
| `ingest.colpali.request-timeout-seconds` | `300` | Page ingest can be slow on CPU. |
| `ingest.colpali.batch-size` (`COLPALI_BATCH_SIZE`) | `8` | Pages per `/embed_pages` call. |
| `ingest.colpali.render-dpi` | `150` | PDFBox render DPI. |
| `ingest.page_store.impl` (`INGEST_PAGE_STORE_IMPL`) | `filesystem` | `filesystem` (v1) or `s3` (vNext). |
| `ingest.page_store.root` (`INGEST_PAGE_STORE_ROOT`) | `${user.home}/.pdf-rag-ingest/page-images` | Filesystem root for stored page PNGs. |
| `ingest.fusion.strategy` (`FUSION_STRATEGY`) | `rrf` | `rrf` \| `weighted` |
| `ingest.fusion.rrf.k` | `60` | RRF constant. |
| `ingest.fusion.weighted.text` | `0.5` | Weighted-fusion text contribution. |
| `ingest.fusion.weighted.visual` | `0.5` | Weighted-fusion visual contribution. |
| `ingest.search.n_text` | `4 * top_k` | Chunks pulled pre-fusion. |
| `ingest.search.n_pages` | `2 * top_k` | Pages pulled pre-fusion. |
| `ingest.search.colpali_prefetch` | `10 * n_pages` | Internal ColPali prefetch limit. |
| `ingest.text_quality.threshold_low` | `50` | Chars/page below this → text_quality=0. |
| `ingest.text_quality.threshold_full` | `500` | Chars/page above this → text_quality=2. |
| `ingest.confidence.weight_text` | `0.4` | |
| `ingest.confidence.weight_visual` | `0.4` | |
| `ingest.confidence.weight_agreement` | `0.2` | |
| `ingest.confidence.threshold_high` | `0.7` | |
| `ingest.confidence.threshold_medium` | `0.4` | |
| `ingest.queue.sync_threshold_pages` (`INGEST_ASYNC_THRESHOLD_PAGES`) | `20` | Pages below = sync; pages above = queue. |
| `ingest.queue.worker_threads` | `1` | Background ingest workers. |
| `ingest.queue.persistence_path` | `${user.home}/.pdf-rag-ingest/queue/` | Job persistence dir. |

## Sidecar — separate repo (`colpali-server`)

A standalone Python repo. The Java side knows nothing about its internals; only the HTTP contract matters.

### HTTP contract

```
GET /info
  → { "model_name": "vidore/colqwen2-v1.0",
      "vector_dim": 128,
      "supports_pooled": true,
      "pooled_methods": ["rows", "cols"],
      "max_batch_size": 32,
      "device": "cuda:0" | "cpu" }

POST /embed_pages
  body: { "pages": [{"page_id": "...", "image_b64": "..."}, ...],
          "include_pooled": true,
          "include_original": true }
  → { "embeddings": [
        { "page_id": "...",
          "original": [[...], ...],         // ~1030 × 128d
          "pooled_rows": [[...], ...],      // 38 × 128d
          "pooled_cols": [[...], ...] }
      ] }

POST /embed_query
  body: { "query": "..." }
  → { "vectors": [[...], ...] }              // query tokens × 128d

GET /healthz
  → { "status": "ok", "ready": true }
```

### Qdrant version

**Pin to `qdrant/qdrant:v1.13.x`** (exact patch version at implementation time; use the latest stable in that minor line). The features we depend on:

- Multivector with `MAX_SIM` comparator (1.10+)
- Optional / nullable named vectors (1.11+)
- Binary quantization on multivector (1.12+)
- Prefetch + rerank query API (1.10+)
- The combination above is well-tested at 1.13.

`docker-compose.yml` pins the exact tag; if Qdrant ships a breaking change in 1.14+ we test before bumping.

### Reference implementation choices

- Default model: **`vidore/colqwen2-v1.0`** (2B params, runs on NVIDIA A2+ in FP16, runs slowly on CPU)
- Alternate for CPU-only: **`vidore/colsmolvlm-v0.1`** (500M params)
- Default impl: HuggingFace Transformers + PyTorch (most-compatible)
- Performance variants (vNext): ONNX Runtime + INT8 (CPU production), llama.cpp GGUF (when ColPali support stabilizes)
- Framework: FastAPI + Uvicorn (matches the project's house style — same uvicorn that fronts Open WebUI)
- Docker: `Dockerfile.cpu` (Python + Torch CPU) and `Dockerfile.cuda` (Python + Torch CUDA + NVIDIA runtime)

### Sidecar tests

- Pytest against the FastAPI app with a tiny test fixture (one rendered page)
- Smoke test: render → embed → verify response shape
- Health check coverage

The sidecar is a separate work item. This Java repo proceeds with the contract; the sidecar can be built in parallel.

## Sequencing

Each phase produces something runnable. Phase ordering is not strict — Phase 2 (sidecar repo) can start in parallel with Phase 1.

### Phase 1: foundation

1. Add `pageStart` / `pageEnd` to `Chunk` and `Chunker`. Per-page Tika extraction via PDFBox. Tests.
2. Refactor `QdrantBackend` → extract `ChunkPipeline`. No behavior change. Tests still pass.
3. Add `PageRasterizer` (PDFBox renderer). Tests.
4. Add `TextLayerProbe`. Tests.
5. Extend `QdrantClient` with multivector collection ops (collection create with named vectors + `MAX_SIM` + HNSW per-vector config + binary quantization config; multivector upsert; prefetch+rerank query shape). Tests.

### Phase 2: sidecar (new repo, parallel)

6. Bootstrap `colpali-server` Python repo (`pyproject.toml`, FastAPI, Pydantic schemas).
7. Implement model loader (HF Transformers, configurable via env).
8. Implement `/embed_pages` (with row + col pooling).
9. Implement `/embed_query`.
10. Implement `/info`, `/healthz`.
11. Dockerfiles (CPU + CUDA variants).
12. Pytest suite.
13. README + deploy notes.

### Phase 3: ColPali integration in Java

14. `ColPaliClient` (HTTP client to sidecar). Tests with WireMock standing in for the sidecar.
15. `ColPaliPipeline` (orchestrator for the visual side: rasterize → embed → upsert; search via prefetch+rerank). Tests against WireMocked sidecar + Qdrant.
16. Wire `ColPaliPipeline` into `QdrantBackend` as injected collaborator. Update `QdrantBackend.ingest` to call both pipelines when `enable_visual_index=true`. Tests.
17. Update `IngestTools`: add `enable_visual_index` to `ingest_document`, add `inspect_page` tool. Tests via the tool surface.

### Phase 4: fusion

18. `FusionStrategy` interface.
19. `RrfFusion` (default).
20. `WeightedScoreFusion` (alternative).
21. `FusionEngine` (resolves `retrieval_mode`, picks strategy, calls it).
22. `ConfidenceCalculator`.
23. Update `SearchResponse` and `SearchHit` shapes.
24. Update `IngestTools.search_documents` to include `retrieval_mode` arg.
25. Tests: fusion correctness, edge cases (empty pipeline results, ties, dual uncertainty), confidence buckets.

### Phase 5: async ingest

26. `IngestJob`, `IngestQueue`, `IngestWorker`.
27. Sync-vs-queue routing in `QdrantBackend.ingest()`.
28. New tool `get_ingest_status(job_id)`.
29. Queue persistence (survive restarts).
30. Tests: queue ordering, restart recovery, sync threshold.

### Phase 6: admin and ops

31. `drop_visual_index` tool.
32. Update `list_knowledge_bases` to include `visual_index_enabled` and `visual_index_vectors`.
33. Health-check endpoint on server-http (`/q/health` already exists via Quarkus; verify it covers sidecar reachability).
34. Tests.

### Phase 7: documentation

35. Update `CLAUDE.md` with the new architecture, new gotchas, new tools.
36. Update `docs/README.md`, `docs/architecture.md`, `docs/deployment.md`, `docs/mcp-integration.md`.
37. Update existing component walkthroughs: `qdrant-backend.md`, `qdrant-client.md`, `chunker.md`, `mcp-tools.md`.
38. New component walkthroughs: `colpali-pipeline.md`, `page-rasterizer.md`, `text-layer-probe.md`, `fusion-engine.md`, `ingest-queue.md`.
39. New top-level doc: `docs/colpali-sidecar.md` (sidecar contract, deployment, model-pick guide for hardware).
40. New research doc: `docs/research/colpali.md` (the research write-up from our discussion; sources, design rationale).

### Phase 8: end-to-end verification

41. `mvn package` green; full test suite passes.
42. Spin up Qdrant + llama-server + colpali-server (Docker compose) locally.
43. Manual smoke against a real PDF mix (clean born-digital + scanned).
44. Verify: ingest sync/async paths, all four `retrieval_mode` values, fallback chain, sidecar-down behavior, `drop_visual_index` round-trip, `inspect_page` round-trip.
45. Smoke-test against MCP host (Claude Desktop or `mcp-cli`).

## Risks

| Risk | Mitigation |
|------|------------|
| Qdrant 1.10+ multivector + optional-named-vectors features behave differently than documented | Pin Qdrant version in compose; explicit test of every vector config we use; fall back to vanilla single-vector if multivector + BQ combo misbehaves |
| ColPali sidecar throughput on CPU too slow even for ColSmolVLM | Async ingest is mandatory on CPU deployments; document the latency reality clearly |
| ColPali model output shapes drift between releases (e.g. ColQwen2 → ColQwen2.5) | Sidecar `/info` endpoint reports dimensions; Java client adapts. Lock the sidecar model version per deployment |
| Filesystem store fills up / permission issues | Default root under `${user.home}/.pdf-rag-ingest/`; deployment docs call out disk-usage planning at 1000+ pages (~200 MB per 1000 pages at 150 DPI). `drop_visual_index` cleans up its KB's tree. |
| Filesystem store orphans (Qdrant has the key but file missing, or file exists but Qdrant point gone) | `inspect_page` handles missing files gracefully (returns 404-style error in result). Periodic reconciliation job in vNext if it becomes a problem. |
| Binary quantization on `original` vector eats too much accuracy | Compose tests across BQ on/off; ship with BQ on, document the off-config for high-quality demanding deployments |
| Async queue persistence corner cases (crash mid-job) | At-least-once semantics: worker writes "in_progress" before starting; on restart, in_progress jobs go back to "queued" with a retry counter; cap retries |
| HTTP/2 h2c upgrade rejection on sidecar (same gotcha as llama-server) | `ColPaliClient.init()` pins HTTP/1.1, same pattern as everywhere else |

## Verification approach

Unit tests: every new class, plus the modified ones. Patterns match the existing test suite (plain JUnit5 + WireMock + reflection-based bean construction). No `@QuarkusTest`.

Integration tests: `QdrantBackendTest` and `ColPaliPipelineTest` exercise end-to-end pipelines with WireMock standing in for Qdrant + sidecar.

Manual smoke tests (Phase 8): real Qdrant + real sidecar + real PDF. Three scenarios:

- Clean born-digital PDF (no OCR invoked, text_quality=2 for all pages, fusion expected to lean text-heavy)
- Scanned PDF (Tika invokes Tesseract, text_quality=0 for most pages, fusion expected to lean visual-heavy)
- Mixed PDF (some pages born-digital, some scanned, text_quality varies across pages)

Each smoke test verifies: ingest succeeds, search returns reasonable results, confidence scores are sensible, `inspect_page` returns the right rendered page, all four `retrieval_mode` values work or degrade per the fallback matrix.

## Future work explicitly deferred

- Document catalog (per-KB record of source_value, ingest_timestamp, etc.) → enables `rebuild_visual_index` for retroactive enablement
- Source-byte caching → for any retroactive operation
- `compare_retrieval_modes` admin tool (returns multiple result sets for A/B)
- Learned-reranker `FusionStrategy` (cross-encoder over fused top-N)
- Adaptive `retrieval_mode=auto` that dynamically picks fusion vs text-only per query based on observed ambiguity / load
- Per-document override on `enable_visual_index` (the per-page text_quality gating in v1 makes this not urgent)
- Page rendering moved out of payload into object storage
- ONNX-INT8 + GGUF sidecar implementations (the HF Transformers reference is fine for v1; perf variants follow when demand justifies)
- **S3-compatible `PageImageStore` implementation** (RustFS / MinIO / AWS S3 / Backblaze B2). The interface is in place; vNext adds the adapter. Useful when KBs go multi-host or page-image storage is too big for a single filesystem.
- Multi-tenant rate limiting on the sidecar
- Cross-encoder reranker on top of fusion
- Tesseract-confidence-aware text_quality scoring (currently only PDFBox text-layer is used; aggregating Tesseract hOCR confidence is a richer signal we could add later)
