# Plan: Lexical hybrid — "Neu" (learned sparse: SPLADE / BM42 via sidecar) (v1)

Status: design converged, additive follow-on to the Classic plan
Author drafted: 2026-07-06
Depends on: [lexical-bm25-hybrid-classic-v1.md](lexical-bm25-hybrid-classic-v1.md) — this plan reuses
that plan's entire hybrid collection/upsert/query spine and only swaps the *encoder* + a couple of
config keys.

## Context

The [Classic plan](lexical-bm25-hybrid-classic-v1.md) adds a lexical channel with **client-side BM25** (exact-term matching, zero new infra).
BM25's ceiling is that it only matches **surface tokens** — it can't bridge `"heart attack"` ↔
`"myocardial infarction"`, singular/plural, or synonyms. **Learned sparse** models fix that: they emit
a sparse vector whose non-zero dimensions are **expanded vocabulary terms** with learned weights, so a
document about "myocardial infarction" also lights up the `heart`/`attack` dimensions. You keep the
interpretability and exact-match strength of sparse retrieval **and** gain semantic term expansion —
often beating both BM25 and dense on out-of-domain retrieval, at the cost of running a model.

Two concrete model families:
- **SPLADE** (e.g. `naver/splade-cocondenser-ensembledistil`) — MLM-head expansion over the full
  WordPiece vocab; weights already encode term importance → **no IDF modifier**.
- **BM42** (Qdrant's transformer-attention reweighting of the document's own tokens) — lighter, no full
  expansion; **pairs with Qdrant's `modifier:"idf"`** just like BM25.

This is the path to reach for **only if** the Classic eval shows BM25 recall is the bottleneck. It is a
config flip (`ingest.sparse.mode=learned`) plus one new Java client and a sidecar endpoint — **no
change to the hybrid spine, the fusion layer, or the search flow**.

## What is reused vs. what is new

**Reused unchanged from the Classic plan** (see that doc for full detail):
- `SparseVector` record, `SparseEncoder` interface, `SparseEncoderProducer` (adds the `learned` branch).
- `QdrantClient.createHybridCollection / ensureHybridCollection / upsertHybridPoints / queryHybrid`,
  `HybridPoint`, `CollectionInfo.sparse_vectors` + `hasSparseVectors()`.
- `ChunkPipeline` ingest/search branching, the ÷max score normalization, the `use_lexical` toggle.
- All the Java hybrid tests.

**New in this plan:**
- `LearnedSparseEncoder` (Java HTTP client) — the `SparseEncoder` impl for `mode=learned`.
- A batch method on `SparseEncoder` (below) so ingest doesn't make one HTTP call per chunk.
- Two sidecar endpoints (`/embed_sparse`, `/embed_sparse_query`) + `/info` extension, in the existing
  Python `sidecar/` project.
- Cross-encoder safety rules (a KB is bound to the encoder that built it).
- Config keys + an optional compose wiring.

### Spine refinement 1 — batch the document encode

The Classic interface has single-text `encodeDocument`. BM25 is CPU-cheap so per-chunk is fine; a
learned encoder over HTTP must batch. Add a **default batch method** to `SparseEncoder`:

```java
default List<SparseVector> encodeDocuments(List<String> texts) {   // BM25 inherits this loop
    List<SparseVector> out = new ArrayList<>(texts.size());
    for (String t : texts) out.add(encodeDocument(t));
    return out;
}
```
`LearnedSparseEncoder` overrides it with one batched `/embed_sparse` call (client-side batching by
`ingest.sparse.learned.batch-size`, like `ColPaliClient.embedPages`). `ChunkPipeline` ingest switches
from the per-chunk `sparse.encodeDocument(c.text())` loop to a single
`sparse.encodeDocuments(chunkTexts)` call — a backwards-compatible spine tweak that also slightly
speeds the Classic path. Query stays single (`encodeQuery`, one query per search).

### Spine refinement 2 — a KB is bound to its encoder

BM25 vectors and SPLADE vectors are **not interchangeable** (different index spaces, different weight
semantics, different IDF setting). A KB built by one encoder cannot be searched by the other. We encode
the encoder identity in the **sparse vector name**:

- Classic BM25 → `text_bm25` (IDF on).
- SPLADE → `text_splade` (IDF off). BM42 → `text_bm42` (IDF on).

Then make search **derive the sparse vector name from the collection**, not from active config:

```java
// in searchChunks hybrid branch, from CollectionInfo:
String kbSparseName = info.sparseVectorNames().stream().findFirst().orElse(null); // the collection's actual name
if (kbSparseName != null && kbSparseName.equals(sparse.vectorName())) {
    // active encoder matches how this KB was built → hybrid search
} else {
    warnings.add("KB '" + kb + "' lexical channel was built by a different encoder ("
               + kbSparseName + ") than the active one (" + sparse.vectorName() + "); "
               + "searching dense-only. Recreate the KB or switch ingest.sparse.mode to match.");
    // dense-only fallback
}
```
`CollectionInfo.sparseVectorNames()` = `config.params.sparse_vectors.keySet()`. This makes mode changes
**safe by construction**: switching `ingest.sparse.mode` never silently queries a KB with the wrong
encoder — it degrades to dense + warns. Switching encoders for real = create a fresh KB and re-ingest
(the same "fresh KB" rule the project already uses for chunking-strategy changes).

## Neu encoder — `LearnedSparseEncoder` (Java)

`@ApplicationScoped`, `@PostConstruct init()` building an `HttpClient` pinned to **HTTP/1.1** (the
project-wide gotcha — uvicorn rejects the h2c upgrade), exactly like `ColPaliClient`/`Embedder`.

```java
@ApplicationScoped
public class LearnedSparseEncoder implements SparseEncoder {
    @ConfigProperty(name = "ingest.sparse.enabled", defaultValue = "false") boolean enabled;
    @ConfigProperty(name = "ingest.sparse.vector-name", defaultValue = "text_splade") String vectorName;
    @ConfigProperty(name = "ingest.sparse.learned.url") String baseUrl;         // reuse colpali sidecar by default
    @ConfigProperty(name = "ingest.sparse.learned.idf", defaultValue = "false") boolean useIdf;  // SPLADE=false, BM42=true
    @ConfigProperty(name = "ingest.sparse.learned.batch-size", defaultValue = "16") int batchSize;
    @ConfigProperty(name = "ingest.sparse.learned.connect-timeout-seconds", defaultValue = "10") long connectTimeout;
    @ConfigProperty(name = "ingest.sparse.learned.request-timeout-seconds", defaultValue = "120") long requestTimeout;
    @Inject ObjectMapper objectMapper;
    private HttpClient http;

    @PostConstruct void init() { http = HttpClient.newBuilder().version(HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(connectTimeout)).build(); }

    @Override public List<SparseVector> encodeDocuments(List<String> texts) {
        // batch by batchSize → POST /embed_sparse {texts:[...]} → parse [{indices,values}] → SparseVector[]
    }
    @Override public SparseVector encodeDocument(String text) { return encodeDocuments(List.of(text)).get(0); }
    @Override public SparseVector encodeQuery(String text) {
        // POST /embed_sparse_query {text} → {indices,values}
    }
    @Override public boolean usesIdfModifier() { return useIdf; }
    @Override public String vectorName() { return vectorName; }
    @Override public boolean enabled() { return enabled; }
}
```

DTOs (Jackson records, `@JsonIgnoreProperties(ignoreUnknown=true)`), mirroring `ColPaliClient`'s style:
```java
record EmbedSparseRequest(List<String> texts) {}
record SparseVec(int[] indices, float[] values) {}
record EmbedSparseResponse(List<SparseVec> vectors) {}
record EmbedSparseQueryRequest(String text) {}
```

Pre-flight health: reuse the sidecar's `/healthz`. Ingest with `mode=learned` + sidecar down → hard-fail
(same asymmetry as ColPali: fail-closed on ingest, degrade on search). If preferred, search with sidecar
down can soft-degrade to dense-only + warning — cheaper than failing the whole query. Pick one and test
(recommendation: **hard-fail ingest, soft-degrade search**, matching the ColPali contract).

## Sidecar contract additions  *(`sidecar/`, FastAPI — the existing ColPali service)*

The sidecar is already a model-agnostic FastAPI app. Add a **lazily-loaded** learned-sparse model
alongside ColPali (or run a standalone sparse sidecar — see deployment options). New endpoints:

| Method | Path | Use |
|---|---|---|
| POST | `/embed_sparse` | batch documents → `[{indices, values}]` |
| POST | `/embed_sparse_query` | one query → `{indices, values}` |
| GET | `/info` | extended to report `sparse_model_name`, `sparse_vocab_size`, `uses_idf` |
| GET | `/healthz` | extended: `sparse_ready: bool` |

Request/response JSON:
```jsonc
// POST /embed_sparse
{ "texts": ["…doc chunk 1…", "…doc chunk 2…"] }
// → 200
{ "vectors": [ { "indices": [1045, 2298, 30104], "values": [1.83, 0.42, 2.11] },
               { "indices": [88, 512], "values": [0.9, 1.4] } ] }

// POST /embed_sparse_query
{ "text": "myocardial infarction treatment" }
// → 200
{ "indices": [1045, 2298, 4319, 8210], "values": [1.4, 1.1, 0.7, 0.9] }
```

**SPLADE sparsification** (the reference impl):
```python
# logits: [seq_len, vocab]  from the MLM head
weights = torch.log1p(torch.relu(logits))          # SPLADE activation
pooled  = weights.max(dim=0).values                # max-pool over sequence → [vocab]
nz      = pooled.nonzero().squeeze(-1)             # keep non-zero dims
indices = nz.tolist()                              # vocab token ids ARE the sparse indices (u32-safe, ~30k)
values  = pooled[nz].tolist()
```
Key property: **indices are vocabulary token ids**, identical between documents and queries, so no
hashing and no collisions (cleaner than BM25's hashed ids). `uses_idf=false` for SPLADE (weights are
learned); for **BM42** the sidecar returns attention-reweighted document-token ids and `uses_idf=true`
(Qdrant supplies IDF). Model choice via `COLPALI_MODEL`-style env (`SPARSE_MODEL`); the Java side stays
model-agnostic via `/info`, exactly as it is for ColPali.

**Testing the sidecar** (Python `pytest`, mirroring the existing 26-test suite that uses a
`FakeModelHandle` — no torch needed): a `FakeSparseModel` returning deterministic
`{indices, values}` for given texts; assert endpoint shapes, batching, empty-text handling,
`/info`/`/healthz` fields.

## Deployment options (pick one)

1. **Co-locate in the existing ColPali sidecar** *(recommended)* — add the endpoints + a lazily-loaded
   SPLADE model to the current FastAPI app/container. Fewest moving parts, one sidecar URL
   (`SPARSE_SIDECAR_URL` defaults to `COLPALI_SIDECAR_URL`). Caveat: memory pressure on the R530 3070
   (ColQwen2 + SPLADE co-resident). SPLADE is small (~110–130M params), usually fine; can pin SPLADE to
   CPU while ColPali holds the GPU (`SPARSE_DEVICE=cpu`).
2. **Standalone sparse sidecar** — new container/port (`:8091`), own image. Clean isolation and
   independent scaling; more infra (a second Dockerfile/compose service, another health check). Follow
   the existing `scripts/` + `docker-compose.gpu.yml` patterns.

Compose: add the model env + (if standalone) a service to `docker-compose.yml` / `.gpu.yml`, and a
`SPARSE_SIDECAR_URL` to the `pdf-rag-http` service. Extend `scripts/up.sh`/`status.sh` health probes.

## Configuration deltas (on top of the Classic keys)

```properties
ingest.sparse.mode=${INGEST_SPARSE_MODE:learned}             # flip from bm25
ingest.sparse.vector-name=${INGEST_SPARSE_VECTOR_NAME:text_splade}   # encoder-identifying name
ingest.sparse.learned.url=${SPARSE_SIDECAR_URL:http://localhost:8090} # default = colpali sidecar
ingest.sparse.learned.model=${SPARSE_MODEL:naver/splade-cocondenser-ensembledistil}
ingest.sparse.learned.idf=${SPARSE_LEARNED_IDF:false}        # SPLADE=false, BM42=true
ingest.sparse.learned.batch-size=${SPARSE_BATCH_SIZE:16}
ingest.sparse.learned.connect-timeout-seconds=10
ingest.sparse.learned.request-timeout-seconds=120
# sidecar side:
# SPARSE_MODEL, SPARSE_DEVICE=cuda|cpu
```

## Testing

- **`LearnedSparseEncoderTest`** (Java, WireMock stub of the sidecar; bean-by-reflection like
  `ColPaliClientTest`): batched `/embed_sparse` request shape + response parse; `encodeQuery` shape;
  batching splits at `batch-size`; HTTP/1.1 pin; empty-text; sidecar-error propagation.
- **`QdrantClientTest`**: `createHybridCollection_noIdf_omitsModifier` already covers the SPLADE (IDF-off)
  schema (shared with the Classic plan).
- **`ChunkPipelineTest`**: `mode=learned` ingest calls `encodeDocuments` once (batched) and upserts the
  `text_splade` named vector; **cross-encoder guard** — a KB with `text_bm25` searched under a `learned`
  encoder → dense-only + warning.
- **Sidecar `pytest`**: the `FakeSparseModel` endpoint tests above.
- **Regression:** the entire Classic + fusion test set stays green.

## Verification (end-to-end)

1. `mvn -pl core test` + `sidecar` `pytest` green.
2. Bring up the sidecar with the sparse model (`SPARSE_MODEL=…`, `scripts/up.sh`); `GET /info` shows
   `sparse_model_name`/`uses_idf`; `GET /healthz` shows `sparse_ready:true`.
3. `INGEST_SPARSE_ENABLED=true INGEST_SPARSE_MODE=learned` → ingest into a **fresh** KB → confirm
   `GET /collections/<kb>` shows `sparse_vectors.text_splade` (no `modifier` for SPLADE) and points carry
   `dense` + `text_splade`.
4. Query a synonym/paraphrase case where BM25 fails (e.g. query "MI" against docs saying "myocardial
   infarction"): the SPLADE hybrid run should retrieve it; BM25 hybrid on a parallel KB won't. Compare
   both against dense-only via `use_lexical`.
5. Feed all three (dense-only / BM25-hybrid / SPLADE-hybrid) through `docs/eval/retrieval-eval.md` to
   decide whether the learned encoder's lift justifies the extra serving cost.

## Files to touch

- **New (Java):** `LearnedSparseEncoder.java`, `LearnedSparseEncoderTest.java`.
- **Edit (Java):** `SparseEncoder.java` (add `encodeDocuments` default), `SparseEncoderProducer.java`
  (wire the `learned` branch), `ChunkPipeline.java` (batched `encodeDocuments` + cross-encoder guard),
  `QdrantClient.CollectionInfo` (`sparseVectorNames()`), `application.properties`, `CLAUDE.md`,
  `docs/components/colpali-sidecar.md` (document the sparse endpoints), `docs/architecture.md`.
- **New/Edit (Python `sidecar/`):** `/embed_sparse` + `/embed_sparse_query` routes, sparse model handle,
  `/info`/`/healthz` extensions, `FakeSparseModel` + tests, `pyproject.toml` ml-deps
  (`transformers`, the model), README.
- **Ops:** compose env/service, `scripts/` health probes, GPU/CPU device flag.

## Decision criteria — when to actually build this

Adopt Neu **only after** the Classic plan is live and evaluated, and the eval shows:
- BM25 hybrid still misses a meaningful class of paraphrase/synonym queries the users actually issue, **and**
- the extra infra (a served model, GPU/CPU budget, ops surface) is justified by the measured recall lift.

If BM25 hybrid already closes most of the dense-only gap, **stop at Classic** — Neu's term expansion is a
quality knob, not a correctness fix, and it re-introduces the "serve another model" cost this whole line
of work was trying to avoid.

## Open questions / deferred

- **SPLADE vs BM42** — SPLADE = stronger expansion, larger vectors (slower, bigger index); BM42 = lighter,
  IDF-backed, closer to BM25. Bench both on the eval set; the sidecar can serve either via `SPARSE_MODEL`.
- **Vector size / index cost** — SPLADE vectors have more non-zeros than BM25; watch Qdrant sparse index
  size and query latency. A top-k pruning of SPLADE weights (keep highest-N terms) is a tunable if needed.
- **GPU co-residency** — measure ColQwen2 + SPLADE memory on the 3070 before committing to co-location vs
  a standalone sidecar; CPU-pinned SPLADE is the safe default.
- **Unified `text_sparse` name?** — encoder-specific names (`text_bm25`/`text_splade`) power the
  cross-encoder guard; a single generic name would need separate metadata to record the encoder. Keep
  encoder-specific.
