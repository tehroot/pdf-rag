# QdrantBackend

`core/.../backend/qdrant/QdrantBackend.java` (~180 lines). The orchestrator
for the Qdrant backend. Implements the `Backend` interface; delegates the
actual work to `ChunkPipeline` (text side), `ColPaliPipeline` (visual side),
and `FusionEngine` (search-time fusion).

## What it does

Three responsibilities:

1. **Fetch once, ingest twice.** Calls `FileFetcher` to get the bytes, then
   passes the same `FetchedFile` to both pipelines. They share a `docId`
   (generated here) so chunks and pages can join at fusion time.
2. **Validate mode consistency.** Hard-rejects mode-mismatched ingests against
   existing KBs: a KB created with visual indexing on can't accept text-only
   ingests, and vice versa. This is the implicit-state pattern's enforcement
   — if you want a different mode, create a new KB.
3. **Route search through fusion.** `search()` delegates to `FusionEngine`,
   which handles `retrieval_mode` resolution, fallback, strategy selection,
   and confidence.

The class itself is small — most of the real work happens in the injected
collaborators.

## Interface

```java
@ApplicationScoped
public class QdrantBackend implements Backend {
    public static final String NAME = "qdrant";

    @Override public String name();
    @Override public IngestResult ingest(IngestRequest req);
    @Override public SearchResponse search(SearchRequest req);
    @Override public List<KnowledgeBaseSummary> listKnowledgeBases();
}
```

Injected:
- `FileFetcher` — URL / path / inline → bytes (shared with other backends)
- `ChunkPipeline` — text-side ingest + search + listing
- `ColPaliPipeline` — visual-side ingest + search + admin
- `FusionEngine` — search-time orchestration of the two pipelines

Config:
- `ingest.visual_index.default_enabled` (`INGEST_DEFAULT_VISUAL_INDEX`,
  default `true`) — the per-call fallback when `IngestRequest.enableVisualIndex()`
  is null.

## Ingest path

```java
public IngestResult ingest(IngestRequest req) {
    FetchedFile file = fetch(req);
    String docId = UUID.randomUUID().toString();

    boolean visualRequested = resolveVisualIndexEnabled(req);
    validateModeConsistency(req.kbName(), visualRequested);

    // Pre-flight sidecar check — hard-fail rather than silently degrading.
    if (visualRequested && !pages.sidecarHealthy()) {
        throw new IngestException("Visual index requested but the ColPali sidecar "
                + "is unreachable. Retry when it's back, or use enable_visual_index=false.");
    }

    // Text ingest always runs.
    IngestResult chunkResult = chunks.ingestChunks(req, file, docId);

    // Visual ingest only when requested.
    List<String> warnings = new ArrayList<>();
    int pageCount = 0;
    if (visualRequested) {
        if (isPdf(file)) {
            pageCount = pages.ingestPages(req, file, docId).pageCount();
        } else {
            warnings.add("enable_visual_index=true but file is not a PDF; "
                    + "visual side skipped for this document.");
        }
    }

    return new IngestResult(NAME, ..., docId, "completed",
            chunkResult.chunkCount(), pageCount, true, message, warnings);
}
```

### Mode resolution

```java
boolean resolveVisualIndexEnabled(IngestRequest req) {
    return req.enableVisualIndex() != null
            ? req.enableVisualIndex()
            : defaultVisualIndexEnabled;
}
```

Per-call value wins; env default fills in. The env default exists so a
no-sidecar deployment can set `INGEST_DEFAULT_VISUAL_INDEX=false` once and
never hit the sidecar-required path unless the agent explicitly opts in.

### Mode consistency validation

```java
void validateModeConsistency(String kbName, boolean visualRequested) {
    boolean kbHasChunks = chunks.collectionExists(kbName);
    boolean kbHasVisual = pages.isEnabledFor(kbName);

    if (!kbHasChunks && !kbHasVisual) {
        return;   // fresh KB — first ingest decides the mode
    }
    if (kbHasVisual && !visualRequested) {
        throw new IngestException("KB '" + kbName + "' was created with visual "
                + "index enabled, but enable_visual_index=false. Use true or "
                + "create a new KB.");
    }
    if (!kbHasVisual && visualRequested) {
        throw new IngestException("KB '" + kbName + "' was created without a "
                + "visual index. Use enable_visual_index=false or create a new KB.");
    }
}
```

Hard-reject. No `force_mode_change` in v1. The user's stance: mode mismatch
creates mixed-coverage KBs that are hard to reason about; a fresh KB is
cheap.

### PDF check for visual

`isPdf(file)` checks content-type and filename suffix. Non-PDF + visual
requested → warning + text-only ingest for this document. The KB still has
visual enabled overall; this one doc just doesn't have a visual side.

## Search path

```java
public SearchResponse search(SearchRequest req) {
    return fusion.search(req, NAME);
}
```

That's it. `FusionEngine` handles everything: mode resolution, calling the
right pipelines, fusing, confidence, response shape. See
[fusion-engine.md](fusion-engine.md).

## List path

```java
public List<KnowledgeBaseSummary> listKnowledgeBases() {
    return chunks.listKbCollections();
}
```

`ChunkPipeline.listKbCollections` iterates Qdrant's `/collections`, skipping
any name ending in `_pages` (those are the visual sub-collections of named
KBs, not standalone KBs). `QdrantBackend.listKnowledgeBases` then augments
each row with `visualIndexEnabled` / `visualIndexPages` via
`ColPaliPipeline.isEnabledFor` + `getPageCount`.

## Failure modes

| Case | Result |
|------|--------|
| Mode-mismatched ingest on existing KB | `IngestException` with clear "create new KB" instruction. |
| `enable_visual_index=true` + sidecar down | Hard-fail before any work. |
| `enable_visual_index=true` + non-PDF file | Warning in result; text-only ingest succeeds. |
| Chunk pipeline throws | Bubbles up; no visual ingest attempted. |
| Visual pipeline throws after text succeeded | Bubbles up; **the text chunks are still in Qdrant**. The visual side is partially committed (whatever points landed before the failure stay). This is consistent with the "best effort" semantics — recovery is re-ingesting after fixing the cause. |
| Fresh KB, any mode | Goes through; first ingest determines the KB's mode. |

The "visual fails after text succeeded" case is the one operational pain
point worth knowing about. Reasonable mitigation: a future cleanup tool
that finds doc_ids with chunks but no pages and re-runs the visual side.

## Why it's like this

- **Orchestrator pattern.** The original `QdrantBackend` did all the work
  itself (~200 lines). Splitting into pipelines + orchestrator made each
  piece individually testable and let `ColPaliPipeline` be a peer
  collaborator instead of a new `Backend`. The agent surface stays one
  logical KB; the orchestration is internal.
- **Shared doc id.** Without this, chunks and pages would have to be joined
  on (kb, filename, source) — fragile if any of those drift. Same UUID v5
  across both pipelines is the clean join key.
- **Hard-fail on mode mismatch, hard-fail on sidecar-down at ingest.** Both
  are operator errors that benefit from explicit failure. Silent degradation
  would produce KBs with uneven coverage that nobody asked for.
- **Soft-fail on non-PDF visual.** Different from the above — the operator
  asked for visual on a KB but happens to be ingesting a DOCX. We can still
  ingest the text (which is the primary value); just warn that the visual
  side was skipped. Future enhancement: pre-convert DOCX → PDF.
- **No special handling for the partial-commit case.** Mid-ingest failures
  leave a partial state in Qdrant. We don't add a transactional rollback
  because Qdrant doesn't have multi-collection transactions and the doc-id
  UUID would just collide on retry anyway. Recovery is human action.

## Tests

`QdrantBackendTest` (10 tests, WireMock):

Original (search-side and text ingest):
- `ingest_inlineText_createsCollectionAndUpsertsChunks`
- `ingest_path_writesPayloadWithChunkText`
- `search_embedsQueryThenCallsQdrantSearch`
- `listKnowledgeBases_includesDimAndVectorCount`
- `pointIdFor_isDeterministic`

New (visual orchestration):
- `ingest_visualEnabled_runsBothPipelinesAndAttachesPagesIngest` —
  end-to-end via `newBackendWithVisual` helper that wires sidecar + image
  store + ColPali pipeline against the same WireMock server.
- `ingest_visualEnabled_nonPdf_skipsVisualWithWarning` — warning surfaced
  in `IngestResult.warnings`.
- `ingest_modeMismatch_existingVisualKb_throws`.
- `ingest_modeMismatch_existingTextOnlyKb_throws`.
- `ingest_visualRequested_sidecarDown_hardFails`.

The `newBackendWithVisual` helper is involved — it wires real
`PageRasterizer`, real `TextLayerProbe`, real `FilesystemPageImageStore` (tmp
dir), real `ColPaliClient` (against WireMock), and `ColPaliPipeline`
composed of them. But no live services needed.
