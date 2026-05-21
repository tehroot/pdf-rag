# ColPaliPipeline

`core/src/main/java/org/example/backend/qdrant/ColPaliPipeline.java` (~270
lines). The visual side of the Qdrant backend — owns the `<kb>_pages`
collection and the page-image storage. Used by `QdrantBackend` as a
collaborator (NOT a `Backend` of its own).

## What it does

For ingest: renders each page of a PDF, stores the PNG bytes outside Qdrant,
calls the ColPali sidecar to produce multi-vector embeddings, and upserts
those into a `<kb>_pages` multivector collection.

For search: embeds the query through the sidecar, runs Qdrant's multistage
prefetch+rerank query against the page collection, maps raw hits to `PageHit`
records.

For inspect: retrieves the stored PNG by `(kbName, docId, pageNumber)` and
returns it as base64 + dimensions.

## Where it sits

```
QdrantBackend.ingest
    ├──► ChunkPipeline.ingestChunks         (always runs)
    └──► ColPaliPipeline.ingestPages         (if enable_visual_index=true)
            │
            ├──► PageRasterizer    (PDF → PNG bytes)
            ├──► TextLayerProbe    (page text_quality 0|1|2)
            ├──► PageImageStore    (PNG → filesystem)
            ├──► ColPaliClient     (HTTP → Python sidecar)
            └──► QdrantClient      (multivector upsert → <kb>_pages)
```

## Interface

```java
@ApplicationScoped
public class ColPaliPipeline {
    // capability check — used by QdrantBackend and FusionEngine
    public boolean isEnabledFor(String kbName);
    public boolean sidecarHealthy();

    // ingest
    public PagesIngestResult ingestPages(IngestRequest req, FetchedFile file, String docId);

    // search
    public List<PageHit> searchPages(SearchRequest req);

    // admin
    public DropResult dropVisualIndex(String kbName);

    // inspect_page support
    public InspectPageResult inspectPage(String kbName, String docId, int pageNumber);
    public byte[] getRenderedPage(String kbName, String docId, int pageNumber);

    public static String pagesCollectionName(String kbName);   // → "<kbName>_pages"

    public record PagesIngestResult(String collection, String docId,
                                     int pageCount, int vectorDim);
    public record DropResult(boolean collectionDropped, int filesRemoved);
}
```

Injected dependencies:
- `PageRasterizer` — PDF → PNG bytes
- `TextLayerProbe` — per-page text_quality classification
- `ColPaliClient` — HTTP client for the sidecar
- `QdrantClient` — multivector collection ops
- `PageImageStore` — filesystem (or future S3) blob storage for PNGs

## Internals

### `isEnabledFor(kbName)` — the capability flag

Implicit state pattern: no separate metadata store, no flag table. We check
whether the `<kb>_pages` collection exists in Qdrant. If it does, visual is
on for this KB; if not, it's off.

```java
return qdrant.getCollection(kbName + "_pages") != null;
```

`QdrantClient.getCollection` returns `null` on 404, so this is a one-call
boolean.

### `ingestPages(req, file, docId)` — the full pipeline

The `docId` is the load-bearing parameter — it must match what
`ChunkPipeline.ingestChunks` used for the same document, so chunks and pages
join cleanly at fusion time. `QdrantBackend.ingest` generates one UUID and
passes it to both pipelines.

```java
// 1. Render every page of the PDF to a PNG.
List<RenderedPage> rendered = rasterizer.renderAll(file);

// 2. Probe text quality per page (no OCR, just PDFBox text-stripper).
List<PageQuality> qualities = textLayerProbe.probe(file);
Map<Integer, Integer> qualityByPage = ...;   // index by page_number

// 3. Store PNGs out-of-band via PageImageStore.
List<String> imageKeys = new ArrayList<>();
for (RenderedPage page : rendered) {
    imageKeys.add(imageStore.store(kbName, docId, page.pageNumber(), page.pngBytes()));
}

// 4. Embed all pages via the sidecar (batched at ColPaliClient.batchSize).
List<PageInput> sidecarInputs = ...;
List<PageEmbedding> embeddings = sidecar.embedPages(sidecarInputs);
int vectorDim = embeddings.get(0).original()[0].length;

// 5. Ensure <kb>_pages multivector collection exists with three named vectors.
Map<String, MultiVectorConfig> named = new LinkedHashMap<>();
named.put("original",      MultiVectorConfig.originalRerankOnly(vectorDim));
named.put("pooled_rows",   MultiVectorConfig.pooled(vectorDim));
named.put("pooled_cols",   MultiVectorConfig.pooled(vectorDim));
qdrant.ensureMultivectorCollection(kbName + "_pages", named);

// 6. Build multivector points and upsert.
//    Point ID is UUID v5 of (docId, "page", pageNumber) — distinct from chunk IDs
//    so the two collections' ID spaces don't collide.
for each rendered+embedded page:
    payload = { doc_id, page_number, filename, source, source_type,
                content_type, text_quality, page_image_key,
                page_image_size_bytes, page_width, page_height,
                ...user-supplied metadata,
                embed_model: sidecar.getInfo().model_name }
    vectors = { original, pooled_rows, pooled_cols }
    pointId = UuidV5.forPage(docId, pageNumber)

qdrant.upsertMultivectorPoints(kbName + "_pages", points);
return PagesIngestResult(collection, docId, pageCount, vectorDim);
```

Three named vectors per page (production-recommended ColPali storage pattern
from Qdrant's blog):

- `original` — full-resolution (~1030 vectors × 128d per page). HNSW disabled
  (`m: 0`); binary quantization on. Used only for rerank.
- `pooled_rows` — row-averaged grid (~38 vectors × 128d). HNSW enabled. Fast
  ANN prefetch.
- `pooled_cols` — column-averaged grid. Same as rows but pooled by columns.

### `searchPages(req)` — multistage query

```java
float[][] queryVectors = sidecar.embedQuery(req.query());
int topK = req.topK();
int prefetchLimit = topK * 10;     // pool prefetch is wider than final

List<PrefetchSpec> prefetches = List.of(
    new PrefetchSpec("pooled_rows", queryVectors, prefetchLimit),
    new PrefetchSpec("pooled_cols", queryVectors, prefetchLimit));

List<SearchHitRaw> raw = qdrant.queryMultistage(
    pagesCollectionName(req.kbName()),
    prefetches,
    "original",          // rerank against full-resolution vectors
    queryVectors,
    topK,
    req.filter());

return raw.stream().map(r -> toPageHit(r)).toList();
```

Qdrant runs the two pooled prefetches in parallel, deduplicates candidates,
then reranks the union with full-resolution `MAX_SIM` against the original
vectors. Returns top-K accurately ranked results at a fraction of the
"search-everything-at-full-resolution" cost.

### `dropVisualIndex(kbName)` — admin path

Cross-store cleanup:

```java
// 1. Drop the Qdrant collection (idempotent — 404 is treated as success).
qdrant.deleteCollection(pagesCollectionName(kbName));

// 2. Recursively remove the KB's page-image directory.
int filesRemoved = imageStore.deleteForKb(kbName);

return new DropResult(collectionDropped, filesRemoved);
```

### `inspectPage(kbName, docId, pageNumber)` — image retrieval

```java
String key = FilesystemPageImageStore.keyFor(kbName, docId, pageNumber);
byte[] bytes = imageStore.retrieve(key);
if (bytes == null) return null;
int[] dims = readPngDimensions(bytes);   // decode width/height from PNG IHDR
return new InspectPageResult(kbName, docId, pageNumber, dims[0], dims[1],
                              Base64.getEncoder().encodeToString(bytes));
```

PNG dimensions are read directly from the IHDR chunk header (bytes 16-23,
big-endian int32s) — no need to decode the full image.

## Failure modes

| Case | Result |
|------|--------|
| Non-PDF input to `ingestPages` | `IngestException` from `PageRasterizer`. |
| Sidecar unreachable mid-ingest | `IngestException` from `ColPaliClient`. `QdrantBackend.ingest` translates this to a clear "sidecar unreachable" message. |
| Embeddings count ≠ rendered page count | `IngestException` ("Sidecar returned N embeddings for M rendered pages"). |
| Mismatched dim on existing collection | `IngestException` from `QdrantClient.ensureMultivectorCollection`. |
| Image-store write fails (disk full, perms) | `IngestException` from `FilesystemPageImageStore`. |
| `dropVisualIndex` called on KB with no visual index | Returns `{collectionDropped: false, filesRemoved: 0}` — idempotent, doesn't throw. |
| `inspectPage` called on missing key | Returns `null`. The tool layer translates to a clear error message. |

## Why it's like this

- **Collaborator, not a `Backend`.** Two reasons: (a) the visual side is
  always paired with text in v1 — there's no "ColPali-only KB" backend; (b)
  the agent surface should be one logical KB, not two-keys-per-document.
  Making `ColPaliPipeline` a regular CDI bean injected into `QdrantBackend`
  lets the orchestrator decide when to call it.
- **`<kb>_pages` suffix.** Implicit state pattern. The existence of the
  collection IS the "is visual enabled?" signal — no separate metadata
  required. Operationally observable via `GET /collections` against Qdrant.
- **Images stored outside Qdrant.** A 1000-page corpus at 150 DPI is ~200MB
  of PNG. Putting that in Qdrant payload would bloat the WAL and snapshots
  dramatically. The `PageImageStore` abstraction lets us swap filesystem for
  S3-compatible later (vNext).
- **UUID v5 for page IDs, separate namespace from chunks.** Chunk point IDs
  are `UuidV5.forChunk(docId, chunkIndex)`; page IDs are
  `UuidV5.forPage(docId, pageNumber)`. Both stable and reproducible from the
  triple, no collision risk between the two collections.
- **`embed_model` recorded in payload.** When we later change the sidecar's
  model, we can identify points produced under the old model — and decide to
  re-ingest or accept the drift. Stored last in the payload (after
  user-supplied metadata's `putIfAbsent`) so the agent can't spoof it.
- **`textLayerProbe` invoked here, not in ChunkPipeline.** The text_quality
  signal needs to ride on the page payload so fusion's confidence
  calculation can read it. ChunkPipeline runs against extracted text, not
  pages directly, so this is the natural home.

## Tests

`ColPaliPipelineTest` (12 tests, WireMock for both Qdrant and sidecar):

- `isEnabledFor_falseWhenPagesCollectionMissing` / `…trueWhenPagesCollectionExists`
- `ingestPages_endToEnd_createsCollectionAndUpserts`
- `ingestPages_existingCollection_skipsCreate`
- `searchPages_embedsQuery_andRunsMultistage`
- `dropVisualIndex_dropsCollectionAndImageFiles`
- `dropVisualIndex_missingCollection_stillCleansImages`
- `getRenderedPage_returnsStoredBytes`
- `getRenderedPage_missing_returnsNull`
- `inspectPage_returnsBase64AndDimensions`
- `inspectPage_missing_returnsNull`
- `pagesCollectionName_appliesSuffix`

The test setup is involved (real `PageRasterizer` + real `TextLayerProbe` +
real `FilesystemPageImageStore` with a tmp dir + WireMock-backed sidecar +
WireMock-backed Qdrant) because each plays a real role. But no live services
required.
