# Plan: Lexical hybrid — "Classic" (client-side BM25 + Qdrant IDF) (v1, revision 2)

Status: design revised 2026-09-22 against the September deployment; awaiting go-ahead
Author drafted: 2026-07-06 · Revision 2: 2026-09-22
Companion: [lexical-bm25-hybrid-neu-v1.md](lexical-bm25-hybrid-neu-v1.md) — learned-sparse variant that reuses this plan's spine

## Revision 2 — what changed since July, and why the plan changed

Revision 1 was written against a small, fresh-KB deployment. Since then the R530 holds
`dtic_archive`: 12,167 PDFs, 2,012,201 chunks in a dense-only, unnamed-vector collection, joined by
doc id to `dtic_archive_pages` (934,834 pages, 18 consolidated segments that took days of NVMe
round-trips to build). "Lexical is a fresh-KB capability" is now a migration problem, not a footnote.
Six probes were run against a scratch `qdrant/qdrant:v1.13.4` (the deployed version) on 2026-09-22.
Every claim below marked **(probed)** was observed on that container.

| # | Revision 1 said | Reality on 1.13.4 | Consequence |
|---|---|---|---|
| R1 | Dense must become a *named* vector (`dense`) so the hybrid query can address it in `using`. | A collection may hold an **unnamed** dense vector plus `sparse_vectors`. A prefetch with no `using` (or `using: ""`) queries the unnamed vector. Upserts still accept the bare-array `vector` form. **(probed)** | Dense stays unnamed. `createCollection` gains an optional sparse block; `upsertPoints` keeps its shape; `CollectionInfo.dim()` keeps working (`config.params.vectors.size` is still present). The `ensureHybridCollection` "single-vector legacy" branch is gone. |
| R2 | Fusion wire shape `"query": {"rrf": {}}`. | The REST shape is `"query": {"fusion": "rrf"}` (or `"dbsf"`). `{"rrf":{}}` is rejected as a JSON format error. **(probed)** | Wire shape corrected. `dbsf` is a one-string config swap. |
| R3 | Qdrant RRF scores are tiny (~0.016), so ÷max is needed to reach [0,1]. | Scores are in (0, 1]: a hit at rank 1 in both legs scores 1.0, rank 1 in one leg 0.5, rank 2 in one leg 0.333. **(probed)** | ÷max stays, but for a different reason: it pins the top hit to 1.0 when it appears in one leg only, so `text_score_floor=1.0` keeps meaning "top hit". |
| R4 | (silent) A sparse vector might be added to an existing collection. | `PATCH /collections/{name}` with a new `sparse_vectors` entry fails: `Not existing vector name error`. PATCH **can** change index params of an existing sparse vector (`on_disk`, `full_scan_threshold`). **(probed)** | Fresh-collection rule confirmed. A **backfill** section is added: copy dense + payload into a new collection, add sparse, swap under the original name. |
| R5 | (silent) How a copied collection gets the original name. | Qdrant cannot rename. A snapshot **recovers under any target name** with the sparse config intact **(probed)**. Aliases resolve on `GET /collections/{alias}` but are absent from `GET /collections` **(probed)**, so `list_knowledge_bases` and `GET /kb` would not show the KB. | Swap by snapshot-recover, not alias. |
| R6 | (silent) Any KB name works for a rebuilt text collection. | `UuidV5.forSource(kbName, path)` keys the doc id on the **KB name**, and the chunk↔page join is by doc id. | A text re-ingest under a new KB name orphans 934,834 pages. The text collection must end up named exactly `dtic_archive`. `dtic_archive_pages` is never touched. |
| R7 | Qdrant issue #6735: IDF is not recomputed on delete. | On the toy collection, a sparse-only query's IDF-weighted score **did** change after a point delete **(probed, unindexed segment)**. Unverified for large indexed segments. | Caveat softened to "verify on the backfilled collection"; delete-before-upsert is now the normal ingest path (directory re-scan, upload replace), so the check matters more than in July. |
| R8 | `avg-len` default 150 tokens. | R530 still runs `INGEST_CHUNK_SIZE_CHARS=700`; text payload is sanitised (`TextSanitizer`) and stored clean. | Default holds. The backfill encodes `payload.text`, the same field a fresh ingest encodes, so backfilled and freshly ingested points are consistent. |
| R9 | REST search surface "if present". | There is none (`server-http/.../rest/` has ingest, upload, jobs, KB; no search). The eval harness calls `FusionEngine.search` with the 7-arg `SearchRequest`. | `use_lexical` lands on the MCP tool, `SearchRequest`, `FusionEngine.withTopK`, and a new `EVAL_USE_LEXICAL` env for the runner. No REST work. |
| R10 | Memory not considered. | The R530 budget is committed: Qdrant ~62 GB anonymous + ~19 GB int8 pooled + ARC 24 GiB + ingest JVM 48 GB of 188 GB. Search is CPU-bound (5.2 core-s per pooled query). | Sparse index sizing and `index.on_disk` are now design inputs; measure on the staging collection before the swap. |

Revision 1's core decisions stand: BM25 in pure Java, hashed term ids, saturated TF as the stored
value with IDF applied by Qdrant, dense⊕sparse fused **inside Qdrant** so the Java text⊕visual fusion
layer is untouched, and a `use_lexical` toggle for A/B measurement.

## Context

This work closes the one real gap the "does Weaviate beat us?" question surfaced: our text pipeline
(`ChunkPipeline` → `Embedder` → `QdrantClient.search`) is **dense-only**. Dense embeddings (bge) are
strong on paraphrase and weak on **exact terms, rare tokens, report numbers, contract numbers,
acronyms** — the tokens a DTIC user copies into a query. Qdrant ships sparse vectors and server-side
fusion; we have not wired them.

**Non-goals:** changing dense embeddings, chunking, the ColPali visual pipeline, the text⊕visual
fusion math, or anything in `<kb>_pages`.

## Goals

1. Text ingest still always writes a dense vector (unchanged when sparse is off).
2. When a KB was created with sparse enabled, the same ingest also writes a BM25 sparse vector
   (`text_bm25`) into the same `<kb>` collection, next to the unnamed dense vector.
3. Search on a sparse-enabled KB fuses dense+sparse in one `/points/query` call and hands the existing
   `FusionStrategy`/`ConfidenceCalculator` **one** ranked chunk list, exactly as the dense list today.
4. A per-call `use_lexical` flag (default true) forces dense-only on a hybrid KB for A/B measurement
   with the retrieval-eval harness.
5. **New:** an existing dense-only KB can be **backfilled** without re-extracting or re-embedding,
   and without touching its `_pages` collection, ending up under its original name.
6. All new wire shapes are unit-tested with WireMock, mirroring `QdrantClientTest`.

## Architecture decision: fuse server-side, not in Java (unchanged)

`FusionStrategy`, `RrfFusion`, `WeightedScoreFusion`, `ConfidenceCalculator`, `ResultDeduper`: zero
changes. Their tests pass verbatim; that is the proof the spine is non-invasive. The dense⊕sparse
fusion is Qdrant's, tuned by config (`rrf` | `dbsf`), not code.

## Data flow

```
INGEST (sparse-enabled KB)
  Chunk[] ──embedder.embed(embeddingText)──▶ dense float[]   ┐
        └──bm25.encodeDocument(text)───────▶ SparseVector    ├─▶ Point{id, dense, sparse?, payload} ─▶ upsertPoints(<kb>)
                                                              ┘
SEARCH (hybrid KB, use_lexical=true)
  query ─┬─ embedder.embedOne ──────▶ dense float[]  ┐
         └─ bm25.encodeQuery ───────▶ SparseVector   ├─▶ queryHybrid(<kb>) ─(Qdrant fusion)─▶ ranked hits ─▶ ÷max ─▶ List<SearchHit>
                                                      ┘                                                            │
                                                                                     FusionEngine (text⊕visual) ◀──┘
BACKFILL (existing dense-only KB, once)
  scroll <kb> (vectors+payload) ─▶ encodeDocument(payload.text) ─▶ upsertPoints(<kb>__lex) ─▶ snapshot ─▶ recover as <kb>
```

---

## Shared spine (Classic and Neu both build on this)

### 1. `SparseEncoder` interface + `SparseVector` *(new, `core/.../backend/qdrant/`)*

```java
public record SparseVector(int[] indices, float[] values) {
    public boolean isEmpty() { return indices == null || indices.length == 0; }
    public static SparseVector empty() { return new SparseVector(new int[0], new float[0]); }
}

public interface SparseEncoder {
    SparseVector encodeDocument(String text);   // stored at ingest and backfill
    SparseVector encodeQuery(String text);      // built at search
    default List<SparseVector> encodeDocuments(List<String> texts) { /* loop; Neu overrides with a batch call */ }
    boolean usesIdfModifier();                  // → sparse-vector config ("idf" or none)
    String vectorName();                        // named sparse key, default "text_bm25"
    boolean enabled();                          // ingest.sparse.enabled
}
```

`SparseEncoderProducer` selects one impl from `ingest.sparse.mode` (`bm25` now; `learned` when the Neu
plan lands). The batch default method is pulled forward from the Neu plan so the backfill and the
ingest loop share one call.

### 2. `QdrantClient` additions *(`core/.../backend/qdrant/QdrantClient.java`)*

**(a) Create with an optional sparse block.** Dense stays unnamed. **(probed)**

```java
public record SparseIndexConfig(boolean idf, boolean onDisk, Integer fullScanThreshold) {}

public void createCollection(String name, int dim) { createCollection(name, dim, null, null); }
public void createCollection(String name, int dim, String sparseName, SparseIndexConfig sparse) {
    Map<String,Object> body = new LinkedHashMap<>();
    body.put("vectors", Map.of("size", dim, "distance", distance));
    if (sparseName != null) {
        Map<String,Object> cfg = new LinkedHashMap<>();
        if (sparse.idf()) cfg.put("modifier", "idf");
        Map<String,Object> index = new LinkedHashMap<>();
        index.put("on_disk", sparse.onDisk());
        if (sparse.fullScanThreshold() != null) index.put("full_scan_threshold", sparse.fullScanThreshold());
        cfg.put("index", index);
        body.put("sparse_vectors", Map.of(sparseName, cfg));
    }
    // PUT /collections/{name}
}
```
Wire shape (probed, accepted by 1.13.4):
```json
{ "vectors": { "size": 384, "distance": "Cosine" },
  "sparse_vectors": { "text_bm25": { "modifier": "idf", "index": { "on_disk": false, "full_scan_threshold": 5000 } } } }
```

**(b) Ensure.** `ensureCollection(name, dim)` keeps its dim guard and its create-race tolerance. It
gains the sparse args and, when the collection exists, one new check: if sparse is requested and the
collection has no sparse vector, that is **not** an error — the caller logs a warning and writes
dense-only (an existing dense KB stays dense until it is backfilled; see §7). If the collection has a
sparse vector with a **different name** than the active encoder's, throw: the KB is bound to another
encoder (the Neu plan's rule, applied from day one).

**(c) Capability flag.**
```java
// CollectionInfo.Params: public Map<String,Object> sparse_vectors;   // absent on dense-only collections
public boolean hasSparseVectors() { ... }
public Set<String> sparseVectorNames() { ... }
```
`config.params.sparse_vectors` is present on hybrid collections and absent on dense-only ones **(probed)**.
This is the lexical analogue of the `<kb>_pages` visual-capability flag.

**(d) Upsert.** `Point` gains an optional sparse component; `upsertPoints` emits the map form only
when a point carries one **(probed: both forms accepted, may be mixed in one request)**:
```java
public record Point(String id, float[] vector, SparseVector sparse, Map<String,Object> payload) {
    public Point(String id, float[] vector, Map<String,Object> payload) { this(id, vector, null, payload); }
}
// sparse == null || empty  →  "vector": [ ...dense... ]                              (today's shape, byte-identical)
// else                     →  "vector": { "": [ ...dense... ], "text_bm25": { "indices": [...], "values": [...] } }
```
The empty-string key addresses the unnamed dense vector **(probed)**.

**(e) Hybrid query.** Same parse path as `queryMultistage` (factor the `QueryResponse → SearchHitRaw`
loop into a shared helper). The dense prefetch carries no `using`; `params.hnsw_ef` is accepted on it
**(probed)** and is wired to the same `COLPALI_PREFETCH_HNSW_EF`-style knob if search profiling wants it.
```java
public List<SearchHitRaw> queryHybrid(String collection, float[] dense, SparseVector sparse,
                                      String sparseName, int prefetchLimit, int topK,
                                      String fusion, Map<String,Object> filter)
```
Wire shape **(probed)**:
```json
{ "prefetch": [
    { "query": [<dense>], "limit": 25 },
    { "query": { "indices": [3,50,900], "values": [1.0,1.0,1.0] }, "using": "text_bm25", "limit": 25 } ],
  "query": { "fusion": "rrf" }, "limit": 5, "with_payload": true,
  "filter": { "must": [ { "key": "filename", "match": { "value": "x.pdf" } } ] } }
```
`filter` applies to both legs **(probed)**. `toQdrantFilter` is reused as-is.

**(f) Scroll** (new, for the backfill): `POST /collections/{name}/points/scroll` with
`with_vector: true, with_payload: true, limit, offset` → `(List<Point>, nextOffset)`. Returns the
dense vector as a bare array on dense-only collections **(probed)**.

**(g) Snapshot + recover** (new, for the swap): `POST /collections/{name}/snapshots?wait=true` →
snapshot name; `PUT /collections/{target}/snapshots/recover?wait=true` with
`{"location": "file:///qdrant/snapshots/<source>/<snapshot>"}`. The target name is free; the
recovered collection keeps its sparse config and answers hybrid queries **(probed)**.

**IDF semantics (unchanged):** with `modifier: "idf"` Qdrant scores each shared term as
`idf(term) · query_value · doc_value`. We store the **saturated TF** as the document value and **1.0**
as the query value; IDF comes once, from live collection statistics.

### 3. `ChunkPipeline` wiring *(`core/.../backend/qdrant/ChunkPipeline.java`)*

Inject `SparseEncoder sparse`. The collection schema is authoritative.

**Ingest** (`ingestChunks`, after `embedder.embed(...)`):
```java
qdrant.ensureCollection(req.kbName(), dim, sparse.enabled() ? sparse.vectorName() : null, sparseIndexConfig());
qdrant.ensurePayloadIndexes(req.kbName(), INDEXED_PAYLOAD_FIELDS);                 // unchanged
boolean writeSparse = sparse.enabled() && qdrant.getCollection(req.kbName()).hasSparseVectors();
List<SparseVector> sv = writeSparse ? sparse.encodeDocuments(chunks.stream().map(Chunk::text).toList()) : null;
// points.add(new Point(UuidV5.forChunk(docId, i), vectors.get(i), writeSparse ? sv.get(i) : null, payload));
// batching by ingest.qdrant.upsert-batch-size: unchanged (a sparse component adds ~1 KB per point)
```
When `sparse.enabled()` and the KB is dense-only, log one warning per ingest naming the backfill
endpoint. The `getCollection` call here is the one `ensureCollection` already makes; pass the
`CollectionInfo` back rather than fetching twice.

*Text choice (unchanged):* encode `c.text()` (clean stored text), not `c.embeddingText()`
(breadcrumb-prefixed). This is also what the backfill reads from `payload.text`, so both paths encode
the same string. `ingest.sparse.include-breadcrumbs` stays a deferred flag.

**Search** (`searchChunks`):
```java
CollectionInfo info = qdrant.getCollection(req.kbName());
boolean hybrid = info != null && info.hasSparseVectors() && req.useLexical();
if (hybrid) {
    float[] dv = embedder.embedOne(req.query());
    SparseVector sv = sparse.encodeQuery(req.query());
    List<SearchHitRaw> raw = qdrant.queryHybrid(req.kbName(), dv, sv, sparse.vectorName(),
                                                topK * prefetchMultiplier, topK, fusionMode, req.filter());
    // ÷max normalise (see §4), then the SAME SearchHit mapping as the dense branch
} else {
    // existing dense branch: byte-for-byte unchanged
}
```
`FusionEngine` already calls `pages.isEnabledFor(kb)` once per search (one `GET /collections/<kb>_pages`);
this adds one `GET /collections/<kb>`. A short-TTL capability cache remains a fast-follow.

### 4. Score normalisation (corrected rationale)

Qdrant's RRF fused score is in (0, 1] **(probed)**: `1/(1+rank)` summed over legs, so 1.0 means rank 1 in
both legs, 0.5 rank 1 in one leg. `ConfidenceCalculator` and `WeightedScoreFusion` treat
`text_score_floor=1.0` as "a top hit". Without normalisation a query whose best chunk matched only
lexically (0.5) would read as medium-confidence text. **Divide each fused score by the batch max**:
top hit → 1.0, tail proportional, ranking order unchanged, both consumers stay in range with no floor
retune. Guard: `maxFused <= 0` → all 0. With `dbsf` the range differs; the same ÷max applies.

### 5. `use_lexical` A/B toggle

- `SearchRequest`: add `boolean useLexical` as the last component, default true in the 5-arg
  convenience ctor; add a 7-arg compat ctor. Call sites that build the full record today:
  `IngestTools.searchDocuments`, `FusionEngine.withTopK` (must pass it through), `RetrievalEvalRunner`,
  tests.
- `IngestTools.search_documents`: optional `@ToolArg Boolean use_lexical` (null → true).
- `RetrievalEvalRunner`: `EVAL_USE_LEXICAL` env (default true), so the A/B is two runs of the same
  test against the **same** KB: `EVAL_USE_LEXICAL=false` then `true`.
- No REST search surface exists; nothing to add there.

---

## 6. Classic encoder — `Bm25SparseEncoder` (unchanged from revision 1)

`@ApplicationScoped`, pure CPU, no `@PostConstruct`. Tokenise: lowercase (`Locale.ROOT`), split on
`[^\p{L}\p{N}]+`, drop tokens shorter than `min-term-length`, no stopword list (IDF handles them).
Term id: `token.hashCode() & 0x7fffffff` (deterministic across JVMs; collisions sum, accepted).
Document value: `f·(k1+1) / (f + k1·(1 − b + b·docLen/avgLen))`. Query value: 1.0 per distinct term.
Defaults `k1=1.5`, `b=0.75`, `avg-len=150` tokens (tracks the R530's 700-char chunks; rule of thumb
`chars/5`), `min-term-length=2`. Edge cases and their tests are as in revision 1: empty → dense-only
point, unicode, long chunk damped by `b`, collisions collapsed, unseen query term contributes nothing.

**Determinism is now a migration invariant.** The backfill and every future ingest must produce the
same `(indices, values)` for the same text, or IDF statistics and query matching split across the
collection. One encoder, in Java, used by both paths. A Python backfill was considered and rejected
for this reason (re-implementing `String.hashCode()` and the tokenizer invites drift; the sidecar's
numpy-pooling parity test shows what it costs to keep two implementations aligned).

**IDF-on-delete caveat (softened):** issue #6735 says IDF is not recomputed on delete. On a toy
collection the score did update after a delete **(probed, unindexed segment)**. Delete-before-upsert is
now the normal path for directory re-scans and upload replaces, so verify on the backfilled
`dtic_archive`: sparse-only score for a fixed query before and after deleting one document. If it
drifts, the mitigation is an optimizer rebuild, not code.

---

## 7. Backfill an existing dense-only KB (new)

**Why not re-ingest.** A text-only directory re-ingest into a new KB would re-run Tika on 12,167 PDFs
and re-embed 2 M chunks (hours on the GPU embedder), and — decisive — `UuidV5.forSource(kbName, path)`
would mint new doc ids, orphaning every page in `dtic_archive_pages`. The chunk text and the dense
vector already sit in the payload and vector of each point. Copy them.

**Why a copy at all.** Sparse vectors cannot be added to an existing collection on 1.13.4 **(probed)**.

**Mechanism.** A Java admin service in `core` (`LexicalBackfillService`) driven by a REST endpoint in
`server-http` (the directory-ingest pattern):

```
POST   /kb/{name}/lexical/backfill        → starts a background task, 409 if one is running
GET    /kb/{name}/lexical/backfill        → { phase, pointsRead, pointsWritten, nextOffset, error }
```

Phases:

1. **Stage.** Create `<name>__lex` with the source's dim and the active encoder's sparse config
   (`ensureCollection` + `ensurePayloadIndexes`). Scroll `<name>` in pages of
   `ingest.sparse.backfill.page-size` (1000) with vectors and payload; for each page,
   `encodeDocuments(payload.text)` and `upsertPoints(<name>__lex, ...)` in the normal batch size.
   Point ids, payloads and dense vectors are copied verbatim, so the chunk↔page join and the payload
   indexes are unchanged. Restart = rerun from the start; upserts are idempotent by id. Points with
   empty `text` (none expected after `TextSanitizer`, but the `NoTextLayerException` path writes zero
   chunks, not empty ones) get a dense-only point.
2. **Verify.** Exact counts equal (`points/count` with `exact: true`; the collection-info counter is
   approximate). `sparseVectorNames()` on the staging collection is `[text_bm25]`. Run the sparse-only
   and hybrid probe queries and record the sparse index size (§ sizing).
3. **Swap** (operator-driven, documented, not automated in v1): snapshot `<name>` (rollback copy) →
   snapshot `<name>__lex` → delete `<name>` → recover `<name>` from the staging snapshot → exact count
   again → delete `<name>__lex` and the staging snapshot. Text search on the KB is unavailable between
   the delete and the recover; the page collection and queued visual jobs are untouched. Snapshots
   need temp space on the Qdrant data volume (see the segments doc, section 7.x on temp-space bounds).

**Sizing for `dtic_archive` (estimates, to be measured in phase 1):**

| Item | Estimate | Basis |
|---|---|---|
| Dense read + write | 2,012,201 × 1,536 B ≈ 3.1 GB | bge-small, 384 dims, f32 |
| Payload read + write | ≈ 2.4 GB | ~700 chars text + metadata per point |
| Sparse payload written | ≈ 1.6 GB | ~100 distinct terms × 8 B per point |
| Sparse inverted index, RAM | ≈ 1.6–3 GB | 2 × 10⁸ postings × 8 B, plus structure |
| Wall clock | hours, I/O-bound on the mirrors | not measured; the runner logs points/s |

The RAM figure lands on a host that is already at ~153 GB of 188 GB committed. Two levers exist and
both were probed: `index.on_disk: true` at create time, and `PATCH` of that flag on the live sparse
vector later. Decide from the phase-1 measurement, not now.

**Memory and CPU at search time.** The sparse leg is an inverted-index lookup over the query's ~5–15
terms; cost is small next to the 5.2 core-s of a pooled MaxSim query, but the R530 search path is
CPU-saturated at 7.7 q/s, so measure hybrid vs dense text latency with `concurrency.py` before the
default flips.

---

## Configuration *(`core/src/main/resources/application.properties` + CLAUDE.md table)*

```properties
# --- Lexical (sparse) channel ------------------------------------------------
ingest.sparse.enabled=${INGEST_SPARSE_ENABLED:false}            # gates new-KB hybrid creation + sparse writes
ingest.sparse.mode=${INGEST_SPARSE_MODE:bm25}                   # bm25 | learned (Neu plan)
ingest.sparse.vector-name=text_bm25
ingest.sparse.fusion=${INGEST_SPARSE_FUSION:rrf}                # rrf | dbsf  (Qdrant "query": {"fusion": ...})
ingest.sparse.prefetch-multiplier=${INGEST_SPARSE_PREFETCH_MULT:5}
ingest.sparse.index.on-disk=${INGEST_SPARSE_INDEX_ON_DISK:false}
ingest.sparse.index.full-scan-threshold=${INGEST_SPARSE_FULL_SCAN_THRESHOLD:5000}
ingest.sparse.backfill.page-size=${INGEST_SPARSE_BACKFILL_PAGE:1000}
ingest.sparse.bm25.k1=${INGEST_SPARSE_BM25_K1:1.5}
ingest.sparse.bm25.b=${INGEST_SPARSE_BM25_B:0.75}
ingest.sparse.bm25.avg-len=${INGEST_SPARSE_BM25_AVGLEN:150}     # tokens/chunk; track INGEST_CHUNK_SIZE_CHARS
ingest.sparse.bm25.min-term-length=${INGEST_SPARSE_BM25_MINLEN:2}
```

## Testing (plain JUnit 5 + WireMock, beans by reflection)

- **`Bm25SparseEncoderTest`** — as revision 1 (tokenise, term id determinism, BM25 math by hand,
  length damping, query values 1.0, empty, collisions).
- **`QdrantClientTest`** additions, asserting captured request JSON:
  - `createCollection_withSparse_sendsUnnamedDenseAndSparseBlock` (`vectors.size`,
    `sparse_vectors.text_bm25.modifier == "idf"`, `index.on_disk`).
  - `upsertPoints_withSparse_usesMapFormWithEmptyKey`; `upsertPoints_noSparse_isUnchanged` (bare array).
  - `queryHybrid_sendsFusionRrf_noUsingOnDense_andParsesHits`; `queryHybrid_dbsf`.
  - `getCollection_detectsSparseCapability` / `_denseOnlyHasNone`.
  - `scroll_returnsPointsAndNextOffset`; `snapshot_and_recover_sendExpectedPaths`.
- **`ChunkPipelineTest`** — hybrid ingest writes both vectors; hybrid search normalises ÷max with top
  == 1.0; `use_lexical=false` takes `/points/search`; dense-only KB + sparse enabled → warning, dense path;
  foreign sparse name → throws.
- **`LexicalBackfillServiceTest`** — WireMock scroll of two pages → encoded upserts to the staging
  collection with identical ids and payloads; empty-text point → dense-only; progress view.
- **Regression guard:** `RrfFusionTest`, `WeightedScoreFusionTest`, `ConfidenceCalculatorTest`,
  `ResultDeduperTest` unchanged and green.

## Verification (end-to-end)

1. `mvn -pl core test` green.
2. Local stack (`scripts/up.sh`), `INGEST_SPARSE_ENABLED=true`: ingest an identifier-rich PDF into a
   fresh KB; `GET /collections/<kb>` shows `sparse_vectors.text_bm25`; a scroll with vectors shows both
   vectors on a point. A rare exact-term query with `use_lexical=true` surfaces the chunk that
   `use_lexical=false` misses.
3. Backfill a small dense-only local KB end to end, including the snapshot swap, and diff exact
   counts and a sample of point ids/payloads before and after.
4. **R530, `dtic_archive`:** run phase 1 into `dtic_archive__lex` under `systemd-run` (never a
   laptop-side loop); record points/s, staging size and sparse-index RSS; run the IDF-on-delete check;
   run `concurrency.py` hybrid vs dense; then the swap in a quiet window.
5. Eval: build the gold set **from DTIC** — 20–40 queries with `(filename, page)` answers, weighted
   toward report numbers, contract numbers, acronyms and part identifiers, the lexical failure mode.
   Run `RetrievalEvalRunner` twice on the backfilled KB (`EVAL_USE_LEXICAL=false` / `true`). This is
   the first run of the harness; it also serves the pending chunking A/B once a second KB exists.

## Files to touch

- **New:** `SparseVector`, `SparseEncoder`, `SparseEncoderProducer`, `Bm25SparseEncoder`,
  `LexicalBackfillService` (core), `LexicalBackfillResource` (server-http), their tests.
- **Edit:** `QdrantClient` (sparse create/ensure, `Point.sparse`, `queryHybrid`, scroll, snapshot,
  `CollectionInfo.sparse_vectors`), `ChunkPipeline` (ingest + search branches), `SearchRequest`,
  `FusionEngine.withTopK`, `IngestTools`, `RetrievalEvalRunner`, `KnowledgeBaseSummary` (add
  `lexicalIndexEnabled`, surfaced by `list_knowledge_bases` and `GET /kb`), `application.properties`,
  `CLAUDE.md`, `docs/components/qdrant-client.md`, `docs/architecture.md`, `docs/eval/retrieval-eval.md`.

## Rollout sequencing

1. Spine + Classic encoder + `use_lexical` + tests. Shippable, no new infra, default off.
2. Backfill service + local end-to-end swap rehearsal.
3. R530: stage `dtic_archive__lex`, measure, swap. `dtic_archive_pages` untouched throughout.
4. DTIC gold set → eval A/B → tune `k1`/`b`/`avg-len`, decide `rrf` vs `dbsf`.
5. Flip `INGEST_SPARSE_ENABLED=true` by default once lift is shown. New KBs are hybrid from creation.
6. Only if BM25 recall proves insufficient → the Neu plan (encoder swap, same spine).

## Open questions / deferred decisions

- **Sparse index residency on the R530.** `on_disk` vs pinned; decided from the phase-1 measurement.
- **Breadcrumbs in lexical text.** Default clean `text`; `include-breadcrumbs` if heading-term recall lags.
- **Capability cache.** One extra `GET /collections/<kb>` per search; cache only if latency shows it.
- **Automate the swap?** v1 documents it as an operator procedure; a `POST .../lexical/swap` follows if
  more than one KB needs it.
- **`avg-len` auto-derivation** from `ingest.chunk.size-chars` at startup — nice-to-have.
- **Duplicate documents.** The backfill copies the collection as-is; the ingest planner's content-hash
  identity (decision D) is orthogonal and can run before or after.
