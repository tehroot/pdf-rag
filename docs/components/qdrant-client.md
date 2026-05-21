# QdrantClient

`core/.../backend/qdrant/QdrantClient.java` (~410 lines). The only place
we encode Qdrant's REST shape. Plain `java.net.http.HttpClient` + Jackson,
same pattern as `Embedder`, `ColPaliClient`, and `OpenWebUiClient`.

## What it does

Eleven public methods covering the endpoints both pipelines need:

| Method | Endpoint | Use |
|--------|----------|-----|
| `listCollections()` | `GET /collections` | List collection names. |
| `getCollection(name)` | `GET /collections/{name}` | Metadata, or `null` on 404. |
| `createCollection(name, dim)` | `PUT /collections/{name}` | Single-vector collection with Cosine distance. |
| `ensureCollection(name, dim)` | get + create | Idempotent helper; rejects dim mismatch. |
| `deleteCollection(name)` | `DELETE /collections/{name}` | Idempotent on 404. |
| `upsertPoints(coll, points)` | `PUT /collections/{name}/points?wait=true` | Single-vector upsert. |
| `search(coll, vec, topK, filter)` | `POST /collections/{name}/points/search` | Single-vector ANN search with optional payload filter. |
| **`createMultivectorCollection(name, namedVectors)`** | `PUT /collections/{name}` | Multivector collection with named vectors, MAX_SIM, binary quantization. |
| **`ensureMultivectorCollection(name, namedVectors)`** | get + create | Idempotent; rejects single-vector existing collections. |
| **`upsertMultivectorPoints(coll, points)`** | `PUT /collections/{name}/points?wait=true` | Multivector upsert with `{name: float[][]}` per point. |
| **`queryMultistage(coll, prefetches, rerank, query, limit, filter)`** | `POST /collections/{name}/points/query` | Prefetch + rerank multistage query. |

Bold = added for the visual pipeline. Earlier methods are unchanged.

## Configuration

| Key | Env | Default |
|-----|-----|---------|
| `ingest.qdrant.url` | `QDRANT_URL` | `http://localhost:6333` |
| `ingest.qdrant.api-key` | `QDRANT_API_KEY` | *(empty)* |
| `ingest.qdrant.distance` | — | `Cosine` |
| `ingest.qdrant.connect-timeout-seconds` | — | `10` |
| `ingest.qdrant.request-timeout-seconds` | — | `120` |
| `ingest.qdrant.upsert-batch-size` | — | `128` |

Auth: `api-key` header (Qdrant's convention, not `Authorization: Bearer`).
Empty for local; set for Qdrant Cloud.

## Single-vector ops (text pipeline)

Unchanged from earlier. See [original-style docs] for the basic shapes:

- `createCollection(name, dim)` → `PUT /collections/{name}` body
  `{"vectors": {"size": dim, "distance": "Cosine"}}`
- `upsertPoints` → batched point list with `vector: [...]` per point
- `search` → POST `/points/search` with cosine query

`ensureCollection` checks for dim mismatch and throws — protects against
switching `EMBED_MODEL` without re-creating the collection.

## Multivector ops (visual pipeline) — NEW

### `MultiVectorConfig`

```java
public record MultiVectorConfig(int size, String distance, String comparator,
                                 boolean hnswEnabled, boolean binaryQuantize) {
    public static MultiVectorConfig pooled(int size);              // ANN-indexed, no quantization
    public static MultiVectorConfig originalRerankOnly(int size);  // HNSW disabled, binary quant
}
```

Two presets cover the typical ColPali setup:
- `pooled(dim)` for `pooled_rows` / `pooled_cols` — HNSW enabled, no
  quantization. Used as the fast ANN prefetch stage.
- `originalRerankOnly(dim)` for the `original` full-resolution vectors —
  `hnsw_config.m=0` (HNSW disabled, rerank-only) + binary quantization on
  with `always_ram=true`. Recovers ~16× storage on the largest of the three
  vector sets.

### `createMultivectorCollection(name, namedVectors)`

Wire shape:

```
PUT /collections/<name>
{
  "vectors": {
    "original": {
      "size": 128,
      "distance": "Cosine",
      "multivector_config": {"comparator": "max_sim"},
      "hnsw_config": {"m": 0},
      "quantization_config": {"binary": {"always_ram": true}}
    },
    "pooled_rows": {
      "size": 128,
      "distance": "Cosine",
      "multivector_config": {"comparator": "max_sim"}
    },
    "pooled_cols": { /* same as pooled_rows */ }
  }
}
```

`multivector_config.comparator: "max_sim"` is the ColBERT late-interaction
scoring: for each query token, find the maximum dot-product against any
document patch token; sum those maxes; that's the document score.

### `ensureMultivectorCollection(name, namedVectors)`

Idempotent. If the collection doesn't exist → create. If it does:
- If the existing collection has a single `vectors.size` (it's a regular
  single-vector collection at the name we want), throw — shape mismatch.
- Otherwise: no-op. (We don't deeply validate the named-vector layout
  matches; Qdrant will reject upserts with a clear error if the named
  vectors don't line up.)

### `upsertMultivectorPoints(coll, points)`

```java
public record MultiVectorPoint(String id,
                                Map<String, float[][]> vectors,
                                Map<String, Object> payload);
```

Wire shape:

```
PUT /collections/<name>/points?wait=true
{
  "points": [
    {
      "id": "uuid-here",
      "vector": {
        "original":     [[v1...], [v2...], ...],
        "pooled_rows":  [[r1...], [r2...], ...],
        "pooled_cols":  [[c1...], [c2...], ...]
      },
      "payload": { ... }
    }
  ]
}
```

`wait=true` — synchronous from the caller's perspective. Without it, the
upsert returns immediately but indexing happens asynchronously, leading to
the Qdrant analogue of the Open WebUI async-processing race.

### `queryMultistage(coll, prefetches, rerankUsing, rerankQuery, limit, filter)`

The unified Qdrant 1.10+ query API:

```
POST /collections/<name>/points/query
{
  "prefetch": [
    {"query": [[...], ...], "using": "pooled_rows", "limit": 100},
    {"query": [[...], ...], "using": "pooled_cols", "limit": 100}
  ],
  "query": [[...], ...],
  "using": "original",
  "limit": 10,
  "with_payload": true,
  "filter": {"must": [{"key": "source", "match": {"value": "..."}}]}
}
```

Qdrant executes the prefetches against pooled vectors (fast HNSW), unions
the candidates, then reranks against the full-resolution `original` vectors
(slow but accurate). Net effect: ~13× faster than original-only search with
near-identical quality.

```java
public record PrefetchSpec(String using, float[][] query, int limit);

public List<SearchHitRaw> queryMultistage(String collection,
                                          List<PrefetchSpec> prefetches,
                                          String rerankUsing,
                                          float[][] rerankQuery,
                                          int limit,
                                          Map<String, Object> filter);
```

Filter shape is the same flat-map → `{must: [...]}` translation as
`search()`. Passing null / empty filter omits the field entirely.

## DTOs

All `@JsonIgnoreProperties(ignoreUnknown = true)` because Qdrant adds fields
across versions.

- `CollectionSummary(name)` — list item.
- `CollectionInfo` — get response (single-vector dim accessor;
  `dim()` returns null for multivector collections because the JSON shape
  there is `vectors: {name1: {...}, name2: {...}}` which doesn't fit the
  single-`size` parse).
- `SearchHitRaw(id, score, payload)` — search/query result row.

## Failure modes

| Case | Throws |
|------|--------|
| Qdrant unreachable | `IngestException("I/O error calling Qdrant ...", IOException)`. |
| Non-2xx | `IngestException("Qdrant METHOD URL returned HTTP X: <body>")`. |
| 401/403 (Qdrant Cloud) | Same; fix `QDRANT_API_KEY`. |
| `getCollection` on missing | Returns null (not exception). |
| `deleteCollection` on missing | No-op (404 treated as success). |
| `ensureCollection` dim mismatch | Clear "dim=X but Y produced" message. |
| `ensureMultivectorCollection` on existing single-vector | Clear "is configured as single-vector" message. |
| Malformed JSON response | `IngestException("Failed to parse Qdrant response: ...")`. |

## Why it's like this

- **Plain `HttpClient` over qdrant-java-client.** Same as everywhere else.
  Smaller dep surface, easier to mock with WireMock, easier to reason about
  HTTP shapes.
- **Multivector methods separate from single-vector.** Could try to unify
  them at the API level, but the wire shapes are different enough (named
  vectors as objects vs single vector as array) that two surfaces is
  clearer than one with type-dispatched branches.
- **Preset factories on `MultiVectorConfig`.** Manually building the named
  vector config every time would be error-prone (HNSW config, quantization
  config, distance, comparator all have to line up). The two presets
  cover 99% of the ColPali use case.
- **`wait=true` everywhere.** Synchronous semantics. Async upsert is faster
  but creates a subtle race where a search immediately after upsert may
  miss the new points.
- **`getCollection` returns null on 404, but `deleteCollection` treats 404
  as success.** Asymmetric on purpose: callers of `getCollection` want to
  know "exists or not"; callers of `deleteCollection` want "ensure not".
- **HTTP/1.1 pinned.** Qdrant itself speaks HTTP/2 fine on the REST port,
  but we pin for consistency with the rest of the project and to insure
  against a future reverse-proxy deployment that doesn't grok h2c.

## Tests

`QdrantClientTest` (17 tests):

Single-vector (8):
- `listCollections`, `getCollection_null_on_404`, `getCollection_parses_dim`,
  `ensureCollection_creates_when_missing`, `ensureCollection_rejects_dim_mismatch`,
  `upsertPoints_sends_expected_body`, `search_passes_filter_and_parses_hits`,
  `sends_api_key_header_when_configured`.

Multivector (9):
- `createMultivectorCollection_sendsExpectedBody`
- `ensureMultivectorCollection_createsWhenMissing`
- `ensureMultivectorCollection_rejectsExistingSingleVectorCollection`
- `ensureMultivectorCollection_existingMultivector_noOps`
- `upsertMultivectorPoints_sendsNamedVectorBody`
- `queryMultistage_sendsExpectedShape_andParsesHits`
- `queryMultistage_emptyPrefetch_omitsField`
- `queryMultistage_invalidParams_throws`
- `multiVectorConfig_presets_haveExpectedShape`

WireMock fixtures verify the exact JSON body shape sent on the wire.
