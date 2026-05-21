# Component walkthroughs

One doc per core part of the application. Read whichever maps to the change
you're making — each is self-contained.

## The data path (Qdrant backend with visual indexing enabled)

```
                                ingest
                                ──────
agent ──MCP──► IngestTools.ingest_document
                  │
                  ▼
              IngestService (dispatcher: picks backend by name)
                  │
                  ▼
              QdrantBackend.ingest  ◄── orchestrator: generates doc_id,
                  │                      validates mode consistency,
                  │                      hard-fails on sidecar-down
                  ├──► FileFetcher (URL / path / inline → bytes)
                  │
                  ├──► ChunkPipeline.ingestChunks
                  │       │
                  │       ├──► TextExtractor.extractPerPage (PDFBox per-page)
                  │       ├──► Chunker.chunkPerPage (page-tagged chunks)
                  │       ├──► Embedder.embed (llama-server / vLLM)
                  │       └──► QdrantClient.upsertPoints  → <kb> collection
                  │
                  └──► ColPaliPipeline.ingestPages   (if enable_visual_index=true)
                          │
                          ├──► PageRasterizer (PDFBox PDFRenderer → PNG)
                          ├──► TextLayerProbe (text_quality classification)
                          ├──► PageImageStore.store  → filesystem PNG store
                          ├──► ColPaliClient.embedPages  → Python sidecar
                          └──► QdrantClient.upsertMultivectorPoints  → <kb>_pages

                                search
                                ──────
agent ──MCP──► IngestTools.search_documents (retrieval_mode arg)
                  │
                  ▼
              IngestService.search
                  │
                  ▼
              QdrantBackend.search  ──► FusionEngine.search
                                          │
                  ┌───────────────────────┴───────────────────────┐
                  ▼                                               ▼
              ChunkPipeline.searchChunks                  ColPaliPipeline.searchPages
              (Embedder embed_query → Qdrant)             (ColPaliClient embed_query
                  │                                         → Qdrant multistage query
                  │                                           with prefetch+rerank)
                  │                                               │
                  └──────────────┬────────────────────────────────┘
                                 ▼
                          FusionStrategy.fuse  (RrfFusion or WeightedScoreFusion)
                                 │
                                 ▼
                          ConfidenceCalculator.annotate
                                 │
                                 ▼
                          SearchResponse: fused hits + per-hit + response-level
                                          confidence + warnings + fusion_mode
```

## Pieces — Java side

| Layer | Doc | Class(es) |
|-------|-----|-----------|
| Agent-facing | [mcp-tools.md](mcp-tools.md) | `IngestTools` (4 tools + `inspect_page` + `get_file_status`) |
| Routing | [dispatcher.md](dispatcher.md) | `IngestService`, `Backend` interface |
| Shared input | [file-fetcher.md](file-fetcher.md) | `FileFetcher` |
| Orchestration | [qdrant-backend.md](qdrant-backend.md) | `QdrantBackend` |
| Text pipeline | [text-extractor.md](text-extractor.md) | `TextExtractor` (Tika + PDFBox per-page) |
| Text pipeline | [chunker.md](chunker.md) | `Chunker` (page-tagged chunks) |
| Text pipeline | [embedder.md](embedder.md) | `Embedder` (llama-server / OpenAI-compat) |
| Visual pipeline | **[page-rasterizer.md](page-rasterizer.md)** | `PageRasterizer` (PDF → PNG) |
| Visual pipeline | **[text-layer-probe.md](text-layer-probe.md)** | `TextLayerProbe` (text_quality 0/1/2) |
| Visual pipeline | **[page-image-store.md](page-image-store.md)** | `PageImageStore` + `FilesystemPageImageStore` |
| Visual pipeline | **[colpali-client.md](colpali-client.md)** | `ColPaliClient` (HTTP → sidecar) |
| Visual pipeline | **[colpali-pipeline.md](colpali-pipeline.md)** | `ColPaliPipeline` (orchestrator for visual ingest+search) |
| Vector store | [qdrant-client.md](qdrant-client.md) | `QdrantClient` (collections + points + multivector + multistage) |
| Search-side | **[fusion-engine.md](fusion-engine.md)** | `FusionEngine` + `FusionStrategy` + `RrfFusion` + `WeightedScoreFusion` + `ConfidenceCalculator` |
| Async ingest | **[ingest-queue.md](ingest-queue.md)** | `IngestJob` + `IngestQueue` + `IngestWorker` (sync/queue routing) |
| Open WebUI pipeline | [openwebui-backend.md](openwebui-backend.md) | `OpenWebUiBackend` + helpers |
| Transports | [transports.md](transports.md) | `server-stdio`, `server-http` |

**Bolded entries are new in the fusion design.** The others have been updated
to reflect their role in the new architecture.

## The Python sidecar (separate repo, in `sidecar/`)

| Doc | What it covers |
|-----|---------------|
| **[colpali-sidecar.md](colpali-sidecar.md)** | The whole `sidecar/` subdirectory: contract, model loader, pooling, inference, Dockerfiles, hardware/model picker. |

## Structure of each walkthrough

Most docs follow this shape:

1. **What it does** — one paragraph.
2. **Interface** — the public surface (methods, records) other components see.
3. **Internals** — how it works, with the wire shape or algorithm detail.
4. **Failure modes** — what can go wrong and what the caller sees.
5. **Why it's like this** — design notes, alternatives considered, gotchas.
6. **Tests** — what's covered, what isn't.

If you want the executive summary instead, [../architecture.md](../architecture.md)
keeps a 1-page view. To deploy, [../deployment.md](../deployment.md). To wire it
into a client, [../mcp-integration.md](../mcp-integration.md). To understand
the design decisions behind fusion, [../plans/colpali-fusion-v1.md](../plans/colpali-fusion-v1.md).
