# Plan: split visual ingest into a dedicated queue lane (v1)

Status: approved, implementing
Author drafted: 2026-07-15

## Context

Bulk directory ingests stall-step through the pipeline: each queued job runs
Tika → chunk → bge embed → upsert, *then* render → VLM embed → upsert, serially
inside one worker thread. The GPU idles during the text phase; the CPU idles
during the VLM phase. Observed on a large PDF set on the R530/3070 deployment —
throughput ≈ text time + visual time per file, when it should be ≈ max of the
two streams.

The enabling observation: chunks and pages are only ever joined at **search**
time, via the shared docId + page ranges (`FusionEngine`). Nothing links them
at ingest. So the two sides can run as fully independent streams with zero new
"crosslink" machinery.

## Design

Invert the sync/queue routing in `QdrantBackend.ingest`:

1. **Text always runs synchronously at submit** — including for large PDFs
   that today queue everything. Tika+bge per file is seconds; the caller gets
   a real `chunk_count` immediately and the doc is text-searchable right away
   (today a queued file returns `chunk_count: 0` until the job drains).
2. **Only visual work is queued** — a new `VISUAL` job kind. The worker path
   for it: fetch → `pages.deleteDoc` (replace semantics / retry cleanup) →
   render → VLM embed → upsert pages. No Tika, no bge.
3. The queue thereby becomes a pure VLM lane: FIFO, GPU back-to-back, while
   the directory scan's request thread streams Tika+bge file after file.
   Fusion picks up pages as they land; searches during the drain degrade
   gracefully to partial visual coverage.

`shouldQueue` (the routing heuristic) is unchanged: visual-enabled PDFs at or
above `ingest.queue.sync_threshold_pages` queue; everything else runs fully
sync through the existing `doIngest`.

### The capability-flag trap and its fix

`validateModeConsistency` infers "KB is visual" from `<kb>_pages` existing.
With text landing immediately and visual deferred, file 2 of a fresh-KB scan
would see `chunks exist + no _pages + visual requested` → hard reject.

Fix: **create `<kb>_pages` eagerly at submit time**, before queueing — the
sidecar is already health-checked at submit, and `GET /info` supplies
`vector_dim`. New `ColPaliPipeline.ensureCollectionFor(kbName)`. The
capability flag is truthful from the first submit; the worker just upserts.
Ordering within the queue branch: sidecar health → ensure `_pages` → text
ingest → enqueue VISUAL job (fail-fast before any writes if the sidecar is
misconfigured; a failed text ingest can leave an empty `_pages`, which is
harmless and idempotent).

### Job model

- `JobKind` enum: `FULL`, `VISUAL`. New field on `IngestJob`; persisted jobs
  from before this change deserialize with `kind == null`, normalized to
  `FULL` via `effectiveKind()` — so in-flight jobs across an upgrade restart
  recover through the legacy both-pipelines path unchanged.
- `IngestJob.queuedVisual(req, docId)` factory; existing `queued(...)` stays
  (legacy/full semantics, used by tests).
- `IngestResult.queued(...)` gains a variant carrying the sync-ingested
  `chunkCount` and a message like
  `"text ingested (214 chunks; searchable now); visual indexing queued as job <id>"`.
  `processingStatus` stays `"queued"` so `DirectoryIngestService`'s
  completed/queued/failed classification is untouched; per-file outcomes now
  show real chunk counts for queued files.

## Change surface

- `core/.../jobs/JobKind.java` — new enum
- `core/.../jobs/IngestJob.java` — `kind` field + `effectiveKind()` + factory
- `core/.../backend/qdrant/QdrantBackend.java` — queue branch does sync text +
  eager `_pages` + enqueue VISUAL; `ingestForWorker` dispatches on kind
- `core/.../backend/qdrant/ColPaliPipeline.java` — `ensureCollectionFor(kb)`
  (refactor the existing ensure block out of `ingestPages`)
- `core/.../ingest/IngestResult.java` — queued-with-chunkCount factory
- Tests: `QdrantBackendTest` (split routing, worker VISUAL path, legacy FULL
  recovery, no text re-embed on worker path), `IngestQueueTest` (kind
  persistence round-trip incl. legacy null → FULL)
- Docs: `docs/components/ingest-queue.md`, CLAUDE.md sync/async section

## Not in v1

- Cross-file VLM batch packing (merging small files' page batches). Revisit
  only if the GPU still shows gaps after this change.
- Backfill tooling for KBs mid-drain; `get_ingest_status` remains the poll
  surface.

## Tuning note

With the VLM lane isolated, `INGEST_QUEUE_WORKERS=2` lets one worker rasterize
(CPU) while another's batch is on the GPU — the sidecar serializes GPU work,
so this fills inter-file render gaps without contention.
