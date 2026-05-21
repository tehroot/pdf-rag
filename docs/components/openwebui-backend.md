# OpenWebUiBackend + helpers

`core/src/main/java/org/hayden/backend/openwebui/`. The parallel backend.
Where Qdrant means "we own the RAG", Open WebUI means "we hand a raw file
to Open WebUI and let it do extraction + chunking + embedding + storage".

The four classes that make it work:

| File | Role |
|------|------|
| `OpenWebUiBackend.java` | Implements `Backend`. Orchestrates the four-step pipeline below. |
| `OpenWebUiClient.java` | The only place Open WebUI HTTP shapes are encoded. Plain `java.net.http` + Jackson. |
| `KnowledgeService.java` | `findOrCreate(name, description)` — list KBs, match by name, create if missing. |
| `FileUploadService.java` | `upload(...)` + `waitUntilProcessed(fileId, timeout)`. Owns the poll loop. |
| `OpenWebUiException.java` | Carries the HTTP status code along with the message. |
| `dto/*.java` | Jackson records for every wire shape — all `@JsonIgnoreProperties(ignoreUnknown=true)`. |

## What it does

Five HTTP endpoints, in order:

```
                           ┌─────────────────────────┐
1. GET  /api/v1/knowledge/ │ KnowledgeService        │   "do we have this KB?"
2. POST /api/v1/knowledge/create  │.findOrCreate     │   "no → create one"
                           └────────────┬────────────┘
                                        │ KB id
                                        ▼
                           ┌─────────────────────────┐
3. POST /api/v1/files/     │ OpenWebUiClient         │   multipart upload, returns file id
                           │.uploadFile              │   (processing has NOT started yet)
                           └────────────┬────────────┘
                                        │ file id
                                        ▼
                           ┌─────────────────────────┐
4. GET  /api/v1/files/{id}/process/status  │ poll    │   200ms → 400ms → ... → 2000ms cap
   (until status=="completed" or "failed") │         │   stop when Open WebUI is done
                           └────────────┬────────────┘
                                        │
                                        ▼ (if completed)
                           ┌─────────────────────────┐
5. POST /api/v1/knowledge/{kbId}/file/add │           │   attach file to KB
                           └─────────────────────────┘
```

The poll in step 4 is what makes this a "synchronous" tool call from the
agent's perspective despite Open WebUI's async backend.

## Interface

### `OpenWebUiBackend`

```java
@ApplicationScoped
public class OpenWebUiBackend implements Backend {
    public static final String NAME = "openwebui";

    @Override public String name() { return NAME; }
    @Override public IngestResult ingest(IngestRequest req);
    @Override public SearchResponse search(SearchRequest req);   // throws — unsupported
    @Override public List<KnowledgeBaseSummary> listKnowledgeBases();

    public ProcessStatus getFileStatus(String fileId);            // the diagnostic tool
}
```

The `getFileStatus(fileId)` method is the one tool entry that bypasses the
dispatcher — the `get_file_status` MCP tool injects `OpenWebUiBackend`
directly because the operation is backend-specific.

### `OpenWebUiClient`

Five methods, one per endpoint:

```java
public List<KnowledgeBase> listKnowledgeBases();
public KnowledgeBase createKnowledgeBase(String name, String description);
public UploadedFile uploadFile(String filename, String contentType, byte[] content);
public ProcessStatus getFileProcessStatus(String fileId);
public void addFileToKnowledgeBase(String knowledgeBaseId, String fileId);
```

### Config

| Key | Env | Default |
|-----|-----|---------|
| `ingest.openwebui.base-url` | `OPEN_WEBUI_BASE_URL` | `http://localhost:3000` |
| `ingest.openwebui.api-key` | `OPEN_WEBUI_API_KEY` | *(empty)* |
| `ingest.openwebui.connect-timeout-seconds` | — | `10` |
| `ingest.openwebui.request-timeout-seconds` | — | `120` |
| `ingest.openwebui.poll.initial-ms` | — | `200` |
| `ingest.openwebui.poll.max-ms` | — | `2000` |

## Internals

### Step 1+2: find-or-create the KB (`KnowledgeService`)

```java
public KnowledgeBase findOrCreate(String name, String description) {
    for (KnowledgeBase kb : client.listKnowledgeBases()) {
        if (name.equals(kb.name())) return kb;
    }
    return client.createKnowledgeBase(name, description);
}
```

Linear scan. KB lists are tiny (single-digit to low-double-digit in normal
use), so this is fine. Case-sensitive equality — `"Docs"` and `"docs"` are
different KBs.

`GET /api/v1/knowledge/` returns `{items: [...], total: N}`. **Critical:**
many Open WebUI docs and discussion threads show this endpoint returning a
bare array. The 0.9.x live server actually wraps it. `KnowledgePage` is the
record that unwraps; collapsing back to a bare-array parse would silently
return `[]` and `findOrCreate` would create a new KB every time.

### Step 3: multipart upload (`OpenWebUiClient.uploadFile`)

`POST /api/v1/files/` — `multipart/form-data` with one part:

```
--<boundary>
Content-Disposition: form-data; name="file"; filename="<safe filename>"
Content-Type: <content-type>

<raw bytes>
--<boundary>--
```

Boundary is `----openwebui-<random UUID>`. Filename quotes are escaped
(`"` → `_`) to avoid breaking the Content-Disposition header. The multipart
body is built into a `byte[]` via `ByteArrayOutputStream` — Open WebUI's
server (uvicorn + httptools) is fine with non-streaming bodies, and a
single byte array makes `Content-Length` trivial.

Returns immediately with the file id. **Processing has not started.**

### Step 4: poll for completion (`FileUploadService.waitUntilProcessed`)

```java
public ProcessStatus waitUntilProcessed(String fileId, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    long sleepMs = initialPollMs;       // 200
    while (true) {
        ProcessStatus status = client.getFileProcessStatus(fileId);
        if (status.completed() || status.failed()) return status;
        if (Instant.now().isAfter(deadline)) throw new OpenWebUiException(...);
        Thread.sleep(sleepMs);
        sleepMs = Math.min(maxPollMs, sleepMs * 2);  // exponential backoff to 2000ms cap
    }
}
```

Backoff sequence: 200, 400, 800, 1600, 2000, 2000, 2000, … Most small files
complete after 1-2 polls. Default timeout (passed via the tool's
`poll_timeout_seconds`, default 300) is generous enough for embedding-heavy
pipelines on large docs.

### Step 5: attach to KB (`OpenWebUiClient.addFileToKnowledgeBase`)

`POST /api/v1/knowledge/{kbId}/file/add` with body `{"file_id": "..."}`.

The crucial detail: this must come *after* step 4 sees `completed`. If you
call this while Open WebUI's status is still `pending`, you get
`400 "The content provided is empty"`. The poll loop is the guard.

If status comes back `failed`, we skip step 5 entirely and report the failure
in `IngestResult.message`.

### HTTP client setup

```java
this.http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
        .build();
```

Same HTTP/1.1 pin as everywhere else. Open WebUI's uvicorn rejects the
cleartext `h2c` upgrade with `400 Invalid HTTP request received` *before*
reading the body — your multipart upload fails before Open WebUI even
parses it. This was the most surprising debugging session in the project's
history.

### Bearer auth

Every request includes `Authorization: Bearer ${OPEN_WEBUI_API_KEY}`.
Centralized in `baseRequest(path)` so individual call sites don't forget.

### Sub-DTOs

| Record | Wraps |
|--------|-------|
| `KnowledgePage` | `{items: [KnowledgeBase], total: N}` for `GET /api/v1/knowledge/`. |
| `KnowledgeBase` | `{id, name, description}` — the only fields we care about; Open WebUI returns ~10 more. |
| `CreateKbRequest` | `{name, description, data, access_control}` — Open WebUI requires both `data` and `access_control` (empty objects accepted). |
| `UploadedFile` | `{id, filename}` — first response of the upload, before processing starts. |
| `ProcessStatus` | `{status, error?}` with `completed()` / `failed()` predicates. |
| `AddFileRequest` | `{file_id}` — payload of `/file/add`. |

All `@JsonIgnoreProperties(ignoreUnknown=true)` because Open WebUI versions
add fields between releases (and one of the auto-detected ones — `data` on
upload — is huge once processing completes).

## Failure modes

| Case | Result |
|------|--------|
| Open WebUI unreachable | `OpenWebUiException("I/O error calling ...", IOException)`. |
| Non-2xx from any endpoint | `OpenWebUiException(statusCode, body)`. The body contains Open WebUI's error message. |
| 401/403 | Same shape with status. Fix: `OPEN_WEBUI_API_KEY`. |
| `processing_status` returns `failed` | `OpenWebUiBackend.ingest` returns `IngestResult(...addedToKb=false, message="Open WebUI failed to process the file: <error>")`. We don't throw — the result carries the failure. |
| Poll timeout | `OpenWebUiException("Timed out after PT300S waiting for file ... (last status=pending)")`. |
| Race with `/file/add` (called too early) | Open WebUI returns 400 "content provided is empty"; we treat it as any other non-2xx. With the poll loop in place, this shouldn't happen — if it does, something has bypassed `waitUntilProcessed`. |

## Why it's like this

- **Why keep this backend at all?** Open WebUI runs its own embedding /
  chunking pipeline, which some users already have configured (e.g. with a
  specific embedding model behind GPU acceleration). The Qdrant backend is
  the default, but ripping out Open WebUI would force those users to
  re-architect. Keeping it as `Backend` lets them use this server as a
  thin wrapper around their existing Open WebUI install.
- **Search is unsupported.** Open WebUI doesn't expose a clean direct
  vector-search endpoint — RAG happens inside its chat completion path, which
  isn't a useful surface to proxy. `search()` throws with a message pointing
  the agent at the Qdrant backend.
- **Multipart upload by hand, not via REST Client.** Quarkus REST Client's
  multipart support is reactive-streams flavored and pulls in additional
  dependencies; a 25-line `ByteArrayOutputStream` body builder does the job
  with no extra deps.
- **Poll loop in `FileUploadService`, not inside `OpenWebUiClient`.** Keeps
  the HTTP client one method = one HTTP call. The poll is a higher-level
  pattern (a sequence of HTTP calls plus sleep + timeout), so it lives in a
  service class that composes the client.
- **`failed` doesn't throw — it returns a result.** The agent gets a clean
  `addedToKb=false` with a message, instead of a stack trace. Failed
  processing is normal-ish (Open WebUI's parsers can choke on weird PDFs) and
  the agent might recover by re-uploading or switching to Qdrant.

## Tests

Two test classes:

`OpenWebUiClientTest` (6 tests, WireMock) — wire-level coverage:

- `listKnowledgeBases_returnsParsedArray` — the `{items, total}` unwrap.
- `createKnowledgeBase_sendsCorrectJsonBody` — `data: {}, access_control: {}`
  defaults in the request body.
- `uploadFile_sendsMultipartAndReturnsId` — captures the request body and
  asserts the multipart shape (boundary header, Content-Disposition,
  Content-Type, raw bytes).
- `getFileProcessStatus_parsesStatusAndError` — error message round-trip.
- `addFileToKnowledgeBase_postsFileId` — exact body assertion.
- `non2xxResponse_throwsOpenWebUiException` — 401 → throw with status.

`OpenWebUiBackendTest` (3 tests, WireMock) — end-to-end pipeline:

- `happyPath_reusesExistingKbAndAttachesFile` — existing KB by name → no
  create call → upload → poll (pending→completed via WireMock scenario) →
  attach.
- `createsKbWhenNameNotFound` — empty `items` list → create.
- `failedProcessing_surfacesErrorAndSkipsAdd` — `failed` status → no attach
  call, error in result message.

The backend tests construct each helper service (`FileFetcher`,
`KnowledgeService`, `FileUploadService`, `OpenWebUiClient`) by hand and wire
them via reflection — same pattern as the rest of the test suite. Poll
delays are reduced (`initialPollMs=1, maxPollMs=5`) to keep the test runs
fast.
