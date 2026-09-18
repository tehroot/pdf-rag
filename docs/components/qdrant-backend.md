# QdrantBackend

`core/.../backend/qdrant/QdrantBackend.java` (~530 lines). The orchestrator
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

    // Text ingest always runs — except that a PDF with no text layer
    // contributes zero chunks when a visual index was requested (below).
    IngestResult chunkResult = ingestChunksOrNoText(req, file, docId, visualRequested);

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

### No text layer: zero chunks, visual side proceeds

`ingestChunksOrNoText(req, file, docId, visualRequested)` wraps
`chunks.ingestChunks`. Both extractors throw `NoTextLayerException` (a
subclass of `IngestException`) when a document yields no text — a scanned
PDF with no text layer. The wrapper decides:

- visual requested AND the file is a PDF → log at INFO, return a
  `completed` result with `chunkCount = 0` and the message
  `"No text layer (…); 0 chunks ingested, visual side only"`. The visual
  side then runs (or is queued) as usual — the page embeddings are what
  make such a document retrievable.
- otherwise (text-only ingest, or a non-PDF) → rethrow. A text-only ingest
  of a scanned PDF is still a hard failure.

Both the sync path (`doIngest`) and the split-queue submit path use the
wrapper. Motivation recorded in the source: a directory ingest of a bulk
scanned corpus rejected every such file outright (524 of ~12k DTIC reports
skipped on the R530, 2026-09-17).

### Per-document locks

`doIngest` does delete-then-write (`chunks.deleteDoc` / `pages.deleteDoc`,
then the upserts). Deterministic doc ids mean an upload batch and a
directory scan — or two uploads of one filename — can target the same doc
id at once, and two interleaved delete+write sections leave one writer's
points deleted by the other. So every entry point (sync ingest, split-queue
submit, worker) holds a per-document lock across its whole delete + write
critical section.

```java
private final ConcurrentHashMap<String, DocLock> docLocks = new ConcurrentHashMap<>();

private static final class DocLock {
    final ReentrantLock lock = new ReentrantLock();
    int refs;   // holders + waiters; guarded by the map's per-key compute atomicity
}

private DocLock acquireDocLock(String docId)   // compute(): refs++, then lock()
private void releaseDocLock(String docId, DocLock dl)   // unlock(), then computeIfPresent(): --refs == 0 → remove
```

One lock per doc id, reference-counted — **not** a fixed stripe array. The
earlier 64-stripe design assumed collisions were harmless because ingest
was I/O-bound. That stopped being true once the visual lane held its stripe
across render + VLM embed: a 400-page job holds a stripe for minutes, and
any text ingest whose id hashes to that stripe waits the whole time. Observed
on the R530 with the DTIC corpus (2026-09-17, see
[../plans/gpu-text-embedder-v1.md](../plans/gpu-text-embedder-v1.md)): one
file in a 40-file directory batch waited 16 min behind an unrelated visual
job; 40-file batches took 159–184 s clean vs 910–1,103 s with one collision.
With per-doc locks the batches ran 93–217 s. Unrelated documents never
serialize.

The map is bounded by the number of doc ids with a holder or waiter *right
now*, not by corpus size: `acquireDocLock` bumps the per-id count under
`ConcurrentHashMap.compute` (atomic per key), `releaseDocLock` decrements
and drops the entry at zero under `computeIfPresent`. A new arrival between
the last holder's unlock and the removal still finds the same entry (its
compute runs before or after the removal, never interleaved), so two callers
on one id always share one lock. Single-process is the deployment, so there
is no distributed lock.

## Search path

```java
public SearchResponse search(SearchRequest req) {
    return fusion.search(req, NAME);
}
```

`FusionEngine` handles everything: mode resolution, calling the right
pipelines, fusing, confidence, response shape. See
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
| PDF with no text layer (`NoTextLayerException`) + visual requested | `completed` with `chunk_count = 0`; the visual side runs (or is queued). |
| PDF with no text layer + `enable_visual_index=false` (or a non-PDF with no text) | `NoTextLayerException` bubbles up — hard failure, nothing to index. |
| Two writers on the same doc id at once | The second waits on the per-doc lock; its delete + write runs after the first's completes. Unrelated doc ids never wait on each other. |
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
- **Per-doc locks, not stripes.** A stripe array bounds memory but couples
  unrelated documents; the visual lane holds its lock for minutes, so a
  collision costs a text ingest minutes. Reference counting keeps the map
  bounded by concurrent activity instead, with no coupling.
- **No special handling for the partial-commit case.** Mid-ingest failures
  leave a partial state in Qdrant. We don't add a transactional rollback
  because Qdrant doesn't have multi-collection transactions and the doc-id
  UUID would just collide on retry anyway. Recovery is human action.

## Tests

`QdrantBackendTest` (23 tests, WireMock):

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
- `ingest_noTextLayerPdf_visualRequested_queuesVisualWithZeroChunks` /
  `ingest_noTextLayerPdf_textOnly_stillFails` — the `NoTextLayerException`
  branch of `ingestChunksOrNoText`.
- `concurrentIngest_sameDocId_serializesDeleteAndWrite` — the per-doc lock.
- Queue/worker routing: `ingest_bigVisualPdf_ingestsTextSync_andQueuesVisualOnlyJob`,
  `ingestForWorker_visualJob_runsOnlyVisualSide`,
  `ingestForWorker_legacyFullJob_runsBothPipelines`.

The `newBackendWithVisual` helper is involved — it wires a real
`PageRasterizer`, `TextLayerProbe`, `FilesystemPageImageStore` (tmp dir), and
`ColPaliClient` (against WireMock) into a composed `ColPaliPipeline`. No live
services needed.
