# Plan: Lexical hybrid — "Classic" (client-side BM25 + Qdrant IDF) (v1)

Status: design converged, awaiting go-ahead
Author drafted: 2026-07-06
Companion: [lexical-bm25-hybrid-neu-v1.md](lexical-bm25-hybrid-neu-v1.md) — learned-sparse variant that reuses this plan's spine

## Context

This work closes the one real gap the "does Weaviate beat us?" question surfaced: our text pipeline
(`ChunkPipeline` → `Embedder` → `QdrantClient.search`) is **dense-only**. Pure dense embeddings
(bge) are strong on paraphrase/semantic similarity but weak on **exact terms, rare tokens, product
codes, identifiers, acronyms, and numbers** — precisely the tokens a user often copy-pastes into a
query. Weaviate ships lexical+dense hybrid natively; so does Qdrant, we just haven't wired it.

This "Classic" plan adds a **BM25 sparse vector** built entirely in Java (no new service, no model,
no GPU) into the *same* `<kb>` collection, and fuses dense+sparse **inside Qdrant's Query API** so the
existing Java fusion/confidence layer is untouched. It is the recommended first increment. The
[Neu companion plan](lexical-bm25-hybrid-neu-v1.md) swaps the encoder for a learned-sparse model
(SPLADE/BM42) via a sidecar, using the identical collection/upsert/query spine defined here.

**Non-goals:** changing dense embeddings, chunking, the ColPali visual pipeline, or the text⊕visual
fusion math. This is purely an additive lexical channel on the text side.

## Goal

1. Text ingest still always writes a dense index (today's behaviour, unchanged when sparse is off).
2. When sparse is enabled *at KB creation time*, the same ingest also writes a BM25 sparse vector into
   the same `<kb>` collection as a second **named** vector (`dense` + `text_bm25`).
3. Search on a sparse-enabled KB fuses dense+sparse in one `/points/query` call
   (`prefetch:[dense, sparse]` + `"query":{"rrf":{}}`), producing a single ranked chunk list that is
   fed to the existing `FusionStrategy`/`ConfidenceCalculator` **exactly as the dense list is today**.
4. A per-call `use_lexical` flag (default true) forces dense-only on a hybrid KB, for A/B measurement
   against the retrieval-eval harness (`docs/eval/retrieval-eval.md`).
5. All new REST/wire shapes are unit-tested with WireMock, mirroring `QdrantClientTest`.

## Architecture decision: fuse server-side, not in Java

Qdrant's Query API can prefetch multiple vectors and fuse them with RRF/DBSF server-side. We use that
so the text side still hands the fusion layer **one** ranked `List<SearchHit>`. Consequences:

- `FusionStrategy`, `RrfFusion`, `WeightedScoreFusion`, `ConfidenceCalculator`, `ResultDeduper`:
  **zero changes**. Their tests must still pass verbatim — that's the proof the spine is non-invasive.
- The only new "fusion" is dense⊕sparse, and Qdrant owns it. We tune it with a config knob, not code.

Rejected alternative — add a third channel into the Java fusion layer
(`fuse(denseHits, sparseHits, pageHits, cfg)`): churns every fusion class + the confidence formula,
for no ranking benefit (default RRF(text⊕visual) consumes *ranks*, not raw scores). More surface area,
more tests, more ways to drift. Not worth it.

## Data flow (ingest + search)

```
INGEST (sparse-enabled KB)
  Chunk[] ──embedder.embed(embeddingText)──▶ dense float[]         ┐
        └──bm25.encodeDocument(text)───────▶ SparseVector          ├─▶ HybridPoint ─▶ upsertHybridPoints(<kb>)
                                                                    ┘

SEARCH (hybrid KB, use_lexical=true)
  query ─┬─ embedder.embedOne ─────────▶ dense float[]   ┐
         └─ bm25.encodeQuery ──────────▶ SparseVector    ├─▶ queryHybrid(<kb>) ─(Qdrant RRF)─▶ ranked hits
                                                          ┘        │
                                       ÷max normalise textScore ◀──┘
                                                          │
                          same List<SearchHit> as today ──▶ FusionEngine (text⊕visual) ─▶ SearchResponse
```

---

## Shared spine (Classic and Neu both build on this)

### 1. `SparseEncoder` interface + `SparseVector`  *(new, `core/.../backend/qdrant/`)*

```java
/** Qdrant sparse vector wire shape: parallel indices/values arrays, indices are u32-safe. */
public record SparseVector(int[] indices, float[] values) {
    public boolean isEmpty() { return indices == null || indices.length == 0; }
    public static SparseVector empty() { return new SparseVector(new int[0], new float[0]); }
}

/** Produces sparse vectors for the lexical channel. Impl chosen by ingest.sparse.mode. */
public interface SparseEncoder {
    SparseVector encodeDocument(String text);  // stored at ingest (BM25 saturated TF, or learned weights)
    SparseVector encodeQuery(String text);     // built at search
    boolean usesIdfModifier();                 // → collection sparse-vector config ("idf" or none)
    String vectorName();                       // named-vector key, default "text_bm25"
    boolean enabled();                         // ingest.sparse.enabled
}
```

CDI selection — one `@ApplicationScoped` producer keyed off config, so exactly one impl is live:

```java
@ApplicationScoped
public class SparseEncoderProducer {
    @ConfigProperty(name = "ingest.sparse.mode", defaultValue = "bm25") String mode;
    @Inject Instance<Bm25SparseEncoder> bm25;
    @Inject Instance<LearnedSparseEncoder> learned;   // present only when the Neu plan is implemented

    @Produces @ApplicationScoped
    public SparseEncoder sparseEncoder() {
        return switch (mode) {
            case "bm25"    -> bm25.get();
            case "learned" -> learned.get();
            default        -> throw new IngestException("unknown ingest.sparse.mode: " + mode);
        };
    }
}
```

(Until the Neu plan lands, `LearnedSparseEncoder` doesn't exist; the `learned` branch is added then.)

### 2. `QdrantClient` additions  *(`core/.../backend/qdrant/QdrantClient.java`)*

Everything mirrors the existing multivector precedent (`createMultivectorCollection`,
`upsertMultivectorPoints`, `queryMultistage`) — same `builder(...)`, `writeJson`,
`sendExpectingSuccess`, `sendForJson` plumbing, same `SearchHitRaw` output.

**(a) Create hybrid collection.** Dense becomes a *named* vector (Qdrant's hybrid Query API needs a
name in `using`); sparse lives under `sparse_vectors` with the IDF modifier:

```java
public void createHybridCollection(String name, int dim, String denseName,
                                   String sparseName, boolean useIdf) {
    Map<String,Object> dense = Map.of("size", dim, "distance", distance);
    Map<String,Object> sparseCfg = useIdf ? Map.of("modifier", "idf") : Map.of();
    Map<String,Object> body = Map.of(
        "vectors",        Map.of(denseName, dense),
        "sparse_vectors", Map.of(sparseName, sparseCfg));
    // PUT /collections/{name}
}
```
Wire shape:
```json
{ "vectors": { "dense": { "size": 1024, "distance": "Cosine" } },
  "sparse_vectors": { "text_bm25": { "modifier": "idf" } } }
```

**(b) Ensure hybrid collection** — idempotent, matches `ensureMultivectorCollection`'s shape check:
```java
public void ensureHybridCollection(String name, int dim, String denseName,
                                   String sparseName, boolean useIdf) {
    CollectionInfo existing = getCollection(name);
    if (existing == null) { createHybridCollection(name, dim, denseName, sparseName, useIdf); return; }
    if (existing.dim() != null) {              // a single-vector (legacy dense-only) collection
        throw new IngestException("Collection '" + name + "' is single-vector dense-only; "
            + "recreate it to enable the lexical channel.");
    }
    // named-vector collection already exists — Qdrant validates named-vector shape on upsert.
}
```
Note: named-dense collections report `dim() == null` (the current `dim()` reads
`config.params.vectors.size`, which is only present for the *unnamed* default vector). That's why the
dim-immutability guard doesn't apply to hybrid collections — same as the multivector path today.

**(c) Capability flag** — extend the deserialization so we can detect "this KB has a lexical channel":
```java
// in CollectionInfo.Params:
public Map<String,Object> sparse_vectors;      // null for legacy dense-only collections
// in CollectionInfo:
public boolean hasSparseVectors() {
    return config != null && config.params != null
        && config.params.sparse_vectors != null && !config.params.sparse_vectors.isEmpty();
}
```
This is the lexical analogue of the `<kb>_pages` visual-capability flag / `ColPaliPipeline.isEnabledFor`.

**(d) Upsert hybrid points:**
```java
public record HybridPoint(String id, float[] dense, SparseVector sparse, Map<String,Object> payload) {}

public void upsertHybridPoints(String collection, String denseName, String sparseName,
                               List<HybridPoint> points) {
    // per point:
    //   vector = { denseName: dense[] }  (+ sparseName: {indices,values}  when sparse non-empty)
    // PUT /collections/{name}/points?wait=true  { "points": [ ... ] }
}
```
Wire shape (sparse present):
```json
{ "points": [ { "id": "<uuid>",
    "vector": { "dense": [<floats>],
                "text_bm25": { "indices": [12,88,340], "values": [1.7,0.9,2.3] } },
    "payload": { "text": "...", "doc_id": "...", "chunk_index": 0, ... } } ] }
```
Empty sparse → emit only `{"dense":[...]}` (Qdrant accepts a subset of a collection's named vectors).

**(e) Hybrid query (dense⊕sparse RRF):**
```java
public List<SearchHitRaw> queryHybrid(String collection, float[] dense, SparseVector sparse,
                                      String denseName, String sparseName,
                                      int prefetchLimit, int topK, Map<String,Object> filter) {
    // prefetch = [ {query: dense[],  using: denseName,  limit: prefetchLimit},
    //              {query: {indices,values}, using: sparseName, limit: prefetchLimit} ]
    // body = { prefetch, query: {"rrf": {}}, limit: topK, with_payload: true, filter? }
    // POST /collections/{name}/points/query  — parse via the SAME QueryResponse→SearchHitRaw path
    //   queryMultistage already uses (factor that parse into a shared private helper).
}
```
Wire shape:
```json
{ "prefetch": [
    { "query": [<dense>], "using": "dense", "limit": 25 },
    { "query": { "indices": [3,50,900], "values": [1.0,1.0,1.0] }, "using": "text_bm25", "limit": 25 } ],
  "query": { "rrf": {} }, "limit": 5, "with_payload": true }
```
Why RRF (not DBSF): rank-based, no score normalization needed across two very different score scales
(cosine ∈ [-1,1] vs BM25 unbounded). DBSF is a config swap later if we want relative-score blending.

**IDF semantics to keep straight:** with `modifier:"idf"`, Qdrant computes each matching term's score
as `idf(term) · query_value · doc_value`, summed over shared indices. So to get textbook BM25 we store
the **saturated TF (with k1/b/doclen) as the document value** and **1.0 as the query value** — IDF is
applied once, by Qdrant, from live collection statistics.

### 3. `ChunkPipeline` wiring  *(`core/.../backend/qdrant/ChunkPipeline.java`)*

Inject `SparseEncoder sparse`. The collection schema is **authoritative** — an existing KB can't gain
sparse in place (Qdrant vector config is immutable), so we decide the path from what's on disk:

**Ingest** (`ingestChunks`, after `embedder.embed(...)` ~line 123):
```java
CollectionInfo info = qdrant.getCollection(req.kbName());
boolean collectionHybrid = info != null && info.hasSparseVectors();
boolean useHybrid = (info != null) ? collectionHybrid : sparse.enabled();

if (sparse.enabled() && info != null && !collectionHybrid) {
    warnings.add("KB '" + req.kbName() + "' is dense-only; lexical channel skipped. "
               + "Recreate the KB with sparse enabled to add it.");   // graceful degrade
}
if (useHybrid) {
    qdrant.ensureHybridCollection(req.kbName(), dim, "dense", sparse.vectorName(), sparse.usesIdfModifier());
    qdrant.ensurePayloadIndexes(req.kbName(), indexedPayloadFields());   // unchanged
    List<HybridPoint> pts = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
        Chunk c = chunks.get(i);
        pts.add(new HybridPoint(UuidV5.forChunk(docId, c.index()),
                                vectors.get(i),
                                sparse.encodeDocument(c.text()),      // clean text, not embeddingText (see note)
                                buildPayload(req, file, docId, c, userMeta, embedder.model())));
    }
    // batch by ingest.qdrant.upsert-batch-size → qdrant.upsertHybridPoints(kb, "dense", vectorName, batch)
} else {
    // existing dense path — ensureCollection / upsertPoints — BYTE-FOR-BYTE UNCHANGED
}
```
*Text choice:* encode `c.text()` (the clean stored text), not `c.embeddingText()` (heading-breadcrumb
prefixed). Rationale: lexical should match what the user sees/quotes; breadcrumbs would over-weight
heading tokens. **Tunable/open decision** — including breadcrumbs could help heading-term recall; make
it an `ingest.sparse.include-breadcrumbs` flag if eval shows a gap. Default: clean text.

**Search** (`searchChunks`, ~line 166):
```java
CollectionInfo info = qdrant.getCollection(req.kbName());
boolean hybrid = info != null && info.hasSparseVectors();
if (hybrid && req.useLexical()) {
    float[] dv = embedder.embedOne(req.query());
    SparseVector sv = sparse.encodeQuery(req.query());
    int prefetch = topK * prefetchMultiplier;   // ingest.sparse.prefetch-multiplier, default 5
    List<SearchHitRaw> raw = qdrant.queryHybrid(req.kbName(), dv, sv, "dense",
                                                sparse.vectorName(), prefetch, topK, req.filter());
    // ↓ normalise fused scores into cosine-like range (see §4), then build SearchHit list
    //   EXACTLY as the dense branch does (same field mapping: text/source/filename/doc_id/pages/…)
} else {
    // existing dense qdrant.search(...) branch — unchanged
}
```
`getCollection` is now called once per search here (in addition to the visual capability check the
fusion layer already does). Acceptable; a short-TTL capability cache is a fast-follow if it shows up in
latency (note in "Open questions").

### 4. Score normalization (keeps confidence math valid)

Qdrant RRF fused scores are tiny (`~1/(rrf_k+rank)`, e.g. 0.016), but `ConfidenceCalculator` and
`WeightedScoreFusion` expect a text signal in ~[0,1] (`text_score_floor=1.0`). In the hybrid search
branch, **divide each fused score by the batch max** → `textScore = fused/maxFused ∈ (0,1]`, top hit =
1.0. Set both `SearchHit.score` and `.textScore` to this normalized value. Properties:

- Monotone → ranking order identical to Qdrant's; default RRF(text⊕visual) (rank-based) is unaffected.
- `WeightedScoreFusion` text signal `min(1, textScore/1.0)` and confidence `normalize(textScore, 1.0)`
  both stay in-range with **no floor retuning**.
- Guard `maxFused <= 0` (all-zero / empty) → set textScore = 0, or 1.0 for a single hit; pick and test.

Divide-by-max (not min-max) is deliberate: min-max forces the worst hit to exactly 0 even when
genuinely relevant, and collapses when `max==min` (single result). Divide-by-max keeps a proportional
tail and has one trivial guard.

### 5. `use_lexical` A/B toggle

- `SearchRequest` (`core/.../ingest/SearchRequest.java`): add `boolean useLexical` as the last
  component; **default true**. Update the canonical ctor and the existing 5-arg convenience ctor to
  pass `true`; add a compat ctor if any call site constructs the full arg list (grep for
  `new SearchRequest(` — `IngestTools`, `FusionEngine.withTopK`, tests).
- `IngestTools.search_documents` (`core/.../tools/IngestTools.java`): add an optional
  `@ToolArg Boolean use_lexical` (nullable → treat null as true). Thread into the `SearchRequest`.
- REST search surface (`server-http/.../rest/`, if a search endpoint exists): add an optional
  `use_lexical` query/body param, same default.

False on a hybrid KB → dense-only path above → cheap A/B without rebuilding KBs, which is exactly what
the dormant retrieval-eval harness needs to prove lift.

---

## Classic encoder — `Bm25SparseEncoder`

`@ApplicationScoped`, `@ConfigProperty` fields, **no HTTP, no `@PostConstruct` needed** (pure CPU;
follows the `Chunker` bean shape, not the `Embedder`/`QdrantClient` HTTP-client shape).

```java
@ApplicationScoped
public class Bm25SparseEncoder implements SparseEncoder {
    @ConfigProperty(name = "ingest.sparse.enabled", defaultValue = "false") boolean enabled;
    @ConfigProperty(name = "ingest.sparse.vector-name", defaultValue = "text_bm25") String vectorName;
    @ConfigProperty(name = "ingest.sparse.bm25.k1", defaultValue = "1.5")    double k1;
    @ConfigProperty(name = "ingest.sparse.bm25.b",  defaultValue = "0.75")   double b;
    @ConfigProperty(name = "ingest.sparse.bm25.avg-len", defaultValue = "150") double avgLen;
    @ConfigProperty(name = "ingest.sparse.bm25.min-term-length", defaultValue = "2") int minTermLen;

    private static final Pattern SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    // --- tokenization: lowercase, unicode-aware split, drop short tokens; stopwords OFF (IDF handles them)
    private List<String> tokenize(String text) { /* text.toLowerCase(ROOT); SPLIT.split; filter len>=minTermLen */ }

    // --- deterministic token→u32 id: 31-bit positive hash (stable across docs so Qdrant IDF is coherent)
    private static int termId(String token) { return token.hashCode() & 0x7fffffff; }

    @Override public SparseVector encodeDocument(String text) {
        List<String> toks = tokenize(text);
        if (toks.isEmpty()) return SparseVector.empty();
        int docLen = toks.size();
        Map<Integer,Integer> tf = new HashMap<>();               // termId → raw count (collapses collisions by sum)
        for (String t : toks) tf.merge(termId(t), 1, Integer::sum);
        double denomBase = k1 * (1 - b + b * (docLen / avgLen));
        int[] idx = new int[tf.size()]; float[] val = new float[tf.size()]; int i = 0;
        for (var e : tf.entrySet()) {
            double f = e.getValue();
            double w = (f * (k1 + 1)) / (f + denomBase);          // BM25 saturated TF; Qdrant multiplies by IDF
            idx[i] = e.getKey(); val[i] = (float) w; i++;
        }
        return new SparseVector(idx, val);
    }

    @Override public SparseVector encodeQuery(String text) {
        List<String> toks = tokenize(text);
        Map<Integer,Float> q = new LinkedHashMap<>();             // dedup; query value = 1.0, IDF applied by Qdrant
        for (String t : toks) q.putIfAbsent(termId(t), 1.0f);
        return toArrays(q);
    }

    @Override public boolean usesIdfModifier() { return true; }
    @Override public String vectorName() { return vectorName; }
    @Override public boolean enabled() { return enabled; }
}
```

**BM25 parameter rationale / tuning:**
- `k1` (TF saturation, default 1.5) and `b` (length normalization, default 0.75) are the textbook
  defaults — good starting point, tune on eval.
- `avgLen` is the average **document length in tokens**, used for length normalization. It must track
  the *actual* chunk size. **The R530 deployment runs `INGEST_CHUNK_SIZE_CHARS=700`** (dense PDFs bust
  bge's 512-token cap), ≈ 110–150 tokens/chunk → default `avgLen=150`. If chunk size changes, retune
  `avg-len` (rough rule: `chars/5`). We can't compute the true corpus average client-side at ingest
  time, so this constant is the pragmatic stand-in (same approach as FastEmbed's BM25).

**Edge cases (all get a test):**
- Empty / all-short-token chunk → `SparseVector.empty()` → point stores dense only.
- Unicode text → `\p{L}\p{N}` keeps letters/digits across scripts; punctuation/whitespace split out.
- Very long chunk → length normalization damps it (that's the point of `b`).
- Hash collisions → two distinct terms share an index; summed → minor score noise, acceptable and
  standard for hashed BM25. Determinism preserved (same token → same id every run).
- Query term absent from corpus → Qdrant IDF for an unseen index contributes nothing; fine.

Pure CPU, negligible latency, **no new service / model / GPU** — the "close the gap cheaply" path and
the natural default.

**Known caveat (note in code + docs):** Qdrant issue #6735 — IDF statistics aren't recomputed when
points are *deleted*, and the directory-ingest replace-on-reingest path (`deleteByDocId` then upsert)
deletes. Effect is slow IDF drift on heavily re-ingested KBs, not correctness loss. Mitigation: a
periodic collection `optimize`/rebuild, or accept the drift (IDF is a soft weight). Call it out; don't
over-engineer in v1.

---

## Configuration  *(`core/src/main/resources/application.properties` + CLAUDE.md table + docs/architecture.md)*

```properties
# --- Lexical (sparse) channel ------------------------------------------------
ingest.sparse.enabled=${INGEST_SPARSE_ENABLED:false}          # gates new-KB hybrid creation
ingest.sparse.mode=${INGEST_SPARSE_MODE:bm25}                 # bm25 | learned (see neu plan)
ingest.sparse.vector-name=text_bm25
ingest.sparse.prefetch-multiplier=${INGEST_SPARSE_PREFETCH_MULT:5}
ingest.sparse.bm25.k1=${INGEST_SPARSE_BM25_K1:1.5}
ingest.sparse.bm25.b=${INGEST_SPARSE_BM25_B:0.75}
ingest.sparse.bm25.avg-len=${INGEST_SPARSE_BM25_AVGLEN:150}   # tokens/chunk; track INGEST_CHUNK_SIZE_CHARS
ingest.sparse.bm25.min-term-length=${INGEST_SPARSE_BM25_MINLEN:2}
```

Add the env-mapped rows (`INGEST_SPARSE_ENABLED`, `INGEST_SPARSE_MODE`) to the CLAUDE.md config table.

## Testing (plain JUnit 5 + WireMock; construct beans by reflection + reflective `init()`)

- **`Bm25SparseEncoderTest`** (pure POJO, no WireMock, no reflection needed — like `RrfFusionTest`):
  - `tokenize` drops sub-`minTermLen` tokens, lowercases, splits on punctuation/unicode.
  - `termId` deterministic & non-negative; same token → same id across calls.
  - `encodeDocument` BM25 math: hand-compute `f*(k1+1)/(f+k1*(1-b+b*docLen/avgLen))` for a 2–3 term doc, assert values.
  - length normalization: longer doc (same tf) → smaller value.
  - `encodeQuery` values all 1.0, indices deduped.
  - empty text / all-short → `SparseVector.empty()`.
  - collision-collapse: crafted duplicate ids summed, no duplicate indices in output.
- **`QdrantClientTest`** additions (mirror `queryMultistage_sendsExpectedShape_andParsesHits`; assert on
  the captured request JSON body via WireMock `getAllServeEvents`):
  - `createHybridCollection_sendsExpectedBody` — asserts `vectors.dense.size`, `sparse_vectors.text_bm25.modifier=="idf"`.
  - `createHybridCollection_noIdf_omitsModifier` (for the Neu/SPLADE path).
  - `upsertHybridPoints_sendsExpectedBody` — asserts `vector.dense` array + `vector.text_bm25.{indices,values}`.
  - `upsertHybridPoints_emptySparse_sendsDenseOnly`.
  - `queryHybrid_sendsExpectedShape_andParsesHits` — asserts `prefetch[*].using`, `query.rrf`, parses `SearchHitRaw`.
  - `getCollection_detectsSparseCapability` — stub a `config.params.sparse_vectors` body, assert `hasSparseVectors()`.
- **`ChunkPipelineTest`** (bean-by-reflection, WireMock Qdrant + embedder like `ColPaliPipelineTest`):
  - hybrid ingest builds sparse + calls `/points` with both named vectors.
  - hybrid search hits `/points/query`, normalizes fused scores (÷max, top==1.0), maps SearchHit fields.
  - `use_lexical=false` on a hybrid KB → falls back to `/points/search` (dense).
  - dense-only KB + `sparse.enabled` → warning emitted, dense path taken.
- **Regression guard:** `RrfFusionTest`, `WeightedScoreFusionTest`, `ConfidenceCalculatorTest`,
  `ResultDeduperTest` unchanged and green — proves the spine didn't leak into the fusion layer.

`mvn -pl core test` stays < 15s, no live services.

## Verification (end-to-end, against a running stack)

1. `mvn -pl core test` — all green.
2. `scripts/up.sh` (Qdrant + llama-server); export `INGEST_SPARSE_ENABLED=true`.
3. Ingest a doc rich in identifiers/codes into a **fresh** KB. Confirm
   `GET /collections/<kb>` shows `config.params.sparse_vectors.text_bm25` and that points carry both
   `dense` and `text_bm25` (`POST /collections/<kb>/points/scroll` with `with_vectors:true`).
4. `search_documents` a rare exact-term query with `use_lexical=true` vs `false` on the same KB → the
   hybrid run surfaces the exact-term chunk the dense-only run misses; confidence buckets still populate.
5. `scripts/smoke.sh` for whole-stack wiring. Then feed the `use_lexical` A/B pair through
   `docs/eval/retrieval-eval.md` (recall@k / MRR) to quantify lift **before** defaulting the flag on.

## Files to touch

- **New:** `SparseVector.java`, `SparseEncoder.java`, `SparseEncoderProducer.java`,
  `Bm25SparseEncoder.java`, `Bm25SparseEncoderTest.java` (+ `QdrantClientTest`/`ChunkPipelineTest` cases).
- **Edit:** `QdrantClient.java` (hybrid create/ensure/upsert/query + `HybridPoint` + `CollectionInfo.sparse_vectors`/`hasSparseVectors` + shared query-parse helper), `ChunkPipeline.java` (ingest + search branches), `SearchRequest.java` (`useLexical`), `IngestTools.java` (`use_lexical` tool arg), REST search surface if present, `application.properties`, `CLAUDE.md`, `docs/components/qdrant-client.md`, `docs/architecture.md`.

## Rollout sequencing

1. Spine + Classic encoder + `use_lexical` + tests. Self-contained, shippable, zero new infra.
2. Fresh hybrid KB → run eval harness → tune `k1`/`b`/`avg-len`.
3. Flip `INGEST_SPARSE_ENABLED=true` as default once lift is proven. Existing KBs opt in by recreating.
4. Only if BM25 recall proves insufficient → implement the **Neu** plan (config flip, no spine change).

## Open questions / deferred decisions

- **Breadcrumbs in lexical text?** Default clean `c.text()`; revisit `include-breadcrumbs` if heading-term recall lags.
- **Capability cache?** Extra `getCollection` per search — add a short-TTL cache only if latency shows it.
- **RRF vs DBSF at the Qdrant seam?** Ship RRF; expose `ingest.sparse.fusion` (rrf|dbsf) later if score-blending helps.
- **`avg-len` auto-derivation?** Could derive from `ingest.chunk.size-chars` at startup instead of a hand-set constant — nice-to-have.
