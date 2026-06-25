# Project status

A rolling snapshot of where pdf-rag-ingest is — what's shipped, what's in
flight, and the open decisions. For the architecture see
[architecture.md](architecture.md); for per-component detail see
[components/](components/README.md).

**As of:** 2026-06-24 · branch `feature/ingest-endpoint` (ahead of `main`;
this cycle's work is committed here, pending merge).

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
  `GET /ingest/status/{jobId}`, `DELETE /ingest/document`.
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
- **`deployment.md` refresh.** Still carries a "pre-fusion" banner; needs the
  visual side + the new REST surface + `scripts/` integrated.
- **SmallRye `Optional<String>` refactor.** Replace the single-space api-key
  default workaround in compose (the long-standing cleanup).
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
