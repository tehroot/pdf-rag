# MCP tool surface (`IngestTools`)

`core/.../tools/IngestTools.java` — the only class the MCP framework
introspects to build the tool catalogue. Five `@Tool` methods, all
`@Blocking`.

## Tools

| Tool | Purpose | Backend |
|------|---------|---------|
| `ingest_document` | Resolve a source, find-or-create the KB, ingest. Optionally visual-indexes; may return queued status. | Qdrant or Open WebUI |
| `search_documents` | Vector search with optional fusion. | Qdrant only |
| `list_knowledge_bases` | List KBs / collections with visual-index status. Defaults to merging across backends. | All |
| `get_file_status` | Open-WebUI-only diagnostic. | Open WebUI |
| `inspect_page` | Retrieve a rendered page image as base64 PNG (Qdrant visual index). | Qdrant |
| **`get_ingest_status`** | Poll an async ingest job by jobId. | Qdrant (queue) |
| **`drop_visual_index`** | Admin: delete the visual index for a KB. | Qdrant |

Bold = added in Phase 5/6.

## `ingest_document`

| Argument | Type | Required | Default | Notes |
|----------|------|----------|---------|-------|
| `source_type` | string | yes | — | `"url"` / `"path"` / `"inline"`. |
| `source_value` | string | yes | — | The URL, absolute path, or base64 bytes. |
| `kb_name` | string | yes | — | KB / collection name. |
| `filename` | string | no | inferred | Required for `inline`. |
| `kb_description` | string | no | — | Only on create. |
| `poll_timeout_seconds` | int | no | `300` | Open-WebUI-only. |
| `backend` | string | no | server default | `"qdrant"` / `"openwebui"`. |
| `metadata` | object | no | `{}` | Stored on each chunk + page payload (Qdrant). |
| **`enable_visual_index`** | bool | no | env default (`INGEST_DEFAULT_VISUAL_INDEX`, default `true`) | Qdrant-backend gate for the ColPali pipeline. |

Return (sync path, small ingest):

```json
{
  "backend": "qdrant",
  "kb_id": "engineering-docs",
  "kb_name": "engineering-docs",
  "file_id": "ae72-...",
  "processing_status": "completed",
  "chunk_count": 12,
  "page_count": 8,
  "added_to_kb": true,
  "message": "Ingested 12 chunks ... + 8 pages visual-indexed",
  "warnings": [],
  "job_id": null
}
```

Return (async path — PDF ≥ `ingest.queue.sync_threshold_pages`):

```json
{
  "backend": "qdrant",
  "kb_id": "engineering-docs",
  "kb_name": "engineering-docs",
  "file_id": "ae72-...",
  "processing_status": "queued",
  "chunk_count": 0,
  "page_count": 0,
  "added_to_kb": false,
  "message": "Queued for ingestion as job 8d7c-... ; poll get_ingest_status to track progress",
  "warnings": [],
  "job_id": "8d7c-..."
}
```

When `processing_status` is `"queued"`, the agent polls
[`get_ingest_status`](#get_ingest_status-new) with the returned `job_id`
until the job is `COMPLETED` or `FAILED`. The final `IngestResult` (with
chunk_count, page_count, etc.) is then under `job.result`.

`page_count > 0` iff visual indexing ran. `warnings` carries non-fatal
degradations (e.g. visual requested but file is not a PDF → text-only with
warning).

### Mode mismatch on existing KB → hard reject

If the KB was created with `enable_visual_index=true` and the call passes
`false`, or vice versa, the tool throws with a clear message. Recovery:
create a new KB or change the arg to match.

### Sidecar down + visual requested → hard fail

A pre-flight `healthz` check on the ColPali sidecar before any work. Caught
errors propagate to the agent unchanged.

## `search_documents`

| Argument | Type | Required | Default | Notes |
|----------|------|----------|---------|-------|
| `kb_name` | string | yes | — | KB / Qdrant collection. |
| `query` | string | yes | — | Natural-language query. |
| `top_k` | int | no | `5` | Final hits returned. |
| `backend` | string | no | server default | Routing. |
| `filter` | object | no | — | Flat `{key: value}` → Qdrant `{must: [...]}`. |
| **`retrieval_mode`** | string | no | env default (`RETRIEVAL_MODE`, default `auto`) | `"auto"` / `"fusion"` / `"text_only"` / `"colpali_only"`. |
| **`fusion_strategy`** | string | no | env default (`FUSION_STRATEGY`, default `rrf`) | `"rrf"` / `"weighted"`. Operator-tunable. |

Return:

```json
{
  "backend": "qdrant",
  "kb_name": "engineering-docs",
  "fusion_mode": "fusion",
  "confidence": "high",
  "warnings": [],
  "hits": [
    {
      "score": 0.0335,
      "text": "...the chunk text...",
      "source": "https://...",
      "filename": "spec.pdf",
      "doc_id": "ae72-...",
      "chunk_index": 3,
      "page_start": 4,
      "page_end": 4,
      "text_score": 0.85,
      "page_score": 0.91,
      "confidence": "high",
      "metadata": {"...full chunk payload..."}
    }
  ]
}
```

`fusion_mode` resolution + fallback matrix is documented in
[fusion-engine.md](fusion-engine.md). `confidence` (response-level) is the
max of per-hit confidence in the top-K.

## `list_knowledge_bases`

| Argument | Type | Required | Default | Notes |
|----------|------|----------|---------|-------|
| `backend` | string | no | `"all"` | `"qdrant"`, `"openwebui"`, or `"all"`. |

Returns `List<KnowledgeBaseSummary>` with visual-index status per row:

```json
[
  {
    "backend": "qdrant",
    "id": "engineering-docs",
    "name": "engineering-docs",
    "vectors": 1024,
    "dim": 1024,
    "visualIndexEnabled": false,
    "visualIndexPages": null
  },
  {
    "backend": "qdrant",
    "id": "scanned-archives",
    "name": "scanned-archives",
    "vectors": 8421,
    "dim": 1024,
    "visualIndexEnabled": true,
    "visualIndexPages": 4267
  },
  {
    "backend": "openwebui",
    "id": "kb-1234",
    "name": "Open WebUI Reports",
    "vectors": null,
    "dim": null,
    "visualIndexEnabled": false,
    "visualIndexPages": null
  }
]
```

Internal `<kb>_pages` Qdrant collections are filtered out of the top-level
listing — they're implementation detail. Open WebUI entries always report
`visualIndexEnabled: false` (it's a Qdrant-side feature).

## `get_file_status`

Open-WebUI-only. Polls `/api/v1/files/{id}/process/status` and returns the
raw `{status, error?}` record.

## `get_ingest_status` (NEW)

| Argument | Type | Required | Notes |
|----------|------|----------|-------|
| `job_id` | string | yes | From an `ingest_document` result whose `processing_status` was `"queued"`. |

Return: the full `IngestJob` record. Agents poll this when an ingest
returned a queued result; the job's `status` transitions from `QUEUED` →
`IN_PROGRESS` → (`COMPLETED` | `FAILED`).

```json
{
  "jobId": "8d7c-...",
  "status": "COMPLETED",
  "request": { "...the original IngestRequest..." },
  "docId": "ae72-...",
  "submittedAt": "2026-05-21T01:42:33.157Z",
  "startedAt":   "2026-05-21T01:42:34.910Z",
  "completedAt": "2026-05-21T01:44:21.402Z",
  "result": {
    "backend": "qdrant",
    "chunk_count": 87,
    "page_count": 50,
    "added_to_kb": true,
    "message": "...",
    "warnings": [],
    "job_id": null
  },
  "error": null,
  "warnings": [],
  "retryCount": 0
}
```

Throws if the `job_id` is unknown — either the id is wrong, or the queue's
persistence root has been wiped.

See [ingest-queue.md](ingest-queue.md) for the queue lifecycle.

## `drop_visual_index` (NEW, admin)

| Argument | Type | Required | Default | Notes |
|----------|------|----------|---------|-------|
| `kb_name` | string | yes | — | KB whose visual index to drop. |
| `confirm` | bool | no | `false` | Must be true to actually drop; default false returns the dry-run plan. |

Drops the `<kb>_pages` Qdrant collection AND removes the page-image files
from the configured `PageImageStore`. The text-side chunk collection is
untouched — the KB continues working in text-only mode after this call.

Returns:

```json
{
  "kbName": "scanned-archives",
  "qdrantCollectionDropped": true,
  "imageFilesRemoved": 4267,
  "message": "Dropped visual index for KB 'scanned-archives': Qdrant collection deleted, 4267 page-image file(s) removed."
}
```

Dry-run (default, `confirm=false`):

```json
{
  "kbName": "scanned-archives",
  "qdrantCollectionDropped": false,
  "imageFilesRemoved": 0,
  "message": "Dry run: would drop visual index for KB 'scanned-archives' (currently 4267 pages indexed). Re-call with confirm=true to actually drop."
}
```

If the KB has no visual index, returns a clear no-op:

```json
{
  "kbName": "engineering-docs",
  "qdrantCollectionDropped": false,
  "imageFilesRemoved": 0,
  "message": "KB 'engineering-docs' has no visual index; nothing to drop."
}
```

**Recovery from a dropped visual index:** re-ingest the documents into the
same KB with `enable_visual_index=true`. The mode-mismatch check accepts
this — once the visual index is dropped, the KB is back to a "fresh"
state from the perspective of `validateModeConsistency`.

## `inspect_page` (NEW)

| Argument | Type | Required | Notes |
|----------|------|----------|-------|
| `kb_name` | string | yes | Must have a visual index. |
| `doc_id` | string | yes | From a previous `ingest_document` result. |
| `page_number` | int | yes | 1-indexed. |

Return:

```json
{
  "kbName": "engineering-docs",
  "docId": "ae72-...",
  "pageNumber": 4,
  "width": 1275,
  "height": 1650,
  "base64Png": "iVBORw0KG..."
}
```

For when an agent sees a low-confidence hit and wants to read the actual
page. Multimodal agents (Claude 3+, GPT-4V, Gemini) can OCR the image
themselves; text-only agents typically won't call this.

Throws `IngestException` if the page isn't in the visual index — common
reasons: KB has no visual index, document wasn't ingested with
`enable_visual_index=true`, or the page image has been manually deleted
from the store.

## How the wiring works

```java
@ApplicationScoped
public class IngestTools {
    @Inject IngestService ingestService;          // dispatcher
    @Inject OpenWebUiBackend openWebUi;            // for get_file_status
    @Inject ColPaliPipeline colpaliPipeline;       // for inspect_page

    @Tool(...)  @Blocking
    public IngestResult ingestDocument(...) { ... }
    // and so on
}
```

Each `@Tool` is a thin adapter: parse args into the typed record, delegate
to the appropriate component, return the result. The MCP framework reads
the `@Tool` annotation for the tool name + description, and `@ToolArg` for
each parameter's schema.

`get_file_status` and `inspect_page` bypass the dispatcher because they're
backend-specific concerns with no analog in the other backend.

## Failure modes

All `IngestException`s bubble up unchanged. The MCP framework wraps them in
JSON-RPC error responses; agents see structured failures rather than hangs.

| Tool | Common failure |
|------|----------------|
| `ingest_document` | Sidecar down + visual requested → hard fail; mode mismatch → hard fail; non-PDF + visual → warning. |
| `search_documents` | Unknown `retrieval_mode` → throw; `colpali_only` on KB without visual → throw; sidecar down → soft-degrade to text_only with warning. |
| `inspect_page` | Page not found → `IngestException` with clear "no visual index" message. |
| `get_file_status` | Open WebUI returns 404 → wrapped in `OpenWebUiException`. |

## Why it's like this

- **Annotations over registration.** Every tool spec the MCP server publishes
  is derived from `@Tool` + `@ToolArg`. Adding a sixth tool is a sixth
  annotated method — no registration table, no schema duplication.
- **`@Blocking` is mandatory.** Quarkus MCP runs requests on event-loop
  threads by default. Our backends do synchronous HTTP and (potentially)
  long-running operations; `@Blocking` shifts to the worker pool.
- **`inspect_page` is the multimodal escape hatch.** Critical for the
  user-stated case of bad-OCR scanned PDFs — when text search returns
  garbled text but the page is otherwise the right hit, an agent that can
  read images can recover.
- **Per-call mode overrides.** Both `enable_visual_index` and
  `retrieval_mode` can be set per call (override env defaults). Lets an
  agent dynamically pick: "use text-only for this fast lookup" vs "use
  fusion for this hard one." Most agents won't bother and will use the
  defaults.

## Tests

`IngestTools` itself isn't unit-tested in isolation — the value lives one
layer deeper. Coverage:

- `IngestServiceTest` exercises every tool's underlying dispatcher path.
- `QdrantBackendTest` and `OpenWebUiBackendTest` end-to-end ingestion paths.
- `FusionEngineTest`, `RrfFusionTest`, etc. cover the search side.
- `ColPaliPipelineTest` covers `inspectPage`.

The MCP JSON schemas would be a nice end-to-end test target (McpAssured
against the stdio jar) — not yet written.
