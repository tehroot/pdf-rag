# pdf-rag-ingest documentation

An MCP server that lets an LLM agent ingest documents into a vector store and
search them. Two backends, picked per-call or by environment default:

- **Qdrant** (default) — we own the RAG: Tika extraction, chunking, embeddings
  via an OpenAI-compatible endpoint (llama.cpp's `llama-server` by default;
  vLLM / OpenAI / Together / LM Studio also work), upserted into Qdrant.
- **Open WebUI** — legacy path. Upload the raw file; Open WebUI does extraction
  + embedding + storage; we attach the file to a named KB.

```
            ┌───────────────────────────────────────────────────────────────┐
            │                     pdf-rag-ingest (this server)              │
            │                                                               │
            │    IngestService  ─dispatch by backend arg / INGEST_BACKEND─┐ │
            │                                                             │ │
┌────────┐  │   ┌────────────────────────────┐    ┌──────────────────────┐│ │
│        │  │   │  QdrantBackend             │    │  OpenWebUiBackend    ││ │
│  LLM /  │MCP│  │  fetch → Tika → chunk →   │    │  fetch → upload →    ││ │
│  agent │◀───▶│  embed → Qdrant            │    │  poll → /file/add    ││ │
│        │  │   └──────────┬─────────────┬──┘    └──────────┬───────────┘│ │
└────────┘  │              │             │                  │            │ │
            └──────────────┼─────────────┼──────────────────┼────────────┘ │
                           ▼             ▼                  ▼              │
                    ┌────────────┐ ┌──────────┐    ┌─────────────────┐     │
                    │ Qdrant REST│ │llama-srv │    │ Open WebUI REST │     │
                    │ /collections│ │/v1/embed │    │ /api/v1/...     │     │
                    │ /points    │ └──────────┘    └─────────────────┘     │
                    │ /search    │                                          │
                    └────────────┘                                          │
```

## Documents

| File | What it covers |
|------|----------------|
| [architecture.md](architecture.md) | 1-page overview: module layout, the two backend pipelines, dispatcher, gotchas. |
| [components/](components/README.md) | Per-component deep dives — one walkthrough per core part of the application. Start here if you're working on the code. |
| [deployment.md](deployment.md) | Building both transports, environment variables, running Qdrant + llama-server + this server locally, container/systemd patterns. |
| [mcp-integration.md](mcp-integration.md) | Wiring this server into local LLM stacks that speak MCP: Claude Desktop, Cline / VS Code agents, Open WebUI, browser hosts. |

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
  pattern as the existing Open WebUI client.
- Text extraction: Apache Tika 3 (`tika-parsers-standard-package`).
- 58 JUnit 5 + WireMock tests; `mvn -pl core test` runs in under 10 s and
  needs no live Qdrant, llama-server, or Open WebUI.

If you only read one of these, start with [architecture.md](architecture.md).
