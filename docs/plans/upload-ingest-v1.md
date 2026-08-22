# Plan: HTTP upload ingest (v1)

Status: drafted, awaiting review
Author drafted: 2026-08-22

## Context

Today a caller gets bytes into the index in three ways:

| Path | Surface | Limit |
|---|---|---|
| `source_type=url` | MCP `ingest_document` | CDN bot-detection kills many fetches |
| `source_type=path` | MCP + `POST /ingest/directory` | the file must already be on a disk the container mounts |
| `source_type=inline` | MCP `ingest_document` only | base64 in a JSON-RPC frame; one file; destroys agent context |

A remote client with a folder of PDFs and no filesystem access to the host has
no good route. `POST /ingest/directory` is the bulk path, but it needs a bind
mount (`/docs`, `/host`). This plan adds a **push** path: the client sends the
bytes over HTTP, the server writes them to durable storage, and the existing
pipeline runs unchanged.

## The upload target is the document library, not scratch space

Uploaded bytes land in a **permanent document store** on the ZFS tank
(`/tank/documents` on the R530), bind-mounted read-write at `/documents` in the
container. This is a deliberate choice over a temporary staging directory, and
it drives most of the design below.

Why durable:

- **The queue re-reads the file.** The queue persists `IngestRequest` to JSON
  and `QdrantBackend.ingestForWorker` re-fetches the file from that request
  minutes later, possibly after a restart. A file that disappears at request
  end cannot serve a queued visual job.
- **Re-index needs the source.** A chunking change, an embedding-model change,
  or a dropped `<kb>_pages` collection all require re-ingesting the original
  bytes. Without a durable copy, an uploaded corpus is unreproducible — the
  vectors become the only copy, and vectors are not a backup.
- **It unifies the two bulk paths.** Once the file has a stable absolute path,
  the upload path uses the *same* deterministic doc id as the directory scan,
  and `POST /ingest/directory` with `"directory": "/documents/<kb>"` re-indexes
  the whole uploaded corpus with no new machinery.
- **It matches the existing storage pattern.** `QDRANT_DATA_DIR` and
  `PAGE_IMAGES_DIR` are already env-switchable named-volume-or-bind mounts
  pointed at `/tank` datasets. `INGEST_DOCUMENTS_DIR` is the third of the same
  shape, and the smallest of the three: the milpdfs corpus is 4.2 GB of source
  against ~38 GB of vectors and ~80 GB of PNGs.

Consequence: there is **no reaper and no retention window**. A stored document
stays until an operator deletes it.

## Scope

New REST surface on `server-http`:

| Method | Path | Purpose |
|---|---|---|
| POST | `/ingest/upload` | multipart upload of 1..N files → store → ingest each |

Not in scope for v1: auth (the REST surface stays unauthenticated, as `/mcp`
and `/ingest/directory` are), resumable/chunked upload, an MCP tool change
(`ingest_document` already accepts inline base64), and any change to how
`/docs` and `/host` behave (both stay read-only).

## Design

### Request

`POST /ingest/upload`, `Content-Type: multipart/form-data`.

| Part | Type | Required | Notes |
|---|---|---|---|
| `files` | file, repeatable | yes | one part per document; the part's `filename` is the document name |
| `kb_name` | text | yes | target knowledge base; also the top-level directory in the store |
| `kb_description` | text | no | used only when the KB is created |
| `subdir` | text | no | relative directory under `<root>/<kb>/`, e.g. `field-manuals/2026` |
| `backend` | text | no | override `INGEST_BACKEND` |
| `enable_visual_index` | text (`true`/`false`) | no | override `INGEST_DEFAULT_VISUAL_INDEX` |
| `metadata` | text (JSON object) | no | applied to every file in the request |
| `on_conflict` | text | no | `replace` (default) / `suffix` / `reject`; see below |
| `ingest` | text (`true`/`false`) | no | default `true`; `false` = store only, see [Store-only mode](#store-only-mode) |
| `expand_archives` | text (`true`/`false`) | no | default `true`; see [ZIP expansion](#zip-expansion) |

Quarkus REST binds this with `@RestForm("files") List<FileUpload>` plus
`@RestForm` scalars. `FileUpload` is already backed by a temp file on disk, so
the JVM never holds the whole batch in heap.

### Store layout

```
${ingest.upload.root}/<kb_name>/<subdir>/<sanitized-filename>
```

Container path `/documents`; host path `/tank/documents` on the R530. The
layout is a plain, browsable corpus tree — `ls /tank/documents/milpdfs` shows
the KB's source documents, and rsync/scp/ZFS-send all work on it normally.

**Sanitization** (`UploadedDocumentStore.sanitize`): `kb_name` must match
`[A-Za-z0-9._-]{1,64}` — a single path segment, no separators — and is rejected
outright otherwise, so it can never be the thing that escapes the root. `subdir`
and the filename are then normalized, and the resolved absolute path is checked
to be a descendant of the **literal `<root>`**, not of `<root>/<kb_name>`:
confining against a baseline that is itself caller-derived is no confinement.
Strip
every `..` segment and control character, reject absolute components, cap each
segment at 200 characters, and preserve the extension (the extension drives
`FileFetcher.contentTypeFor` and the PDF check). An empty result becomes
`upload-<n>`.

### Durable write

Per file:

1. Write to `<root>/<kb>/<subdir>/.<name>.part` — a hidden temp name **in the
   final directory**, so the later rename is same-dataset and atomic. (Hidden
   also means an interrupted upload is invisible to `POST /ingest/directory`,
   whose scan already skips dot-prefixed entries.)
2. `FileChannel.force(true)` before closing, when `ingest.upload.fsync=true`
   (default). Without this the rename can be visible while the data is not yet
   on stable storage. On ZFS this is a ZIL-committed sync write; on a pool with
   no SLOG it costs an extra seek per file, which is negligible against the
   ingest pipeline that follows.
3. `Files.move(tmp, final, ATOMIC_MOVE, REPLACE_EXISTING)`.
4. `fsync` the parent directory (best-effort; skipped on platforms that reject
   opening a directory channel) so the rename itself survives power loss.

`quarkus.http.body.uploads-directory` is set to `/documents/.tmp` so the
multipart temp file and the final file are on the same dataset — otherwise
every move degrades to a full copy across devices. That directory is
dot-prefixed, so scans skip it.

Two guards on that assumption, because a misconfigured `UPLOAD_TMP_DIR` breaks
the atomicity silently:

- At startup, `UploadedDocumentStore` compares `Files.getFileStore` of the
  upload root and of the multipart temp dir. Different stores → log a warning
  and fall back to copy + fsync + delete, never a non-atomic `Files.move`.
- At startup and after every request, sweep `/documents/.tmp` and any stray
  `.<name>.part` files older than one hour. A client that disconnects
  mid-upload otherwise leaks the partial body onto the tank forever.

**Free-space guard:** checked twice, because the first check trusts a number
the caller controls.

1. Before the resource method runs, `quarkus.http.limits.max-body-size` is the
   only hard cap — Quarkus rejects an oversized body with 413 while streaming,
   before any application code sees it. `ingest.upload.max_request_bytes` is
   therefore a *secondary* check, not the primary defence, and the two must be
   configured to the same value.
2. Before the move, check `Files.getFileStore(root).getUsableSpace()` against
   the **materialized** temp-file sizes (`FileUpload.size()`, actual bytes on
   disk) plus `ingest.upload.min_free_bytes` (default 10 GiB) → HTTP 507.

Checking only a declared `Content-Length` would be defeated by a client that
declares 1 MiB and streams 500 GiB: Quarkus spills those bytes to
`/documents/.tmp` — on the tank — before the application is called at all. The
`max-body-size` cap and the temp-dir sweep above are what actually bound that,
and this is why the ZFS quota in the ops note is a required part of the
deployment, not a suggestion.

**Ownership:** the container runs as root, so stored files are `root:root` on
the tank. Documented in the deployment notes; an operator who wants otherwise
sets `user:` in the compose service.

### Conflict handling

`on_conflict` controls what happens when the target path already exists:

| Value | Behaviour |
|---|---|
| `replace` (default) | overwrite the file; the doc id is unchanged, so `doIngest` deletes the old points and the document is replaced in place — idempotent re-upload |
| `suffix` | store as `name-2.pdf`, `name-3.pdf`; a new path means a new doc id and a second document |
| `reject` | the file's outcome is `"error"`, the rest of the batch proceeds |

`replace` mutates a path that a persisted job may still be pointing at. See
[Immutable bytes for queued jobs](#immutable-bytes-for-queued-jobs) — without
that mechanism, `replace` is a silent data-integrity bug, not a convenience.

### Immutable bytes for queued jobs

**The defect this fixes.** For a visual-enabled PDF at or above
`ingest.queue.sync_threshold_pages`, `QdrantBackend.ingest` ingests the text
side synchronously and queues a `VISUAL` job whose persisted `IngestRequest`
holds the **path**. The worker calls `fetch(req)` →
`FileFetcher.fromPath` minutes later. If an `on_conflict=replace` upload has
overwritten that path in the meantime, the worker renders pages from the **new**
bytes while the chunks in the index came from the **old** bytes — both under one
doc id, both scoring in the same fused result. Nothing detects it: point counts
stay right, so the idempotency claim still looks true. `validateModeConsistency`
passes. There is no content hash and no lock anywhere in `core` today
(verified), so nothing catches it downstream either. A restart makes it worse,
not better: recovery re-reads the same mutated path.

**Fix — hardlink snapshot.** When a job is queued, hardlink the stored file
into `<root>/.jobs/<jobId>/<filename>` and set the job's `sourceValue` to that
link. A later `Files.move(..., REPLACE_EXISTING)` replaces the *directory
entry*, not the inode, so the job keeps reading the exact bytes it was queued
for. A hardlink costs one inode and no data blocks, and works on ZFS within a
dataset. The link is unlinked when the job reaches `COMPLETED` or terminal
`FAILED`; the startup sweep removes links for job ids the queue no longer
knows.

Fallback when the link fails (cross-dataset, or a filesystem without hardlinks):
reject `on_conflict=replace` for any doc id that has a non-terminal job, with a
409 naming the job id. Correct, just less convenient.

### Serializing writers on one doc id

The doc id is deterministic, so two writers can now target it at once: an
upload batch and a `POST /ingest/directory` over the same KB directory, or two
uploads of the same filename. `doIngest` does `deleteDoc` then write. Interleave
two of those and one writer deletes points the other just wrote. This race
exists today between concurrent directory scans; the upload endpoint makes it
easy to hit, because both entry points now cover the same tree.

Fix: a per-doc-id lock (`ConcurrentHashMap<String, Lock>`, striped, held across
one document's `deleteDoc` + write) in `QdrantBackend.doIngest`, used by every
entry point. Not a distributed lock — single-process is the deployment.

### Doc id

Identical to the directory-ingest scheme, now that the file has a real path:

```java
docId = UuidV5.forSource(kbName, DirectoryIngestService.canonicalSourcePath(storedPath))
```

So an upload of `x.pdf` and a later `POST /ingest/directory` over
`/documents/<kb>` produce the **same** doc id for the same file, and neither
duplicates the other's work. This retires the "Decision 1" from the earlier
draft: `upload://filename` keys are unnecessary.

### Ingest

Each stored file goes through `IngestService.ingest(req, docId)` with
`SourceType.PATH` pointing at the stored absolute path. Nothing in `core`'s
pipeline or in the worker changes. A queued visual job re-reads the same
durable path on drain, and after a restart.

**A failed ingest keeps the file.** The outcome is `"error"` with the message,
and the bytes stay in the store — the operator fixes the cause (sidecar down,
Qdrant dim mismatch) and re-runs `POST /ingest/directory` over the KB
directory rather than re-uploading gigabytes.

The same property covers the crash window between the rename and the queue
submit: the bytes are durable, the job is not, so the file is stored but
unindexed. That is recoverable by exactly the same directory re-scan, and it is
why file durability and queue durability do not need to be one transaction. It
is stated here so nobody later mistakes an orphan for data loss.

**Content check at store time.** Validate magic bytes against the extension for
`.pdf` (`%PDF-` header) and reject a mismatch. `isPdf` and `countPdfPages` route
on the extension, so a non-PDF named `.pdf` currently falls through
`countPdfPages`'s `catch → 0 pages` into the sync text path and indexes as
garbage. Cheap to check while the bytes are already in hand.

### Response

Reuses the directory-ingest shapes, so a client parses one outcome format for
both bulk paths:

```json
{
  "kb_name": "milpdfs",
  "files_found": 3,
  "completed": 2,
  "queued": 1,
  "stored": 0,
  "failed": 0,
  "files": [
    {"path": "/documents/milpdfs/TM-9-1005.pdf", "filename": "TM-9-1005.pdf",
     "doc_id": "…", "status": "queued", "job_id": "…",
     "chunk_count": 812, "page_count": null, "message": "…"}
  ]
}
```

- New record `UploadIngestResponse` (mirrors `DirectoryIngestResponse`, with
  `root` in place of `directory`).
- Per-file outcomes reuse the existing `DirectoryFileOutcome` record unchanged.
  `path` is the stored container path — the same value
  `POST /ingest/directory` reports, and a valid `source_path` for
  `DELETE /ingest/document`.
- `status` gains a fourth value, `"stored"` (bytes on the tank, nothing
  indexed), alongside `"completed"`, `"queued"`, and `"error"`. Update the
  record's javadoc; the directory path never emits it, so that surface is
  unaffected.

### Deleting a stored document

`DELETE /ingest/document` today removes vectors only, which would leave an
orphan file on the tank. Add `?delete_source=true` (default `false`): after the
points are deleted, delete the backing file **only** when its resolved path is
under `ingest.upload.root` **and** equals the canonical stored path recorded for
that doc id — never a path re-derived from caller input at delete time.

`delete_source=true` **requires `doc_id`**. Combining it with `source_path` is
rejected with 400: `source_path` is caller input, and letting it drive the
deletion puts the caller on both sides of the containment comparison, which is
the shape of the hole the red-team found. Deleting points by `source_path`
still works as before — only the file deletion is restricted.

A path under `/docs` or `/host` is never deleted —
those mounts are read-only and are not ours to manage. An attempt returns a
warning in the result rather than an error.

### Store-only mode

`ingest=true` (default): each stored file goes through the routing
`POST /ingest/directory` already uses — text runs synchronously, a large visual
PDF returns `queued` with a `job_id` for its visual half. The HTTP request
stays open for the text pipeline of the whole batch (order of seconds per
file).

`ingest=false`: store the bytes, return immediately, index nothing. Each
outcome is `status: "stored"` with its `doc_id` and stored `path`, and no
`job_id`. The caller then indexes with one existing call:

```bash
curl -X POST http://localhost:8080/ingest/directory \
  -H 'content-type: application/json' \
  -d '{"kb_name":"milpdfs","directory":"/documents/milpdfs"}'
```

Because the upload assigns the same deterministic doc id the scan will compute,
the two calls describe the same documents — the scan is not a second ingest of
anything the upload already indexed.

**Why this and not an `async=true` that queues jobs.** A queued whole-file job
is `JobKind.FULL`, which runs text **and** visual inside one worker thread —
precisely the serialization
[docs/plans/split-visual-ingest-v1.md](split-visual-ingest-v1.md) removed to
keep the GPU fed. Routing a 300-file batch through `FULL` would drain slower
than the same files ingested through the directory path. Store-only gets the
same "return immediately" property, and the indexing that follows uses the
normal split routing, so the VLM lane stays pure. It also needs no new job
kind, no new poll surface, and no new failure mode: bytes on the tank with no
index entry is the state the plan already recovers from by re-scanning.

Trade-off accepted: two calls instead of one, and the upload response carries
no per-file `job_id` in this mode.

### ZIP expansion

`expand_archives=true` (default) and a part whose name ends `.zip` → expand
into `<root>/<kb>/<subdir>/`, preserving the archive's internal directory
structure, then ingest each entry as its own document. This is what makes
"bulk upload without disk access" practical from a browser or `curl`, and it
gives each entry a distinct path, so filename collisions inside a batch stop
being a problem.

Guards:

- reject any entry whose normalized path escapes the target directory
  (zip-slip);
- skip directories, hidden entries (any dot-prefixed segment, matching the
  directory scan), and entries whose extension is outside the accepted set;
- cap entry count (`ingest.upload.zip.max_entries`, default 500) and total
  uncompressed bytes (`ingest.upload.zip.max_uncompressed_bytes`, default
  2 GiB) — a zip bomb otherwise fills the tank;
- the free-space guard is re-checked against the declared uncompressed size
  before expansion;
- the per-file cap `ingest.max-file-bytes` still applies at fetch time.

The archive itself is **not** kept after a successful expansion. Nested
archives are not expanded.

### Concurrency

`DirectoryIngestService.ingestAll` already implements bounded-parallel per-file
ingest with per-file error capture and submit-order outcomes. Extract it to a
shared `core` helper `BatchIngestExecutor` and call it from both services; no
behaviour change on the directory path. Upload gets its own knob
`ingest.upload.parallelism`, default 4. Writes to the store happen first,
single-threaded, as the parts are read; only the ingest fan-out is parallel.

### Limits

`quarkus.http.limits.max-body-size` defaults to 10 MiB — a two-PDF upload
exceeds it. `server-http/application.properties` gains:

```properties
quarkus.http.limits.max-body-size=${UPLOAD_MAX_BODY_SIZE:2G}
quarkus.http.body.uploads-directory=${UPLOAD_TMP_DIR:/documents/.tmp}
```

Request-level caps enforced by the resource: `ingest.upload.max_files`
(default 200) and `ingest.upload.max_request_bytes` (default 2 GiB). Note the
ordering: Quarkus enforces `max-body-size` **before** the resource method runs,
so an oversized batch returns 413 and never reaches the `max_files` check. Keep
`UPLOAD_MAX_BODY_SIZE` and `ingest.upload.max_request_bytes` equal, and say so
in the OpenAPI description so a client can tell 413 from 507.

## Security note

This is the first endpoint that **writes** caller-controlled bytes to a
permanent location on the server — and now to the same ZFS pool that holds the
Qdrant storage and the page images. The REST surface has no auth and CORS
defaults to `*`. Anyone who reaches `:8080` can already read and index anything
under `/host`; after this change they can also write into `/tank/documents` and
consume pool capacity.

v1 mitigations: path confinement under `<root>/<kb>`, filename sanitization,
zip-slip and zip-bomb guards, per-file / per-request / entry-count caps, and
the free-space reserve. Real mitigation is still network isolation, and
`docs/deployment.md` must say so in the section that introduces the mount.
Recommended: give `/tank/documents` its own dataset with a quota, so a fill
cannot starve `/tank/qdrant`.

**ZFS dataset settings** (ops note, not code):

```bash
zfs create -o recordsize=1M -o compression=lz4 -o quota=200G tank/documents
```

`recordsize=1M` suits whole-file PDF reads; `lz4` is close to free and PDFs
still give some return. The quota is the backstop for the paragraph above.

## Files touched

**New — `core`**

| File | Purpose |
|---|---|
| `ingest/UploadIngestService.java` | orchestrates: store → per-file ingest → outcomes |
| `ingest/UploadIngestRequest.java` | the non-file form fields, as a record |
| `ingest/UploadIngestResponse.java` | response shape |
| `ingest/UploadedDocumentStore.java` | sanitize, durable write, conflict policy, ZIP expansion, delete |
| `ingest/BatchIngestExecutor.java` | shared bounded-parallel fan-out |
| `ingest/JobSourceSnapshots.java` | hardlink a queued job's bytes; unlink on terminal status |

**New — `server-http`**

| File | Purpose |
|---|---|
| `rest/UploadResource.java` | `@Path("/ingest/upload")`, `@Consumes(MULTIPART_FORM_DATA)` |

A separate resource class, not a method on `IngestResource`: that class is
`@Consumes(APPLICATION_JSON)` at class level.

**Modified**

- `core/src/main/resources/application.properties` — the `ingest.upload.*` keys.
- `server-http/src/main/resources/application.properties` — body limits,
  uploads directory, OpenAPI description.
- `ingest/DirectoryIngestService.java` — delegate the fan-out to
  `BatchIngestExecutor`.
- `backend/qdrant/QdrantBackend.java` — per-doc-id lock around
  `deleteDoc` + write in `doIngest`; hardlink the job's source before
  `queue.submit`.
- `jobs/IngestWorker.java` — release the snapshot link when a job reaches a
  terminal status.
- `ingest/IngestService.java` + `rest/IngestResource.java` — `delete_source`
  query parameter on `DELETE /ingest/document`.
- `docker-compose.yml` — read-write `${INGEST_DOCUMENTS_DIR:-documents}:/documents`
  mount (same named-volume-or-bind pattern as `QDRANT_DATA_DIR`), a `documents:`
  named volume, and the `INGEST_UPLOAD_ROOT` / `UPLOAD_TMP_DIR` /
  `UPLOAD_MAX_BODY_SIZE` env wiring.
- `.env.example` — `INGEST_DOCUMENTS_DIR`, with `/tank/documents` shown as the
  R530 value next to the existing `QDRANT_DATA_DIR` / `PAGE_IMAGES_DIR` notes.
- `scripts/bootstrap.sh` — create the documents dir, as it does for the inbox.
- `scripts/smoke.sh` — upload a small generated PDF, assert the outcome, assert
  the file exists in the store.
- `CLAUDE.md` — REST surface list, config table, and a gotcha entry for
  "uploads are durable; the store is the corpus of record".
- `docs/deployment.md` — the new mount, the dataset settings, the write-side
  exposure.

**New docs**: `docs/components/upload-ingest.md`.

## Configuration

| Var | Purpose | Default |
|---|---|---|
| `INGEST_UPLOAD_ROOT` | document store root inside the container | `/documents` (code default `${user.home}/.pdf-rag-ingest/documents`) |
| `INGEST_DOCUMENTS_DIR` | compose-level host path or volume name bound to it | `documents` (named volume); R530: `/tank/documents` |
| `INGEST_UPLOAD_PARALLELISM` | files ingested concurrently per request | `4` |
| `INGEST_UPLOAD_MAX_FILES` | parts (or ZIP entries) accepted per request | `200` |
| `INGEST_UPLOAD_MAX_REQUEST_BYTES` | total stored bytes per request | `2147483648` |
| `INGEST_UPLOAD_MIN_FREE_BYTES` | free space that must remain after a request | `10737418240` |
| `INGEST_UPLOAD_FSYNC` | fsync each file before the atomic rename | `true` |
| `INGEST_UPLOAD_TMP_SWEEP_MINUTES` | age at which a stray `.part` / temp body is swept | `60` |
| `INGEST_UPLOAD_REQUIRE_MOUNT` | fail startup if the upload root is not a mount point | `true` |
| `INGEST_UPLOAD_ZIP_ENABLED` | expand `.zip` parts | `true` |
| `INGEST_UPLOAD_ZIP_MAX_ENTRIES` | entries accepted per archive | `500` |
| `INGEST_UPLOAD_ZIP_MAX_UNCOMPRESSED_BYTES` | zip-bomb guard | `2147483648` |
| `UPLOAD_MAX_BODY_SIZE` | Quarkus HTTP body cap | `2G` |
| `UPLOAD_TMP_DIR` | Quarkus multipart temp dir (must be on the store's dataset) | `/documents/.tmp` |

## Tests

Plain JUnit 5, no `@QuarkusTest`, matching the existing suite.

| Test | Asserts |
|---|---|
| `UploadedDocumentStoreTest` | sanitization (traversal, control chars, length, extension kept); `subdir` confinement; `on_conflict` replace/suffix/reject; temp file is hidden and removed on failure; atomic rename leaves no `.part`; free-space guard rejects before writing; ZIP expansion preserves structure; zip-slip rejected; entry-count and uncompressed-byte caps; hidden entries skipped; `deleteStored` refuses a path outside the root |
| `UploadIngestServiceTest` | fake `IngestService`: outcome per file in submit order; one file's failure does not abort the batch **and leaves the file on disk**; doc id equals `UuidV5.forSource(kb, storedPath)`, i.e. the directory-scan id for the same path; `max_files` rejection |
| `BatchIngestExecutorTest` | order preserved, bounded parallelism, error capture (lifted from the directory path's coverage) |
| `UploadIngestServiceTest` (store-only) | `ingest=false` stores every file, calls `IngestService` zero times, and emits `"stored"` outcomes with a doc id and no job id; the doc id matches what a later directory scan of the same path computes |
| `IngestServiceTest` (extended) | `delete_source=true` with `source_path` and no `doc_id` is rejected; with `doc_id` it deletes only when the recorded stored path is under the upload root |
| `JobSourceSnapshotsTest` | the hardlink survives a `Files.move(..., REPLACE_EXISTING)` over the original path — the regression test for the headline finding; link released on COMPLETED and on terminal FAILED; falls back cleanly when hardlinks are unavailable |
| `QdrantBackendTest` (extended) | two threads ingesting the same doc id serialize: the second's points are not deleted by the first; `on_conflict=replace` is rejected with 409 when a non-terminal job holds that doc id and snapshots are unavailable |
| `UploadedDocumentStoreTest` (extended) | `kb_name` with a separator, a `..`, or a URL-encoded separator is rejected before any path is built; a `.pdf` without a `%PDF-` header is rejected; the temp sweep removes an aged `.part` and leaves a fresh one |
| `DirectoryIngestServiceTest` | unchanged — proves the fan-out extraction is behaviour-neutral |

The multipart binding itself has no unit test (it needs a live Quarkus HTTP
server); `scripts/smoke.sh` covers it end to end.

## Verification

1. `mvn -pl core test` — new + existing suites.
2. `mvn package` — full build.
3. `scripts/up.sh --gpu` then:
   ```bash
   curl -sS -X POST http://localhost:8080/ingest/upload \
     -F kb_name=uploadtest -F files=@a.pdf -F files=@b.pdf | jq
   curl -sS -X POST http://localhost:8080/ingest/upload \
     -F kb_name=uploadtest -F subdir=manuals -F files=@batch.zip | jq
   ```
4. On the host: `ls -R /tank/documents/uploadtest` shows the files in the
   expected tree, with no `.part` leftovers.
5. `GET /kb/uploadtest` shows the expected document count; a search returns
   hits from an uploaded file.
6. Re-upload `a.pdf` → the document count does **not** increase, and the file
   is overwritten in place (idempotency).
7. `POST /ingest/directory` over `/documents/uploadtest` → every file reports
   the **same** `doc_id` the upload returned, and the count still does not
   increase.
8. Restart the stack mid-drain → a queued visual job still finds its stored
   file and completes.
9. **Overwrite race:** upload a 30-page PDF (visual queued), immediately
   re-upload a *different* 30-page PDF under the same filename with
   `on_conflict=replace`, then let the queue drain. `inspect_page` on the doc
   must return pages from the **first** file, matching its chunks — or the
   second upload must have been refused with 409. A mixture is the bug.
10. **Store-only:** upload with `-F ingest=false` → every outcome is
    `"stored"` with a `doc_id` and no `job_id`, and `GET /kb/uploadtest` shows
    no new documents. Then `POST /ingest/directory` over the KB directory →
    each file reports the **same** `doc_id` the upload returned, and the count
    rises exactly once.
11. `DELETE /ingest/document?doc_id=…&delete_source=true` removes both the
    points and the file. The same call with `source_path` instead of `doc_id`
    returns 400. A `doc_id` whose stored path is under `/host` removes only the
    points and returns a warning.

## Red-team pass (local model, 2026-08-22)

Four adversarial passes (durability, concurrency, security, self-consistency)
run on the LAN model server, 20 findings, each verified against the source
before acceptance. Provenance check: 18/20 quotes verbatim from this document,
the other 2 re-punctuated — no fabricated citations.

Accepted and folded in above: the queued-job overwrite race (hardlink
snapshots), the shared-doc-id write race (per-doc-id lock), `kb_name` as a
confinement baseline, the client-controlled free-space input, the `FileStore`
assumption, temp-file leaks, `delete_source` path re-derivation, magic-byte
validation, and the 413-before-507 ordering.

Rejected, recorded so they are not re-raised:

| Finding | Why rejected |
|---|---|
| ZFS may commit the data and the rename in different txgs, leaving a zero-length file at the final path | The fsync in step 2 completes before the rename in step 3 is issued, so the ordering is enforced by the caller, not by txg grouping. A txg commits atomically. |
| No REST endpoint exists to poll an uploaded document's job | `GET /ingest/status/{jobId}` and `GET /ingest/jobs` already ship in `IngestResource`. The reviewer was not given that file. |
| `quarkus.http.limits.max-body-size` must also be set in `core` | `core` is a library with no HTTP server; only `server-http` binds a port. The ordering point underneath it *was* valid and is now documented. |
| `docker-compose.yml` has no `/documents` mount | True of the file today; adding it is in "Files touched". The reviewer read the current compose as if the plan were applied. It did surface a real hazard, now fixed: the code default `${user.home}/.pdf-rag-ingest/documents` would silently succeed inside the container if the mount were missing, so `INGEST_UPLOAD_REQUIRE_MOUNT` fails startup instead. |

One pre-existing issue surfaced that is **out of scope** for this plan:
`FileFetcher.fromPath` does `Files.readAllBytes`, so every ingest holds the
whole file in heap — at `ingest.max-file-bytes=100MB` and
`ingest.upload.parallelism=4` that is 400 MB of transient heap before rendering.
Uploads make large files easier to introduce but do not create the problem.
Worth its own issue.

## Decisions (settled 2026-08-22)

| # | Question | Decision |
|---|---|---|
| A | how a large upload returns without holding the request open | **store-only mode** (`ingest=false`), not a queued `FULL` job — keeps the VLM lane pure |
| B | ZIP expansion in v1 | **in**, with the zip-slip, entry-count, uncompressed-byte, and free-space guards |
| C | `delete_source` on `DELETE /ingest/document` | **in**, `doc_id` only; combining it with `source_path` is a 400 |

No open decisions remain. The plan is ready to implement on approval.
