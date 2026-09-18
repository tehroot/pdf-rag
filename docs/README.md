# pdf-rag-ingest documentation

98% slopcoded, ignore issues, I'll make it 90% slopcoded soon. 

An MCP server that lets an LLM agent ingest documents into a vector store and
search them. Two backends, picked per-call or by environment default:

- **Qdrant** (default) — we own the RAG: Tika extraction, chunking, embeddings
  via an OpenAI-compatible endpoint (llama.cpp's `llama-server` by default;
  vLLM / OpenAI / Together / LM Studio also work), upserted into Qdrant.
- **Open WebUI** — legacy path. Upload the raw file; Open WebUI does extraction
  + embedding + storage; we attach the file to a named KB.

```
 ┌───────────┐  MCP   ┌───────────────────────────────────────────────────────────────────┐
 │ LLM /     │◀──────▶│ pdf-rag-ingest (this server)                                      │
 │ agent     │        │                                                                   │
 └───────────┘        │ IngestService ── dispatch by `backend` arg / INGEST_BACKEND       │
                      │         │                                            │            │
                      │         ▼                                            ▼            │
                      │  ┌───────────────────────────────────────┐  ┌──────────────────┐  │
                      │  │ QdrantBackend                         │  │ OpenWebUiBackend │  │
                      │  │  text:   fetch → Tika → chunk →       │  │ fetch → upload → │  │
                      │  │          embed → upsert <kb>    (REST)│  │ poll → /file/add │  │
                      │  │  visual: render → embed pages →       │  └────────┬─────────┘  │
                      │  │          upsert <kb>_pages      (gRPC)│           │            │
                      │  └────┬────────────┬──────────────┬──────┘           │            │
                      │       │            │              │                  │            │
                      └───────┼────────────┼──────────────┼──────────────────┼────────────┘
                              │            │              │                  │
                  REST :6333  │            │ HTTP         │ HTTP /embed_pages│  REST
                  gRPC :6334  │            │              │ (f32b64 back)    │
                              │            │              │                  │
                              ▼            ▼              ▼                  ▼
                       ┌─────────────┐ ┌─────────┐ ┌───────────────────┐ ┌──────────────┐
                       │ Qdrant      │ │ llama-  │ │ colpali-lb (nginx,│ │ Open WebUI   │
                       │ <kb>   REST │ │ server  │ │ least_conn)       │ │ /api/v1/...  │
                       │ <kb>_pages  │ │ /v1/emb │ │ ├ colpali-server  │ │              │
                       │        gRPC │ │ (GPU)   │ │ │   local GPU     │ └──────────────┘
                       │             │ │         │ │ └ colpali-0a…1d   │
                       └─────────────┘ └─────────┘ │     2nd GPU host  │
                                                   └───────────────────┘
```

Text chunks and searches use Qdrant's REST port; the `<kb>_pages`
multivector upserts use gRPC (`QdrantGrpcUpserter`, port 6334). Page
embeddings come back from whichever sidecar the balancer picked, as base64
float32 (`f32b64`); the sidecars hold no state and never talk to Qdrant.
See [components/visual-dataflow.md](components/visual-dataflow.md).

## Documents

| File | What it covers |
|------|----------------|
| [status.md](status.md) | Rolling project status: what shipped this cycle, test counts, open items + decisions, deployment state. Start here for "where are we?" |
| [architecture.md](architecture.md) | 1-page overview: module layout, the two backend pipelines, dispatcher, gotchas. |
| [components/](components/README.md) | Per-component deep dives — one walkthrough per core part of the application. Start here if you're working on the code. |
| [deployment.md](deployment.md) | Building both transports, environment variables, running Qdrant + llama-server + this server locally, container/systemd patterns. |
| [mcp-integration.md](mcp-integration.md) | Wiring this server into local LLM stacks that speak MCP: Claude Desktop, Cline / VS Code agents, Open WebUI, browser hosts. |
| [eval/retrieval-eval.md](eval/retrieval-eval.md) | Retrieval-accuracy eval protocol: gold doc/query sets, page-level hit@K / MRR, side-by-side KB comparison (e.g. sliding vs structural chunking), candidate-log debugging. |
| [components/directory-ingest.md](components/directory-ingest.md) | REST endpoint `POST /ingest/directory` — bulk-ingest a directory on disk (non-MCP), with idempotent re-scan via deterministic doc IDs. |
| [components/visual-dataflow.md](components/visual-dataflow.md) | The visual ingest data path across hosts: worker → balancer → sidecar (any host) → worker → Qdrant (gRPC, ingest host). Bytes and cost per hop, operating rules. |
| [plans/sidecar-pool-v1.md](plans/sidecar-pool-v1.md) | ColPali sidecar pool: nginx least-connections balancer (`docker-compose.pool.yml`, `deploy/colpali-lb.conf`), replicas on a second GPU host, measured rates, what bit. Done 2026-09-17. |
| [plans/sidecar-throughput-v1.md](plans/sidecar-throughput-v1.md) | Per-replica sidecar throughput: numpy + orjson post-processing (done), binary wire encodings `f32b64` / `f16b64` (done), Qdrant gRPC upsert outcome (done), thread-pool overlap and FlashAttention-2 (not started). |
| [plans/gpu-text-embedder-v1.md](plans/gpu-text-embedder-v1.md) | llama-server on the GPU, sidecar VRAM headroom (`PYTORCH_CUDA_ALLOC_CONF`), per-document locks, visual-queue stop/resume semantics. Done 2026-09-17. |

## Tool surface (what an agent sees)

| Tool | Purpose |
|------|---------|
| `ingest_document` | Resolve a `url` / `path` / `inline` source, find-or-create the named KB on the chosen backend, ingest. Optional `backend` and `metadata` args. |
| `search_documents` | Vector search a KB. Embed the query, run `/points/search` on Qdrant, return ranked chunks. (Qdrant only.) |
| `list_knowledge_bases` | List collections/KBs. Defaults to merging across both backends; pass `backend='qdrant'` or `'openwebui'` to scope. |
| `get_file_status` | Open-WebUI-only diagnostic — current processing status for a previously uploaded file id. |

`ingest_document` returns `{backend, kb_id, kb_name, file_id, processing_status, chunk_count, added_to_kb, message}`.
For Qdrant, `chunk_count` is the number of points upserted; `file_id` is the `doc_id` UUID.

## At a glance

- Java 21, Quarkus 3.33.1, `quarkiverse-mcp-server` 1.12.0.
- Three Maven modules: `core` (all logic + all tests), `server-stdio`, `server-http`.
- Qdrant + llama-server access is via plain `java.net.http.HttpClient` + Jackson — same
  pattern as the existing Open WebUI client. One exception: the `<kb>_pages`
  multivector upserts use the official `io.qdrant:client` 1.13.0 over gRPC
  (`ingest.qdrant.upsert-transport`, default `grpc`; `rest` reverts).
- Text extraction: Apache Tika 3 (`tika-parsers-standard-package`).
- 319 JUnit 5 + WireMock tests; `mvn -pl core test` needs no live Qdrant,
  llama-server, or Open WebUI. 67 sidecar tests (`pytest`, no torch needed).

If you only read one of these, start with [architecture.md](architecture.md).
