# Upload ingest (REST)

The **push** counterpart to [directory ingest](directory-ingest.md): the client
sends the bytes over HTTP, the server writes them to a durable document store,
and the existing pipeline runs unchanged. This is the route for a remote client
with a folder of PDFs and no filesystem access to the host — `/docs` and
`/host` require a mount; `POST /ingest/upload` does not.

- **Logic:** `core/src/main/java/org/hayden/ingest/` —
  `UploadIngestService` (orchestration), `UploadedDocumentStore` (sanitize,
  durable write, conflicts, ZIP expansion, delete), `JobSourceSnapshots`
  (hardlink pinning for queued jobs), `BatchIngestExecutor` (shared fan-out),
  plus the `UploadIngestRequest` / `UploadIngestResponse` records.
- **Transport:** `server-http/src/main/java/org/hayden/rest/UploadResource.java`
  (multipart binding only) + the `SourceConflictException` (409),
  `InsufficientStorageException` (507), and `PayloadTooLargeException` (413)
  mappers. stdio has no REST.

## Endpoint

```
POST /ingest/upload        multipart/form-data
```

| Part | Type | Required | Notes |
|---|---|---|---|
| `files` | file, repeatable | yes | one part per document; the part's `filename` names the document |
| `kb_name` | text | yes | target KB; also the top-level store directory |
| `kb_description` | text | no | used only when the KB is created |
| `subdir` | text | no | relative directory under `<root>/<kb>/` |
| `backend` | text | no | override `INGEST_BACKEND` |
| `enable_visual_index` | text `true`/`false` | no | override `INGEST_DEFAULT_VISUAL_INDEX` |
| `metadata` | text (JSON object) | no | applied to every file in the request |
| `on_conflict` | text | no | `replace` (default) / `suffix` / `reject` |
| `ingest` | text `true`/`false` | no | default `true`; `false` = store only |
| `expand_archives` | text `true`/`false` | no | default `true`; expand `.zip` parts |

The response reuses the directory-ingest outcome shape
(`DirectoryFileOutcome`), with the store `root` in place of `directory` and a
fourth per-file status, `"stored"`, next to `"completed"` / `"queued"` /
`"error"`.

## The store is the corpus of record

Uploads land at `${ingest.upload.root}/<kb>/<subdir>/<file>` — `/documents` in
the container, a read-write mount (`INGEST_DOCUMENTS_DIR`, `/tank/documents`
on the R530). **There is no reaper and no retention window**; a file stays
until an operator deletes it. That durability is load-bearing three ways:

1. the queue re-reads the file when a visual job drains (possibly after a
   restart);
2. a re-index (chunking change, embedding-model change, dropped `<kb>_pages`)
   re-ingests the original bytes;
3. `POST /ingest/directory` over `/documents/<kb>` re-indexes the uploaded
   corpus with no new machinery, because the doc id is the **same**
   deterministic `UuidV5.forSource(kb, storedPath)` a scan of that tree
   computes.

A failed ingest keeps the file — fix the cause, re-scan the directory, don't
re-upload gigabytes. The same recovery covers a crash between the store's
rename and the queue submit: bytes stored, nothing indexed, one re-scan away.

## Durable write

Per file: write to a hidden `.<name>.part` **in the final directory** (so the
rename is same-dataset and atomic, and scans — which skip dot-prefixed
entries — never see it), `FileChannel.force(true)` when
`ingest.upload.fsync=true`, `ATOMIC_MOVE` into place, best-effort fsync of the
parent directory. `quarkus.http.body.uploads-directory` (`UPLOAD_TMP_DIR`) is
`/documents/.tmp` so the multipart temp and the final file share a FileStore;
at init the store compares the two FileStores and falls back to
copy + fsync + delete (never a non-atomic cross-device move) with a warning
if they differ. Aged `.part` files and abandoned multipart bodies are swept
at init and after every request (`ingest.upload.tmp_sweep_minutes`).

`ingest.upload.require_mount=true` (default) refuses to operate when the root
is not a mount point — a missing `/documents` bind would otherwise silently
store the corpus inside the container. Set it false for bare-metal runs.

## Confinement

`kb_name` must match `[A-Za-z0-9._-]{1,64}` with no leading dot — validated
**before any path is built**, so it can never be an escape vector (`..`
matches the character class and is rejected explicitly). Subdir and filename
are sanitized (last segment only, control chars stripped, `..` segments
dropped, 200-char cap with the extension preserved), and the resolved path is
re-checked to be a descendant of the **literal root**, not of `<root>/<kb>` —
confining against a caller-derived baseline is no confinement. A `.pdf`
without a `%PDF-` header is rejected at store time (it would otherwise fall
through `countPdfPages`'s catch into the sync text path and index as garbage).

## Conflicts, and why replace needs snapshots

`on_conflict=replace` (default) overwrites in place — same path, same doc id,
so `doIngest` deletes the old points and the document is replaced
idempotently. But a queued visual job persists a *path* and re-reads it
minutes later: replace the file in between and the worker renders pages from
the **new** bytes while the chunks came from the **old** — both under one doc
id, both scoring in the same fused result, nothing downstream to catch it.

Two mechanisms close that hole:

- **`JobSourceSnapshots`** — at queue time, `QdrantBackend` hardlinks the
  source into `<root>/.jobs/<jobId>/<filename>` and persists the job with the
  **link** as its `sourceValue`. `Files.move(..., REPLACE_EXISTING)` replaces
  the directory entry, not the inode, so the job keeps its bytes. The link is
  released when the job reaches a terminal status; the worker's startup sweep
  drops links for job ids the queue no longer tracks as live.
- **Replace refusal (fallback)** — when the link couldn't be made
  (cross-device, no hardlinks), the job still points at the browsable path,
  and `UploadIngestService` refuses `on_conflict=replace` over any path a
  non-terminal job reads: HTTP 409 naming the job id.

Independently, `QdrantBackend.doIngest` holds a **per-doc-id lock** across
each document's `deleteDoc` + write, on every entry point (sync, split-queue
submit, worker) — two writers on one deterministic id serialize instead of
one deleting the points the other just wrote.

## Store-only mode

`ingest=false` stores the bytes and returns immediately with `"stored"`
outcomes (doc id, stored path, no job id) and **zero** `IngestService` calls.
The caller indexes later with one `POST /ingest/directory` over
`/documents/<kb>` — which computes the same doc ids, so nothing is ingested
twice. This is deliberately *not* an `async=true` that queues `FULL` jobs:
a FULL job runs text + visual in one worker thread, exactly the serialization
the split-visual design removed to keep the GPU fed.

## ZIP expansion

A `.zip` part (with `expand_archives=true`, default) expands into
`<root>/<kb>/<subdir>/`, preserving internal structure; each entry becomes its
own document with its own path and doc id. Guards: zip-slip fails the whole
archive; directories, dot-prefixed entries, and extensions outside the
directory-scan set are skipped; `ingest.upload.zip.max_entries` (500) and
`ingest.upload.zip.max_uncompressed_bytes` (2 GiB) cap bombs — declared sizes
are checked before extraction and actual bytes are re-checked while
streaming, because declared sizes lie. The archive itself is never stored;
nested archives are not expanded.

## Size limits and free space

`quarkus.http.limits.max-body-size` (`UPLOAD_MAX_BODY_SIZE`, 2G) is the
**primary** cap: Quarkus rejects with 413 while the body streams, before any
application code. `ingest.upload.max_request_bytes` is its application-level
twin (keep them equal) and `ingest.upload.max_files` (200) caps part count.
The free-space guard (`ingest.upload.min_free_bytes`, 10 GiB reserve → 507)
is computed from **materialized temp-file sizes**, never a client-declared
Content-Length — a client can declare 1 MiB and stream 500 GiB, and those
bytes spill to `/documents/.tmp` before the resource runs; the body cap, the
temp sweep, and the ZFS quota on the dataset are what actually bound that.

## Deleting a stored document

`DELETE /ingest/document?doc_id=…&delete_source=true` removes the points and
then the backing file — located by **walking the store for the doc id**,
never from caller input, and only ever under `ingest.upload.root` (a doc
ingested from `/docs` or `/host` keeps its file; the result carries a
warning). `delete_source=true` requires `doc_id`; combining it with
`source_path` is a 400, because that would put the caller on both sides of
the containment comparison. Point deletion by `source_path` alone still works
as before.

## Security note

This is the first endpoint that **writes** caller-controlled bytes to
permanent server storage — on the same pool as the Qdrant data and page
images — and the REST surface has no auth. The path confinement, caps, and
free-space reserve are v1 mitigations; the real one is network isolation,
plus a dedicated quota'd dataset:

```bash
zfs create -o recordsize=1M -o compression=lz4 -o quota=200G tank/documents
```

The container runs as root, so stored files are `root:root` on the tank; set
`user:` on the compose service if that matters.

## Configuration

| Key (env) | Purpose | Default |
|---|---|---|
| `ingest.upload.root` (`INGEST_UPLOAD_ROOT`) | store root in the container | `~/.pdf-rag-ingest/documents`; compose: `/documents` |
| `ingest.upload.tmp_dir` (`UPLOAD_TMP_DIR`) | multipart temp dir, same dataset as root | `<root>/.tmp` |
| `ingest.upload.parallelism` (`INGEST_UPLOAD_PARALLELISM`) | ingest fan-out per request | `4` |
| `ingest.upload.max_files` (`INGEST_UPLOAD_MAX_FILES`) | parts per request | `200` |
| `ingest.upload.max_request_bytes` (`INGEST_UPLOAD_MAX_REQUEST_BYTES`) | app-level request cap | `2147483648` |
| `ingest.upload.min_free_bytes` (`INGEST_UPLOAD_MIN_FREE_BYTES`) | free-space reserve → 507 | `10737418240` |
| `ingest.upload.fsync` (`INGEST_UPLOAD_FSYNC`) | fsync before the rename | `true` |
| `ingest.upload.tmp_sweep_minutes` (`INGEST_UPLOAD_TMP_SWEEP_MINUTES`) | stray sweep age | `60` |
| `ingest.upload.require_mount` (`INGEST_UPLOAD_REQUIRE_MOUNT`) | refuse a non-mount root | `true` |
| `ingest.upload.zip.enabled` (`INGEST_UPLOAD_ZIP_ENABLED`) | expand `.zip` parts | `true` |
| `ingest.upload.zip.max_entries` (`INGEST_UPLOAD_ZIP_MAX_ENTRIES`) | entries per archive | `500` |
| `ingest.upload.zip.max_uncompressed_bytes` (`INGEST_UPLOAD_ZIP_MAX_UNCOMPRESSED_BYTES`) | zip-bomb cap | `2147483648` |
| `quarkus.http.limits.max-body-size` (`UPLOAD_MAX_BODY_SIZE`) | transport body cap (413) | `2G` |
| — (`INGEST_DOCUMENTS_DIR`) | compose volume/bind for `/documents` | `documents` (named volume); R530: `/tank/documents` |

## Tests

`UploadedDocumentStoreTest`, `UploadIngestServiceTest`,
`BatchIngestExecutorTest`, `JobSourceSnapshotsTest`, plus the extended
`QdrantBackendTest` (per-doc-id serialization), `IngestServiceTest`
(`delete_source`), and `DirectoryIngestServiceTest` (`delete_source` +
`source_path` rejection). The multipart binding itself has no unit test (it
needs a live HTTP server); `scripts/smoke.sh` covers it end to end.
