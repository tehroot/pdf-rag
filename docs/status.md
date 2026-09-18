# Project status

A rolling snapshot of where pdf-rag-ingest is — what's shipped, what's in
flight, and the open decisions. For the architecture see
[architecture.md](architecture.md); for per-component detail see
[components/](components/README.md).

**As of:** 2026-09-18 · branch `feature/ingest-endpoint` (ahead of `main`,
pending merge). The September throughput work (commits `a4d9716`..`8eb15fe`,
2026-09-17/18) is committed here.

## Snapshot

An MCP server that ingests documents into a vector store and lets an agent
search them. Default backend is **Qdrant** (text pipeline + optional ColPali
visual pipeline, fused via RRF/weighted with confidence scoring); **Open WebUI**
is the legacy parallel backend. Two transports (stdio, Streamable HTTP); the
HTTP transport now also serves a plain REST surface.

**Deployment:** live on the Dell R530 — Qdrant 1.13.4 + llama-server
(bge-small, on the GPU since 2026-09-17) + one ColPali sidecar
(`TomoroAI/tomoro-colqwen3-embed-4b`, RTX A4500) + the Quarkus MCP server,
driven by Qwen3 in Open WebUI. For the DTIC bulk ingest an nginx balancer
(`colpali-lb`) fronts that sidecar plus eight replicas on `big-dumb` (two
CMP 170HX, four per card); see
[plans/sidecar-pool-v1.md](plans/sidecar-pool-v1.md). End-to-end working;
retrieval accuracy "not 100%", which motivated the retrieval-quality work
below.

## Capabilities

- **MCP tools (8):** `ingest_document`, `search_documents`,
  `list_knowledge_bases`, `delete_document`, `inspect_page`,
  `get_ingest_status`, `drop_visual_index`, `get_file_status` (Open WebUI).
- **REST (server-http, same port as `/mcp`):** `POST /ingest/directory`,
  `GET /ingest/status/{jobId}`, `DELETE /ingest/document`. OpenAPI schema at
  `/q/openapi` + Swagger UI at `/q/swagger-ui` (`quarkus-smallrye-openapi`).
- **Chunking strategies:** `sliding` (default) and `structural` (opt-in,
  heading-aware + breadcrumbs).
- **Backends:** Qdrant (default), Open WebUI (legacy).

## Shipped this cycle (June 2026)

| Area | What | Docs |
|---|---|---|
| Retrieval-quality prep | Qdrant payload indexes on every ingest; hardcoded tunables → config; per-stage timing + candidate-score logging (`INGEST_SEARCH_DEBUG_CANDIDATES`); `ResultDeduper` (collapses overlapping chunks, on by default) | [fusion-engine.md](components/fusion-engine.md), [result-deduper.md](components/result-deduper.md) |
| Structural chunking | `StructuredExtractor` (Tika XHTML / PDF font heuristics) + `StructuralChunker` (block packing, heading breadcrumbs on embedded text only). **Config-gated, default `sliding`.** | [structured-extractor.md](components/structured-extractor.md), [structural-chunker.md](components/structural-chunker.md) |
| Eval harness | Protocol + `RetrievalEvalRunner` (env-gated). **Built but never run** — needs a gold set. | [eval/retrieval-eval.md](eval/retrieval-eval.md) |
| Directory ingest (REST) | Scan a dir → per-file ingest; idempotent re-scan via deterministic `UuidV5.forSource` doc IDs; reuses sync/queue routing. First non-MCP HTTP surface (`quarkus-rest-jackson`). | [directory-ingest.md](components/directory-ingest.md) |
| Document deletion | `delete_document` tool + `DELETE /ingest/document` (by `doc_id` or `source_path`); `QdrantClient.deleteByDocId`. **Delete-before-upsert** in `doIngest` closes the stale-tail on re-ingest. | [directory-ingest.md](components/directory-ingest.md) |
| Dev pipeline | `scripts/` — portable-bash wrappers for build + run (CPU/GPU via one `--gpu` flag), bootstrap, test, smoke, teardown. | [../scripts/README.md](../scripts/README.md) |

## Shipped since (July 2026)

- **OpenAPI / Swagger on the REST surface.** Added `quarkus-smallrye-openapi` to
  `server-http` (only): schema at `/q/openapi`, Swagger UI at `/q/swagger-ui`,
  always-on via `SWAGGER_UI_ALWAYS_INCLUDE=true` (build-time). `IngestResource`
  annotated with `@Tag`/`@Operation`/`@Parameter`; MCP `/mcp` (not JAX-RS) stays
  out of the schema. Build + runtime verified (200s). **Uncommitted** working-tree
  change, alongside doc updates to [deployment.md](deployment.md) + `CLAUDE.md`.

## Shipped since (September 2026, 17th–18th)

Bulk-ingest throughput for the DTIC corpus (~12k PDFs, ~1.3 M pages). The
visual rate went from 92 pages/min (one sidecar) to 450–620 pages/min
(nine sidecars) and the Qdrant upsert from 0.44–0.54 s/page to 0.20 s/page.
Measured figures are in the plan docs.

| Area | What | Docs |
|---|---|---|
| Per-document locks | `QdrantBackend` replaced 64 lock stripes with reference-counted per-`docId` locks (map entries live only while held or waited on). A visual job no longer blocks unrelated text ingests: 40-file batches 93–217 s vs 910–1,103 s with one collision. | [plans/gpu-text-embedder-v1.md](plans/gpu-text-embedder-v1.md) |
| Text embedder on the GPU | `docker-compose.gpu.yml`: llama-server on the CUDA image with `--n-gpu-layers`; sidecar gets `PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True`. llama-server from 2000% CPU to under 1%. | [plans/gpu-text-embedder-v1.md](plans/gpu-text-embedder-v1.md) |
| Sidecar pool | `docker-compose.pool.yml` + `deploy/colpali-lb.conf` (nginx least-connections, no `max_conns`, `/healthz` busy fallback, embed access log) + `deploy/bigdumb-sidecar-compose.yml` (8 replicas on a second host). Sidecar Docker healthcheck timeout 40 s / 5 retries. Bring-up layers three compose files; `--no-deps` for any recreate during a run. | [plans/sidecar-pool-v1.md](plans/sidecar-pool-v1.md), [deployment.md](deployment.md) |
| Transient failures | `ColPaliClient`: one retry on `IOException`, then `SidecarUnavailableException` (requeue, no penalty); HTTP 502/503/504 transient. `IngestWorker`: a job interrupted by shutdown is requeued, not FAILED (65 jobs were lost to graceful restarts in one day). | [architecture.md](architecture.md) |
| No text layer | `NoTextLayerException`: a scanned PDF with a visual index requested yields 0 chunks and proceeds to the visual side (`QdrantBackend.ingestChunksOrNoText`). 524 of ~12k DTIC files had been rejected. | [architecture.md](architecture.md) |
| JVM heap passthrough | `JAVA_TOOL_OPTIONS: ${PDF_RAG_JAVA_TOOL_OPTIONS:-}` on `pdf-rag-http` for heap sizing and `-D` properties. | [deployment.md](deployment.md) |
| Sidecar post-processing | `embed_images_array` on both handles (one device-to-host copy), `pooling_np.py`, orjson response; numpy + orjson are core deps. Batch median 4.14 s → 3.03 s per replica, vectors bit-identical. | [plans/sidecar-throughput-v1.md](plans/sidecar-throughput-v1.md) |
| Binary wire encodings | `EmbedPagesRequest.encoding` = `json` \| `f32b64` \| `f16b64`; `/info` advertises `encodings`. Java requests `ingest.colpali.wire-encoding` (default `f32b64`) and decodes JSON arrays or base64 per field. 12-page batch 61.8 MB → 26.9 MB; heap-space retries 0. | [plans/sidecar-throughput-v1.md](plans/sidecar-throughput-v1.md) |
| Qdrant gRPC upserts | `QdrantGrpcUpserter` (`io.qdrant:client` 1.13.0, port 6334): `<kb>_pages` multivector upserts as packed float32, `wait=true`, REST fallback per batch. `ingest.qdrant.upsert-transport` (`grpc`), `grpc-host` (`auto`), `grpc-port` (`6334`). Qdrant CPU 350–550% → ~110%. | [plans/sidecar-throughput-v1.md](plans/sidecar-throughput-v1.md), [components/visual-dataflow.md](components/visual-dataflow.md) |

Incident to remember: `@ConfigProperty(defaultValue = "")` is "no value" to
SmallRye Config; the first gRPC build crash-looped for 9 min. Sentinel
defaults only, and tag the running image before a risky deploy.

## Designed, not built

- **Lexical (BM25/sparse) + dense hybrid on the text side.** The text pipeline is
  dense-only, which retrieves poorly on exact terms / rare tokens. Two standalone
  plans: [plans/lexical-bm25-hybrid-classic-v1.md](plans/lexical-bm25-hybrid-classic-v1.md)
  (client-side BM25 + Qdrant IDF, no new infra — **recommended first**) and
  [plans/lexical-bm25-hybrid-neu-v1.md](plans/lexical-bm25-hybrid-neu-v1.md)
  (learned SPLADE/BM42 via the sidecar). Fuse dense+sparse **server-side in
  Qdrant** (prefetch + RRF) so the Java text⊕visual fusion is untouched; lexical
  is a **fresh-KB** capability (schema immutable) with a `use_lexical` per-search
  A/B toggle wired to the eval harness. Motivated by a Weaviate gut-check →
  Weaviate is a Qdrant peer, not a replacement; the only real gap is the
  dense-only text side. **Design only; awaiting go-ahead.**

## Tests

- Java: **319 core unit tests** (plain JUnit 5 + WireMock, no live services;
  `mvn -pl core test` or `scripts/test.sh --core`; run 2026-09-18, 0 failures).
- Python sidecar: **67 tests** (`scripts/test.sh --sidecar`), no torch needed;
  includes numpy-pooling parity and the three wire encodings.
- No CI configured yet; `scripts/pipeline.sh` is the local stand-in.

## Open items / decisions

- **Run the retrieval eval.** Blocked on a gold set (5–10 prod PDFs + 20–40
  queries with `(filename, page)` answers). Then A/B `eval_sliding` vs
  `eval_structural` and decide: breadcrumb budget at 700-char chunks (cap 120
  vs raise to ~900), whether to flip the default strategy, prod cutover.
- **Implement the lexical hybrid.** Start with the Classic BM25 plan; measure
  lift via the eval harness + `use_lexical` before defaulting it on. See
  [plans/lexical-bm25-hybrid-classic-v1.md](plans/lexical-bm25-hybrid-classic-v1.md).
- **Commit the working-tree changes.** OpenAPI/Swagger + doc/plan updates are
  unstaged. (Note: `scripts/build-images.sh` is also modified but not by this
  work — confirm before staging.)
- **`deployment.md` refresh.** REST surface, OpenAPI, the compose overlays
  (GPU, pool) and heap sizing are documented; the doc still carries a
  "pre-fusion" banner and needs the visual side + `scripts/` integrated into
  every section. `PDF_RAG_JAVA_TOOL_OPTIONS` is documented in
  `.env.example` (commented `-Xmx64g` example), `docker-compose.yml` and
  [deployment.md](deployment.md).
- **SmallRye `Optional<String>` refactor.** Replace the single-space api-key
  default workaround in compose (the long-standing cleanup).
- **Multivector upsert cost — resolved 2026-09-18** by the gRPC transport
  (0.20 s/page, was 0.44–0.54 as JSON). Per-page worker cost is now render
  0.78 s (PDFBox, CPU), embed wait 0.76 s, upsert 0.20 s: rendering is the
  largest worker-side term. Next levers: sidecar thread-pool overlap and
  FlashAttention-2 (throughput plan steps 2–3, not started); storage (the
  pool writes at its sequential ceiling during a load).
- **Restore HNSW building on `dtic_archive_pages` after the bulk load.**
  `indexing_threshold` was raised to 100000000 on 2026-09-18 so no graphs
  build during the load; set it back to 20000 at the end, or pooled-vector
  prefetch stays brute force. See the throughput plan's step-4 outcome.
- **Failed-job retry endpoint.** `POST /ingest/jobs/retry` (with an
  `?error_contains=` filter) resubmitting persisted requests under their
  original doc IDs. Deferred July 2026: the sidecar-outage burn that motivated
  it was fixed at the source (transient requeue + backoff + compose
  `service_healthy` gate), and full re-POSTs are idempotent — but selective
  recovery beats a 3 h corpus re-render when something novel fails a batch.
  Still open after September: failed jobs are terminal and are resubmitted
  by staging symlinks + `POST /ingest/directory` (pool plan, operating notes).
- **CI.** No `.github/workflows`; consider wiring `scripts/test.sh --all`.
- **Merge** `feature/ingest-endpoint` → `main` once reviewed.

## Known constraints (carried)

- Qdrant collection dim is immutable — switching embed/ColPali models needs
  fresh collections.
- Mode mismatch on an existing KB is hard-rejected (no `force_mode_change`).
- CDN bot-detection RSTs some URL fetches → prefer the `/docs` inbox +
  path/directory ingest.
- `llama-server` needs its GGUF present before `up` (`scripts/bootstrap.sh`
  fetches it).
- Pool replicas across GPU generations (GA102 vs GA100, bf16) are not
  bit-identical: about 3% of token vectors per page differ (cosine < 0.9),
  pooled vectors match to 0.999, self-MaxSim 0.993. Accepted for bulk
  corpora; queries route through the same balancer.
- Recreate compose services with `--no-deps` during a run; a busy sidecar
  can fail its health probe and compose then refuses to start dependents.
