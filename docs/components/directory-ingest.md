# Directory ingest (REST)

A plain HTTP endpoint that points the server at a directory on disk and
ingests every matching file — for bulk-loading an inbox or wiring a cron job,
without an MCP client. New surface, served on the **same port as `/mcp`**.

- **Logic:** `core/src/main/java/org/hayden/ingest/DirectoryIngestService.java`
  + the `DirectoryIngestRequest` / `DirectoryIngestResponse` /
  `DirectoryFileOutcome` records (all in `core/.../ingest/`).
- **Transport:** `server-http/src/main/java/org/hayden/rest/` —
  `IngestResource` (JAX-RS), `JobStatusView`, `IngestExceptionMapper`.

The split follows the repo convention: scanning + dispatch is backend-agnostic
logic in `core`; the JAX-RS binding is HTTP-specific and lives in `server-http`
(stdio has no REST). `server-http` gains a `quarkus-rest-jackson` dependency;
core beans are injected into the resource via the existing Jandex index.

## Endpoints

```
POST /ingest/directory      scan a directory, ingest matching files
GET  /ingest/status/{jobId} poll a queued file's job
```

No auth (consistent with `/mcp`); relies on network isolation. JSON is
snake_case to match the MCP tool surface.

### `POST /ingest/directory`

Request:

```json
{
  "directory": "/docs/manuals",          // absolute path, required
  "kb_name": "engineering-docs",          // required
  "kb_description": "…",                   // optional (only used on KB create)
  "recursive": true,                       // optional, default true
  "extensions": ["pdf", "docx"],           // optional; omit → default doc set
  "backend": "qdrant",                     // optional → configured default
  "enable_visual_index": true,             // optional → env default
  "metadata": { "team": "platform" }        // optional, applied to every file
}
```

Response (`200`, even when some files fail):

```json
{
  "directory": "/docs/manuals",
  "kb_name": "engineering-docs",
  "files_found": 12,
  "completed": 9,
  "queued": 2,
  "failed": 1,
  "files": [
    {"path": "/docs/manuals/a.pdf", "filename": "a.pdf",
     "doc_id": "e9b81ae3-…", "status": "completed",
     "job_id": null, "chunk_count": 47, "page_count": 0,
     "message": "Ingested 47 chunks …"},
    {"path": "/docs/manuals/big.pdf", "filename": "big.pdf",
     "doc_id": "c5430c6c-…", "status": "queued",
     "job_id": "…", "chunk_count": null, "page_count": null,
     "message": "Queued for ingestion …"},
    {"path": "/docs/manuals/broken.pdf", "filename": "broken.pdf",
     "doc_id": "ce88d5c3-…", "status": "error",
     "job_id": null, "message": "PDFBox extracted no text …"}
  ]
}
```

`completed + queued + failed == files_found`. Bad input (missing `kb_name`,
non-absolute or non-existent `directory`) → `400 {"error": "…"}` via
`IngestExceptionMapper`. A failure on one *file* never aborts the scan — it
becomes an `"error"` outcome.

### `GET /ingest/status/{jobId}`

For files that came back `"queued"` (large visual PDFs auto-routed to the async
queue), poll their job. Returns a compact view of the persisted `IngestJob`
(`status`, `chunk_count`, `page_count`, `message`, `error`, `retry_count`);
unknown id → `404`. Equivalent to the `get_ingest_status` MCP tool, for
REST-only callers.

## How a scan runs

```mermaid
flowchart TD
    R["POST /ingest/directory"] --> V{"validate:
        kb_name set?
        directory absolute + exists?"}
    V -- no --> E400["400 {error}"]
    V -- yes --> W["walk dir
        (recursive? Files.walk : Files.list)"]
    W --> F["filter:
        regular file,
        not under a dot-dir,
        extension in set"]
    F --> L{"for each file"}
    L --> ID["doc_id = UuidV5.forSource(kb, absPath)"]
    ID --> D["IngestService.ingest(PATH request, doc_id)"]
    D --> RT{"backend sync/queue routing"}
    RT -- "small / text" --> C["completed (chunk_count)"]
    RT -- "large visual PDF" --> Q["queued (job_id)"]
    D -. throws .-> ER["error outcome (message)"]
    C --> SUM["per-file outcome"]
    Q --> SUM
    ER --> SUM
    SUM --> L
    L -- done --> RESP["DirectoryIngestResponse (200)"]
```

**File selection.** Regular files only; anything under a dot-prefixed segment
(`.git/…`, `.hidden.pdf`) is skipped. The extension filter is the request's
`extensions` (leading dots tolerated) or, when omitted, a default document set
(`pdf txt md html htm json csv docx xlsx pptx`). Results are sorted by path
for stable ordering.

**Per-file dispatch.** Each file becomes a `PATH`-source `IngestRequest`
dispatched through `IngestService.ingest(req, docId)` — so the backend's normal
[sync/queue routing](qdrant-backend.md) applies: small/text-only files ingest
inline (`completed`), large visual PDFs are queued (`queued` + `job_id`).

## Idempotent re-scan (deterministic doc IDs)

Every file gets a **deterministic** document id:
`UuidV5.forSource(kbName, absolutePath)` (see [UuidV5](qdrant-client.md) /
`UuidV5.java`). Because chunk and page point IDs derive from the doc id
(`UuidV5.forChunk(docId, i)`), re-scanning a directory makes a file's points
**overwrite in place** instead of accumulating a duplicate copy — so an inbox
can be re-scanned safely after dropping in new files (unchanged files no-op,
new files get added). The KB name is part of the key, so the same file ingested
into two KBs gets two distinct ids.

This required threading a caller-supplied id through the ingest path:
`Backend.ingest(req, explicitDocId)` (default ignores it),
`QdrantBackend.ingest(req, explicitDocId)` (uses it for both the sync and
queued branches), and `IngestService.ingest(req, explicitDocId)`. The MCP
`ingest_document` tool still uses the no-id overload → a fresh random id per
call, unchanged.

**Caveat (the known stale-tail issue):** if a file *changes* and now produces
*fewer* chunks than before, the surplus high-index points from the previous
version remain (different chunking config has the same effect). Overwrite is
clean for same-or-more chunks; shrinking content leaves an orphan tail. There
is no delete-by-source today (see `docs/eval/retrieval-eval.md` notes on
re-ingest semantics). For a strict refresh, target a fresh KB.

## Why it's like this

- **Reuses `IngestService`, not a parallel path.** Each file goes through the
  exact dispatch + sync/queue routing + validation the MCP tool uses, so
  behavior can't drift between the two entry points.
- **200-with-per-file-status, not fail-fast.** A bulk scan over hundreds of
  files shouldn't die because one PDF is a scan with no text layer. The caller
  gets a complete ledger.
- **Synchronous request (caveat).** The endpoint processes files in the request
  thread; large *visual* directories return fast (their files queue), but a
  directory of hundreds of text files will block until done. If true
  fire-and-forget is needed, a batch-job wrapper is the follow-up.
- **No sandbox.** Any absolute path the process can read is allowed (a
  deliberate choice — see the design decision log). The natural deployment
  pattern is still the `/docs` bind-mount inbox; nothing forces it.

## Tests

`DirectoryIngestServiceTest` (9 tests, core, temp dir + a recording
`IngestService` stub — no HTTP, no live backend):

- `recursive_picksAllSupported_skipsHiddenAndUnsupported` — `.dotfiles`,
  dot-dirs, and unknown extensions excluded; subdir file included.
- `nonRecursive_skipsSubdirectories`.
- `extensionFilter_isHonored_andDotPrefixTolerated` — `[".pdf", "md"]`.
- `docId_isDeterministicPerSourcePath` — id == `UuidV5.forSource(...)` and
  identical across two scans.
- `perFileFailure_isCapturedAsErrorOutcome_andDoesNotAbort`.
- `queuedResult_isCountedAndReportsJobId`.
- `missingKbName_throws`, `relativeDirectory_throws`,
  `nonexistentDirectory_throws`.

The thin JAX-RS resource is verified by manual smoke test (start the jar, curl
`POST /ingest/directory` + the 400/404 paths), in keeping with the repo's
no-`@QuarkusTest` convention.
