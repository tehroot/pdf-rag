# Embedder

`core/src/main/java/org/example/backend/qdrant/Embedder.java` (~160 lines).
The OpenAI-compatible embeddings client. Project default target: llama.cpp's
`llama-server` started with `--embeddings`. Same code works against vLLM,
vanilla OpenAI, Together, LM Studio, or any other endpoint that speaks the
OpenAI `/v1/embeddings` schema.

## What it does

Turns a list of strings into a list of vectors, in order, in batches.

```
List<String>  ──┐
                ├──► POST <base-url>/embeddings  {model, input: [...]}
List<String>  ──┘             │
                              ▼
                       List<float[]>
```

Two methods:

```java
public float[] embedOne(String input);
public List<float[]> embed(List<String> inputs);
```

`embedOne` is for `search_documents` (one query vector per call); `embed` is
for `ingest_document` (one vector per chunk, batched).

## Interface

Config (`@ConfigProperty`, env overrides shown):

| Key | Env | Default |
|-----|-----|---------|
| `ingest.embed.base-url` | `EMBED_BASE_URL` | `http://localhost:8081/v1` |
| `ingest.embed.api-key` | `EMBED_API_KEY` | *(empty)* |
| `ingest.embed.model` | `EMBED_MODEL` | `bge-large-en-v1.5` |
| `ingest.embed.batch-size` | `EMBED_BATCH_SIZE` | `64` |
| `ingest.embed.connect-timeout-seconds` | — | `10` |
| `ingest.embed.request-timeout-seconds` | — | `120` |

The base URL must include `/v1` (llama-server, vLLM, and OpenAI all
namespace their OpenAI-compatible endpoints under `/v1`). The client appends
`/embeddings`.

## Internals

### Wire shape

Request:

```json
POST /v1/embeddings
Content-Type: application/json
Accept: application/json
Authorization: Bearer <api-key>      ← only if api-key is non-empty

{
  "model": "bge-large-en-v1.5",
  "input": ["chunk one text...", "chunk two text...", ...]
}
```

Response:

```json
{
  "data": [
    {"embedding": [0.123, -0.456, ...]},
    {"embedding": [0.789, -0.012, ...]},
    ...
  ]
}
```

Open-ended Jackson DTOs (`@JsonIgnoreProperties(ignoreUnknown=true)`) — the
endpoint may return `model`, `usage`, `object` and other fields we don't read.

### Batching

`embed(inputs)` splits the input list into slices of `batchSize` and calls
`embedBatch(slice)` for each:

```java
for (int i = 0; i < inputs.size(); i += batchSize) {
    int end = Math.min(i + batchSize, inputs.size());
    out.addAll(embedBatch(inputs.subList(i, end)));
}
```

Default `batchSize=64` is conservative — llama-server handles batches of 64
short text chunks comfortably on CPU, and modern vLLM deployments scale much
higher. Tune up for throughput, down if you hit memory limits on the embedding
server.

### Per-batch request

`embedBatch(batch)`:

1. Serialize `{model, input: [...]}` via the parent `ObjectMapper`.
2. Build the request — URL = `baseUrl + "/embeddings"`, `Content-Type:
   application/json`, `Accept: application/json`. Add the bearer header only
   if `apiKey` is non-empty (so llama-server with no auth doesn't get a
   meaningless `Authorization: Bearer ` header).
3. Send with the shared `HttpClient` (HTTP/1.1 pinned, see below).
4. Non-2xx → `IngestException` with status + body. The body usually contains
   a useful error message from the embedding server.
5. Parse the response into `EmbedResponse`.
6. **Size check.** If the server returned a different number of vectors than
   we asked for, throw. This catches a class of subtle bugs where a
   misconfigured server returns a single-element response for a multi-input
   batch.
7. Each `embedding` (a `List<Double>` over the wire — Jackson can't tell
   floats from doubles in JSON) gets converted to a `float[]`. Qdrant's
   storage is 32-bit so we don't keep the extra precision.

### `HttpClient` setup

```java
@PostConstruct
void init() {
    this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            .build();
}
```

`HTTP_1_1` is mandatory:

- llama-server's plaintext port is HTTP/1.1-only. It doesn't understand the
  cleartext `h2c` upgrade trio that `java.net.http` sends by default on HTTP/2;
  connections hang or close mid-request depending on the llama.cpp build.
- vLLM and Open WebUI both run on uvicorn, which actively rejects the trio
  with `400 Invalid HTTP request received`.
- Over HTTPS the issue disappears (ALPN negotiates h2 cleanly without a
  cleartext upgrade), but our local dev defaults are plain HTTP.

This pin matches the rest of the project — `OpenWebUiClient`, `FileFetcher`,
`QdrantClient` all do the same. See [../architecture.md](../architecture.md)
for the long version.

## Failure modes

| Case | Throws |
|------|--------|
| `inputs == null` or `inputs.isEmpty()` | Returns `[]` (no HTTP call). |
| Endpoint unreachable / network error | `IngestException("I/O error calling embeddings endpoint at ...", IOException)` |
| Endpoint returns non-2xx | `IngestException("Embeddings endpoint returned HTTP X: <body>")` — body included for diagnosis. |
| Endpoint returns wrong vector count | `IngestException("Embeddings response returned N vectors for M inputs")`. Usually a server-side rate-limit / batch-size mismatch — try lowering `EMBED_BATCH_SIZE`. |
| Endpoint returns malformed JSON | `IngestException("Failed to parse embeddings response", IOException)`. |
| Embedding contains `null` | `IngestException("Embedding response had a null vector")`. |

No retries. If you need them, wrap the call upstream — adding retries here
would obscure transient vs persistent failures and slow down the per-chunk
budget.

## Why it's like this

- **OpenAI-compat wire, generic name.** The class is named `Embedder`, not
  `LlamaServerEmbedder` or `VllmEmbedder`, because the request/response shape
  is OpenAI's and works against multiple servers. The defaults
  (`EMBED_BASE_URL`, `EMBED_MODEL`) target llama-server (see
  [[embedder-choice]]) but swapping to vLLM is a config change, not a code
  change.
- **Why `/v1/embeddings`, not llama-server's native `/embedding`.** llama-
  server exposes both. The native endpoint returns a flat array
  (`{"embedding": [...]}`) for a single input; the OpenAI-compat one supports
  batching, paginated responses, and the same shape vLLM/OpenAI return. One
  schema covers more backends.
- **`model` field is honored or ignored depending on the server.** llama-
  server ignores it (it serves whichever GGUF was loaded at startup); vLLM
  treats it as a routing hint when multiple models are loaded; OpenAI requires
  it match a known model name. The default `bge-large-en-v1.5` is a generic
  label that works as-is against llama-server. Override `EMBED_MODEL` for
  vLLM/OpenAI.
- **No streaming.** OpenAI's embeddings endpoint isn't streamable anyway; the
  whole response comes at once. The Open WebUI poll loop in
  [openwebui-backend.md](openwebui-backend.md) is the only place we do
  long-polling.
- **Auth header is conditional.** Sending `Authorization: Bearer ` (empty
  value) breaks some servers (vanilla OpenAI returns 401 with a confusing
  error). Sending no header at all is fine against llama-server (no auth) and
  against vanilla OpenAI (returns 401 with a clear "no API key" error).
- **Vectors come back as `float[]`, not `double[]`.** Qdrant's vector
  storage is 32-bit. We could keep `double` precision in memory until upsert
  and let Jackson serialize, but the per-chunk array is hot enough that the
  `float[]` micro-saving is worth the explicit conversion. Embeddings models
  produce ~Float32 precision intrinsically anyway.

## Tests

`EmbedderTest` (6 tests, WireMock):

- `embed_singleBatch_returnsVectorsInOrder` — happy path, two inputs in.
- `embed_splitsBatches_byBatchSize` — 6 inputs with `batchSize=3` → 2 POSTs.
- `embed_emptyList_returnsEmpty_withoutCallingHttp` — empty input never hits
  the wire.
- `omitsAuthorizationHeader_whenApiKeyBlank` — confirms no Authorization
  header is sent when `apiKey=""`.
- `non2xx_throwsIngestException` — 503 → `IngestException` with status.
- `mismatchedResultSize_throws` — server returns 1 vector for 2 inputs → throws.

Tests build the bean by hand, wire `ObjectMapper`, set `apiKey="test-key"` or
`""`, and reflectively invoke `init()`. No CDI, no live server.
