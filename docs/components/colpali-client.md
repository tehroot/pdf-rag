# ColPaliClient

`core/.../backend/qdrant/ColPaliClient.java` (~420 lines). The Java HTTP
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
| `ingest.colpali.wire-encoding` | — | `f32b64` (`json` / `f32b64` / `f16b64`) |

Long request timeout (5 min) because CPU sidecar embed calls can be slow on
large batches. Connect timeout stays short — if the sidecar's down we want to
fail fast.

`batch-size` here is the **client-side** batch size, separate from the
sidecar's own `COLPALI_MAX_BATCH_SIZE`; the client splits into batches of
`min(client_batch_size, sidecar_max_batch_size)`.

`wire-encoding` is the encoding the client *requests* for the page vectors
(see [Wire encoding](#wire-encoding-and-decodevectors)). A blank value sends
`json`.

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
2. Build `EmbedPagesRequest{pages, include_original=true, include_pooled=true,
   encoding=<wire-encoding>}`.
3. POST to `/embed_pages` (one retry on I/O error, see below).
4. Parse `EmbedPagesResponse{embeddings: [...]}`. The three vector fields of
   each `PageEmbeddingDto` are `JsonNode`, plus `Integer dim` and
   `String encoding` (present with the base64 encodings).
5. Validate: response count must match batch input count.
6. Decode each field to `float[][]` via `decodeVectors(node, dim, encoding)`.

### Wire encoding and `decodeVectors`

```java
public static float[][] decodeVectors(JsonNode node, Integer dim, String encoding)
```

Accepts either form per field, so the two sides roll independently:

- **JSON array of arrays** (encoding `json`, or any sidecar that predates
  the request field and ignored it) → `to2DFloat`: each number read as a
  double and cast to float. Qdrant stores 32-bit anyway.
- **Base64 string** (`f32b64` / `f16b64`) → `Base64` decode, then read
  row-major little-endian floats, `dim` per row: 4 bytes per value for
  float32 (`FloatBuffer` bulk get), 2 bytes for float16
  (`Float.float16ToFloat`). `""` is an empty array. Throws if `dim` is
  missing or not positive, or if the byte count is not a multiple of
  `width × dim`.
- `null` / missing node → empty array; any other node type throws.

Why the default is `f32b64` (from the source comment and
[../plans/sidecar-throughput-v1.md](../plans/sidecar-throughput-v1.md)):
bit-identical to `json`, 2.3× smaller, one-pass decode. JSON parsing of a
12-page batch built ~5 million boxed Doubles per worker and set the ingest
JVM's heap ceiling (R530, 2026-09-17); after the switch, bytes per 12-page
batch through the balancer fell from 61.8 MB to 26.9 MB and the heap-space
retries went to 0. `f16b64` is 4.6× smaller than JSON and exact for the
model's bf16 outputs above 6.1e-5 in magnitude.

### `embedQuery(query)` — query encoding

One POST, no batching. Returns the multi-token query embedding as
`float[][]` (each row is one query token's vector). Empty / blank query
throws.

### Retry once, then `SidecarUnavailableException`

```java
static final int SEND_ATTEMPTS = 2;
static final long RETRY_DELAY_MS = 2_000;
```

`sendRaw` (used by every call) sends up to `SEND_ATTEMPTS` times. An
`IOException` is logged at WARN with the attempt number, exception class
and message, then retried once after 2 s on a fresh connection. If both
attempts fail it throws `SidecarUnavailableException` (a subclass of
`IngestException`) naming the URI, the attempt count and the last cause.
Embedding is a pure function of the request, so a retry is safe.

The observation behind it (R530 pool, 2026-09-17, from the source comment):
about 0.5% of embed POSTs failed with an `IOException` while the balancer
logged a 200 for every request it saw and no TCP close crossed the wire —
a client-side condition. A second attempt is cheap; if it also fails the
queue worker requeues the job as transient rather than failing it.

A **502 / 503 / 504** response also throws `SidecarUnavailableException`
(no retry here): those come from a proxy or balancer in front of the
sidecar(s) — no live upstream, restart window — so they are an environment
condition. Other non-2xx codes throw a plain `IngestException`.

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
| Sidecar unreachable / I/O error on both attempts | `SidecarUnavailableException("I/O error calling ColPali sidecar at ... after 2 attempts: <class>: <message>")`. Each failed attempt is WARN-logged with its cause. |
| 502 / 503 / 504 | `SidecarUnavailableException` with status + body (balancer / proxy has no live upstream). |
| Other non-2xx response | `IngestException` with status + body. The sidecar's error message is propagated. |
| Base64 vectors without a positive `dim`, or byte count not a multiple of `width × dim` | `IngestException`. |
| Interrupted during send or retry sleep | `IngestException("Interrupted calling ColPali sidecar at ...")`, interrupt flag re-set — the worker reads that flag as shutdown. |
| Sidecar returns wrong embedding count | `IngestException("Sidecar returned N embeddings for M pages")`. |
| Empty PNG passed to `embedPages` | `IngestException("Cannot embed empty PNG for page ...")` — fail fast before the HTTP call. |
| Blank query | `IngestException("query is required")`. |
| Empty query vectors in response | `IngestException("Sidecar returned no query vectors")`. |
| Malformed JSON response | `IngestException("Failed to parse ColPali sidecar response: ...", IOException)`. |
| Network error during `isHealthy` | Returns false (doesn't throw). |

One retry per request on I/O error, none on HTTP errors. Beyond that,
transient errors propagate as `SidecarUnavailableException` and the
queue worker requeues the job ([ingest-queue.md](ingest-queue.md)); a
synchronous caller sees the exception.

## Why it's like this

- **`java.net.http` over Quarkus REST Client.** Same reasons as everywhere
  else: explicit wire-level control, easy multipart / large-payload handling,
  one place to apply the HTTP/1.1 pin.
- **`float[][]` over `List<List<Float>>`.** Qdrant stores 32-bit; downstream
  code wants primitive arrays for serialization efficiency.
- **`JsonNode` DTO fields, decoded by hand.** One DTO serves both the JSON
  and the base64 encodings, and the base64 path never materializes a boxed
  number per value.
- **Validate response counts.** Cheap defensive check; turns subtle "wrong
  vector for wrong page" bugs into clear errors.
- **Exactly one retry, on I/O errors only.** Job-level retries still belong
  upstream (the queue worker's transient requeue). The single in-client
  retry exists because the observed failures were client-side and cleared
  on a fresh connection; retrying HTTP error codes would mask real faults.
- **`isHealthy()` swallows exceptions.** Designed for pre-flight checks
  where the caller's question is "should I attempt this expensive operation?"
  — not "tell me exactly why the sidecar's down." The detailed error surfaces
  on the actual operation if you proceed without health checking.

## Tests

`ColPaliClientTest` (17 tests, WireMock):

- `getInfo` parse correctness.
- `isHealthy`: ready=true, ready=false, server down (catches Exception),
  5xx response.
- `embedPages`: single batch, batched split, empty input no-op, mismatched
  response count, empty PNG rejection, non-2xx propagation.
- Wire encodings: `embedPages_requestsBinaryEncoding_andDecodesFloat32Base64`
  (the request carries `encoding`, a base64 float32 body decodes),
  `decodeVectors_float16Base64`, `decodeVectors_rejectsMisalignedBytes`.
- `embedQuery`: success, blank query, empty vectors response.

The `embed_pages_splitsByBatchSize` test uses `batchSize=4` with 9 inputs to
verify the split is `4 + 4 + 1`.
