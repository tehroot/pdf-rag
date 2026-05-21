# ColPaliClient

`core/.../backend/qdrant/ColPaliClient.java` (~270 lines). The Java HTTP
client that talks to the Python ColPali sidecar. Plain `java.net.http.HttpClient`
+ Jackson, same pattern as the rest of the project's clients.

The sidecar is described in [colpali-sidecar.md](colpali-sidecar.md); this
doc covers the Java-side contract.

## What it does

Four operations, one per sidecar endpoint:

```java
public SidecarInfo getInfo();
public boolean isHealthy();
public List<PageEmbedding> embedPages(List<PageInput> pages);
public float[][] embedQuery(String query);
```

## Public types

```java
public record PageInput(String pageId, byte[] pngBytes);
public record PageEmbedding(String pageId,
                             float[][] original,
                             float[][] pooledRows,
                             float[][] pooledCols);

public static class SidecarInfo {   // @JsonIgnoreProperties(ignoreUnknown=true)
    public String  model_name;
    public Integer vector_dim;
    public Boolean supports_pooled;
    public List<String> pooled_methods;
    public Integer max_batch_size;
    public String  device;
}
```

The Java side stays model-agnostic via `SidecarInfo` — when the sidecar
reports `vector_dim=128` it's used for Qdrant collection setup, batch size
for client batching, etc.

## Configuration

| Key | Env | Default |
|-----|-----|---------|
| `ingest.colpali.sidecar-url` | `COLPALI_SIDECAR_URL` | `http://localhost:8090` |
| `ingest.colpali.connect-timeout-seconds` | — | `10` |
| `ingest.colpali.request-timeout-seconds` | — | `300` |
| `ingest.colpali.batch-size` | `COLPALI_BATCH_SIZE` | `8` |

Long request timeout (5 min) because CPU sidecar embed calls can be slow on
large batches. Connect timeout stays short — if the sidecar's down we want to
fail fast.

Note that `batch-size` here is the **client-side** batch size, separate from
the sidecar's own `COLPALI_MAX_BATCH_SIZE`. Client batches respect both —
client splits into batches of `min(client_batch_size, sidecar_max_batch_size)`.

## Internals

### HTTP/1.1 pin

```java
@PostConstruct
void init() {
    this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .build();
}
```

Same reason as the rest of the project. `java.net.http` defaults to HTTP/2
and sends a cleartext `h2c` upgrade on plain HTTP. uvicorn (which fronts the
Python sidecar) rejects the trio with `400 Invalid HTTP request received`
*before* parsing the body. Pinning to 1.1 is mandatory.

### `getInfo()` — sidecar self-report

Single GET to `/info`. Used by:
- `ColPaliPipeline` at ingest to record `embed_model` in the Qdrant payload.
- An operator's tooling to verify what's running.

### `isHealthy()` — pre-flight check

```java
public boolean isHealthy() {
    try {
        var resp = sendRaw(GET("/healthz"));
        if (resp.statusCode() / 100 != 2) return false;
        return parsed.ready;
    } catch (IngestException e) {
        return false;
    }
}
```

Catches all exceptions (network failures, timeouts, parse errors) and reports
false. Used by `QdrantBackend.ingest` to hard-fail before doing any
filesystem writes if the sidecar's unreachable.

### `embedPages(pages)` — batched embedding

```java
public List<PageEmbedding> embedPages(List<PageInput> pages) {
    if (pages.isEmpty()) return List.of();
    List<PageEmbedding> out = new ArrayList<>(pages.size());
    for (int i = 0; i < pages.size(); i += batchSize) {
        int end = Math.min(i + batchSize, pages.size());
        out.addAll(embedBatch(pages.subList(i, end)));
    }
    return out;
}
```

Each batch:
1. Base64-encode the PNG bytes (`Base64.getEncoder().encodeToString`).
2. Build `EmbedPagesRequest{pages, include_original=true, include_pooled=true}`.
3. POST to `/embed_pages`.
4. Parse `EmbedPagesResponse{embeddings: [...]}`.
5. Convert each `List<List<Double>>` to `float[][]` via `to2DFloat`.
6. Validate: response count must match batch input count.

The `List<List<Double>>` → `float[][]` conversion is the price of Jackson
parsing JSON numbers as Double by default; we cast down to float since
Qdrant stores 32-bit vectors anyway.

### `embedQuery(query)` — query encoding

One POST, no batching. Returns the multi-token query embedding as
`float[][]` (each row is one query token's vector). Empty / blank query
throws.

### Size validation

Both `embedPages` and `embedQuery` validate that the response shape matches
what we asked for:

```java
if (parsed.embeddings.size() != batch.size()) {
    throw new IngestException("Sidecar returned " + got + " embeddings for "
            + batch.size() + " pages");
}
```

Catches a class of subtle bugs where a misconfigured sidecar returns fewer
results than asked. Failing here surfaces the misconfiguration; failing later
in `QdrantClient.upsertMultivectorPoints` would be confusing.

## Failure modes

| Case | Throws |
|------|--------|
| Sidecar unreachable | `IngestException("I/O error calling ColPali sidecar at ...", IOException)`. |
| Non-2xx response | `IngestException` with status + body. The sidecar's error message is propagated. |
| Sidecar returns wrong embedding count | `IngestException("Sidecar returned N embeddings for M pages")`. |
| Empty PNG passed to `embedPages` | `IngestException("Cannot embed empty PNG for page ...")` — fail fast before the HTTP call. |
| Blank query | `IngestException("query is required")`. |
| Empty query vectors in response | `IngestException("Sidecar returned no query vectors")`. |
| Malformed JSON response | `IngestException("Failed to parse ColPali sidecar response: ...", IOException)`. |
| Network error during `isHealthy` | Returns false (doesn't throw). |

No retries. Sidecar transient errors propagate; the caller decides whether
to retry the whole ingest.

## Why it's like this

- **`java.net.http` over Quarkus REST Client.** Same reasons as everywhere
  else: explicit wire-level control, easy multipart / large-payload handling,
  one place to apply the HTTP/1.1 pin.
- **`float[][]` over `List<List<Float>>`.** Qdrant stores 32-bit; downstream
  code wants primitive arrays for serialization efficiency.
- **Validate response counts.** Cheap defensive check; turns subtle "wrong
  vector for wrong page" bugs into clear errors.
- **No retries.** Retries belong upstream (in a job-queue or in the agent's
  call pattern). Embedding a single page is cheap; the typical retry case
  is the whole ingest job, not a sub-batch.
- **`isHealthy()` swallows exceptions.** Designed for pre-flight checks
  where the caller's question is "should I attempt this expensive operation?"
  — not "tell me exactly why the sidecar's down." The detailed error surfaces
  on the actual operation if you proceed without health checking.

## Tests

`ColPaliClientTest` (14 tests, WireMock):

- `getInfo` parse correctness.
- `isHealthy`: ready=true, ready=false, server down (catches Exception),
  5xx response.
- `embedPages`: single batch, batched split, empty input no-op, mismatched
  response count, empty PNG rejection, non-2xx propagation.
- `embedQuery`: success, blank query, empty vectors response.

The `embed_pages_splitsByBatchSize` test uses `batchSize=4` with 9 inputs to
verify the split is `4 + 4 + 1`.
