# Project status

A rolling snapshot of where pdf-rag-ingest is — what's shipped, what's in
flight, and the open decisions. For the architecture see
[architecture.md](architecture.md); for per-component detail see
[components/](components/README.md).

**As of:** 2026-07-13 · branch `feature/ingest-endpoint` (ahead of `main`,
pending merge). The June cycle is committed here; the recent OpenAPI/Swagger
change + doc updates are **uncommitted in the working tree**.

## Snapshot

An MCP server that ingests documents into a vector store and lets an agent
search them. Default backend is **Qdrant** (text pipeline + optional ColPali
visual pipeline, fused via RRF/weighted with confidence scoring); **Open WebUI**
is the legacy parallel backend. Two transports (stdio, Streamable HTTP); the
HTTP transport now also serves a plain REST surface.

**Deployment:** live on the Dell R530 (`huge-dumb`) — Qdrant 1.13.4 +
llama-server (bge-small) + ColQwen2 on the RTX 3070 + the Quarkus MCP server,
driven by Qwen3 in Open WebUI. End-to-end working; retrieval accuracy "not
100%", which motivated the retrieval-quality work below.

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

- Java: **242 core unit tests** (plain JUnit 5 + WireMock, no live services;
  `mvn -pl core test` or `scripts/test.sh --core`). Full reactor builds clean.
- Python sidecar: **26 tests** (`scripts/test.sh --sidecar`), no torch needed.
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
- **`deployment.md` refresh.** REST surface + OpenAPI now documented; still
  carries a "pre-fusion" banner and needs the visual side + `scripts/` integrated.
- **SmallRye `Optional<String>` refactor.** Replace the single-space api-key
  default workaround in compose (the long-standing cleanup).
- **Multivector upsert cost.** First live per-stage timings (July 2026,
  milpdfs re-ingest) put the Qdrant upsert at ~307 ms/page — on par with
  ColQwen2 GPU embedding (~359 ms/page) and ~30% of visual-job wall time.
  Cause: `wait=true` synchronous indexing on ~2 MB/page JSON bodies.
  Levers if drain rate starts to matter: gRPC transport, `wait=false` +
  completion check, or larger multivector batches (bounded by the ~32 MB
  request cap). Measure first with `INGEST_QUEUE_WORKERS=2` overlap — worker
  overlap may already hide most of it.
- **Failed-job retry endpoint.** `POST /ingest/jobs/retry` (with an
  `?error_contains=` filter) resubmitting persisted requests under their
  original doc IDs. Deferred July 2026: the sidecar-outage burn that motivated
  it was fixed at the source (transient requeue + backoff + compose
  `service_healthy` gate), and full re-POSTs are idempotent — but selective
  recovery beats a 3 h corpus re-render when something novel fails a batch.
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
