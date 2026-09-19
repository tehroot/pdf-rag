# Plan: ingest work planner (v1)

Status: proposed, for review
Author drafted: 2026-09-18

## Why

The DTIC load (12,167 PDFs, ~1.3 M pages, R530 + eight remote sidecar
replicas, 2026-09-16..18) showed that the ingest path has no planning
layer. Every decision about *what* runs, *when*, and *where* is made by a
default number or by file order:

| Decision | Made today by | Consequence observed |
|---|---|---|
| Which files to submit, in what order | an external runner script, filename order, 40 at a time | ten byte-identical copies of a 1,765-page report handed to ten workers at once; 5–15 min completion gaps |
| Whether a file is new | its path (deterministic doc id from the path) | 952 padded-accession copies embedded as new documents, ~25 % of the pages written |
| Which job a worker takes next | FIFO on job age | a 4-page job and a 1,765-page job are equal; long documents synchronize workers |
| Stage concurrency | fixed: 4 text threads per directory call, N visual workers, 1 embed request per worker | a rendering worker idles its share of the GPU pool; GPUs at 60–79 % duty with nine replicas |
| When a document's vectors are written | at the end of the document, in 4-page calls | 300 MB write bursts, 1–2 GB heap per worker on long documents, a mid-document failure loses the document |
| Whether to slow down | never | Qdrant upsert time per page tripled over a day while nothing backed off |

The sidecar pool, the balancer, the gRPC upsert and the wire encoding
(plans `sidecar-pool-v1`, `sidecar-throughput-v1`) made every stage faster.
They did not change any of the decisions above. This plan does.

## Goals

1. **Identity by content.** The same bytes are ingested once per knowledge
   base, whatever the path.
2. **Shaped ordering.** The queue serves a mix of sizes, caps concurrent
   giants, and is fair across knowledge bases.
3. **Decoupled stages.** Rendering never starves embedding; embedding never
   waits on Qdrant; each stage is sized from its measured cost.
4. **Bounded resources and backpressure.** Memory per worker is bounded by a
   batch, not a document; a slow Qdrant slows admission instead of growing
   queues.
5. **Admission and planning inside the service.** No external script needs
   to scroll Qdrant to know what is missing.
6. **A plan before a load.** A dry run answers: how many files, how many
   already present or duplicate, how many pages, how long, how much disk and
   RAM at the end.

Non-goals for v1: distributed Qdrant, cross-host job placement other than
the existing embed balancer, changing the vector model or collection
layout, replacing the durable job queue.

## Current shape (what the plan builds on)

- `IngestQueue`: durable, one JSON file per job, an in-memory
  `LinkedBlockingQueue<String>` of job ids; `take()` polls the head. Crash
  recovery requeues `IN_PROGRESS` with `retryCount+1`; transient failures
  (`SidecarUnavailableException`) requeue without penalty.
- `IngestJob`: `jobId, status, request, docId, submittedAt, startedAt,
  completedAt, result, error, warnings, retryCount, kind`. `kind` is `FULL`
  or `VISUAL`.
- `QdrantBackend.ingest`: text runs synchronously at submit; a PDF with
  `pages >= ingest.queue.sync_threshold_pages` (env
  `INGEST_ASYNC_THRESHOLD_PAGES`, 5 in production) queues a `VISUAL` job.
  Doc id: caller-supplied deterministic (directory scans: UUIDv5 of the
  path) or random.
- `ColPaliPipeline.ingestPages`: render all pages → store PNGs → embed in
  batches of `COLPALI_BATCH_SIZE` through the balancer → after the last
  batch, upsert in `INGEST_QDRANT_MULTIVECTOR_UPSERT_BATCH`-page gRPC calls.
  Holds the per-document lock throughout.
- `DirectoryIngestService` + `BatchIngestExecutor`: 4 files in parallel per
  HTTP call; text side inside the call.
- `scripts/bulk_ingest_runner.py`: outside the service; computes "missing"
  from Qdrant scrolls and the jobs API, stages symlink batches, posts them
  3 at a time.

## Design

Six steps. Each is independently shippable and measurable; the order is
by value per day of work. Steps 0–3 remove every cause of this week's
pauses; step 4 is the structural change; step 5 is the operator surface.

### Step 0 — admission inside the service (1 day)

New REST endpoint on `IngestResource`:

```
POST /ingest/plan
{ "directory": "/host/...", "kb_name": "...", "recursive": true,
  "extensions": ["pdf"], "dedupe": "path|content" }
→ { "files_found": N, "present": n1, "queued_or_running": n2,
    "duplicates": n3, "new": n4, "pages_estimated": P, "bytes": B,
    "estimate": { "text_minutes": ..., "visual_minutes": ...,
                  "qdrant_bytes": ..., "resident_ram_bytes": ... },
    "files": [ { "path", "state": "present|queued|duplicate_of:<docId>|new",
                 "pages", "bytes", "text_layer": true|false } ] }
```

"Present" = the deterministic doc id (or content hash, step 1) has chunk
points in `<kb>` or page points in `<kb>_pages`; "queued or running" = a
job for that doc id in `QUEUED`/`IN_PROGRESS`. This is exactly the
computation the runner script does with Qdrant scrolls, moved to where
the data is and done with `count`/`retrieve` by id instead of full scans.

`POST /ingest/directory` gains `skip_present` (default `true`): present
and queued files are reported as `skipped` in the response and not
touched. That alone removes the "reused staging directory re-ingests
everything" failure and the delete-then-vacuum churn it caused in Qdrant.

Touch points: `IngestResource`, `DirectoryIngestService`, a new
`AdmissionService` (counts by id against `QdrantClient`, job lookup
against `IngestQueue`). Tests: WireMock counts; the runner's behaviour
reproduced as unit cases (present / queued / new / error-twice).

### Step 1 — identity by content (2 days)

- `FetchedFile` gains `sha256` (computed once in `FileFetcher`; the bytes
  are already in memory). Every chunk and page payload carries
  `content_hash`.
- A per-KB hash index: a payload index on `content_hash` in `<kb>` is
  enough to answer "have I seen these bytes" with one filtered `count`.
  No new collection.
- On submit with `dedupe=content`: if the hash is present under another doc
  id, write an **alias**: a single point in `<kb>` with payload
  `{ doc_id: <new>, alias_of: <canonical docId>, filename, source,
  content_hash }` and no text (a zero vector is not needed: the point is
  found by payload filter only). No chunks, no pages, no visual job.
- Search: `ResultDeduper` already collapses overlapping chunks; extend
  `SearchHit.metadata` with `aliases: [filenames]` when the canonical doc
  has alias points, so a result under one file name can show the other
  names the same bytes were submitted under. (Decision A below: show or
  hide.)
- Directory scans keep the path-derived doc id for the canonical copy, so
  existing deterministic ids and the deploy-time snapshots are unchanged.

Touch points: `FileFetcher`, `FetchedFile`, `ChunkPipeline`,
`ColPaliPipeline`, `QdrantBackend.ingest`, `QdrantClient.ensurePayloadIndexes`,
`FusionEngine`/`ResultDeduper` for alias display. Tests: same bytes under
two paths → one set of vectors, one alias; delete of the canonical doc
must also delete its aliases (or promote one — Decision A).

### Step 2 — job attributes and a scheduling policy (2 days)

`IngestJob` gains, with defaults so persisted jobs still load:

```
int pages;           // from the submit-time probe (already computed for the split decision)
long bytes;
boolean textLayer;   // TextLayerProbe result, or false if unknown
String sizeClass;    // "S" <= 20 pages, "M" <= 100, "L" <= 400, "XL" above (configurable)
long costEstimateMs; // pages * measured per-page cost for the job kind
```

`IngestQueue.take()` consults a `SchedulingPolicy` instead of polling the
head. The in-memory structure becomes per-(KB, sizeClass) FIFO lanes; the
persisted form is unchanged (one JSON per job). Policy `weighted` (the new
default):

```
take():
  for each lane in round-robin over KBs, then classes S, M, L, XL:
    if class is XL and running(XL) >= max_concurrent_xl: skip
    if class is L  and running(L) >= max_concurrent_l : skip
    pick the oldest job in the lane; if it is older than max_wait, it wins outright
  fallback: the oldest job overall (starvation floor)
```

Config (`ingest.queue.policy=fifo|weighted`, `max_concurrent_xl=2`,
`max_concurrent_l=6`, `max_wait=30m`, class bounds). `fifo` is the
current behaviour and stays selectable.

Why lanes and caps rather than shortest-job-first alone: SJF empties the
queue count fast but starves long documents until the end and then runs
them all together, which is the pattern that idled the GPUs this week;
caps keep a bounded number of giants in flight at all times.

Touch points: `IngestJob`, `IngestQueue` (in-memory index only),
`QdrantBackend.ingest` (fills the attributes; the page count is already
read there), a `SchedulingPolicy` interface with two implementations.
Tests: lane fairness, the XL cap, the starvation floor, persisted-job
compatibility (a job file without the new fields loads as class `M`).

### Step 3 — stream the upserts (1 day)

`ColPaliPipeline.ingestPages` upserts each embedded batch as soon as it
returns instead of accumulating a document's vectors. Point ids are
`UuidV5(docId, page)`, so a retry overwrites. The per-document lock is
still held across the whole document (replace semantics on retry need the
`deleteDoc` to precede the first write and nothing else to write the same
doc meanwhile).

Effects: per-worker memory bounded by one batch (~30 MB) instead of a
document (330 MB for 200 pages); Qdrant sees a steady stream instead of
bursts; a failure mid-document keeps the pages already written and the
retry re-embeds only from the start (v1 keeps the full re-embed for
simplicity; resuming at the first missing page is a follow-up).

Touch points: `ColPaliPipeline.ingestPages` (loop restructure), heap
guidance in `deployment.md` (48 GB → the default becomes adequate).
Tests: the upsert mock receives N/batch calls interleaved with embed
calls; a failing third batch leaves batches one and two written.

### Step 4 — separate stage pools (4–5 days)

Replace "one worker owns a document end to end" with three pools joined by
bounded queues:

```
IngestQueue ──take()──► render pool ──[page-batch queue, cap R]──► embed pool ──[vector-batch queue, cap E]──► upsert pool
                       (CPU, PDFBox)                               (HTTP → colpali-lb)                        (gRPC → Qdrant)
```

- A document is opened by the render pool, which emits page batches of
  `COLPALI_BATCH_SIZE`; the last batch carries an end marker.
- The embed pool sends batches to the balancer and forwards the vectors.
- The upsert pool writes batches (step 3 already made this per batch) and
  marks the job completed when the end marker's batch is written.
- Bounded queues are the backpressure: if Qdrant slows, the vector-batch
  queue fills, the embed pool blocks, the page-batch queue fills, the
  render pool blocks, and `take()` stops admitting. Memory is bounded by
  `R + E` batches, independent of document length.
- Pool sizes from config with defaults derived from measured cost per page
  (render 0.78 s, embed 0.76 s wait, upsert 0.20 s on the R530): render
  threads ≈ 2× embed slots; embed slots ≈ 2 × sidecar replicas; upsert
  threads ≈ 4.
- Job state: `IN_PROGRESS` now means "has batches in flight"; crash
  recovery is unchanged (the job requeues and starts over, deleting its
  partial pages first).
- The per-document lock moves to the upsert stage: taken at the first
  batch's `deleteDoc`, released at the end marker.

Touch points: `IngestWorker` (becomes the render pool driver),
`ColPaliPipeline` (split into render / embed / upsert functions),
`IngestQueue` (in-flight accounting per stage for the policy's caps).
Tests: end-to-end with a fake sidecar and Qdrant; a slow upsert mock must
throttle admission; a mid-document sidecar failure must requeue without
leaking a batch.

### Step 5 — the plan, estimates, and bulk mode (2 days)

- `POST /ingest/plan` (step 0) gains estimates from a rolling
  `StageCostModel`: per-page cost per stage, updated from every completed
  job (the numbers already logged as `render=`, `embed=`, `upsert=`).
- `bulk_mode` on a knowledge base: suspends graph building on `<kb>_pages`
  (`indexing_threshold` raised), records the previous value, and restores
  it when the plan's jobs are all terminal; exposes graph-build progress
  (`indexed_vectors_count` toward the target) on `GET /kb/{name}`.
- `GET /ingest/stats`: pages per minute per stage, queue depths per pool,
  upsert latency p50/p90, embed latency per replica, jobs per size class.

Touch points: `IngestResource`, `KnowledgeBaseResource`, `QdrantClient`
(collection PATCH), a `StageCostModel` bean.

## Observations from the load's tail (2026-09-19), mapped to steps

| Observed | Cause | Step that removes it |
|---|---|---|
| 3 jobs stuck `IN_PROGRESS` for a day, no pages written | `OutOfMemoryError` escaped the worker's `catch (Exception)`; now recorded as failed (a328091) | 3 (bounded memory) and 4 (backpressure) |
| 24 files reported complete with text and no pages | the runner's "done" test accepted chunks alone | 0 (`present` means both sides, or a terminal job) |
| a pass ran a whole hour on one GPU while eight idled | a recreate without the pool compose file | 5 (`/ingest/stats` shows per-replica embed latency; the plan's estimate would have flagged 7 s/page) |
| "sidecar unreachable" at submit while eight replicas idle | the balancer's health probe targets the local replica with a 1 s timeout | 4 (the embed pool owns replica health, admission does not probe a single upstream) |
| 5 files failed on a masked 120 s embedder timeout | one request per chunk batch, twelve threads on eight slots | 4 (bounded embed queue sized from slot count) |
| 39 files failed on lone UTF-16 surrogates in two different parsers | PDFBox output and a window that splits pairs (fixed at extraction and payload, ea33775, 6426e69) | none needed; noted because step 1's hash must be taken on bytes, not text |

## Data model changes, compatibility

| Change | Backward compatible? |
|---|---|
| `IngestJob` new fields | Yes: absent in old files → defaults (class `M`, cost 0). |
| Payload `content_hash` | Yes: absent on old points; dedupe by content only sees new ingests until a backfill (a scroll + set-payload job, optional). |
| Alias points | New payload shape; search ignores points without vectors today, so nothing breaks before the alias display lands. |
| `take()` policy | `fifo` reproduces today's order. |
| Stage pools | Job lifecycle unchanged from the outside; `INGEST_QUEUE_WORKERS` maps to the embed pool size. |

## Rollout and validation

One step at a time, each behind config, measured on a real corpus slice:

1. Step 0 + 1 on the next corpus: plan output vs. what the runner computed
   (must agree), duplicate count vs. checksum scan.
2. Step 2 on: completion gaps per 5-minute bucket (target: no bucket under
   50 % of the median), XL jobs in flight never above the cap.
3. Step 3 on: `pdf-rag-http` RSS with 20 workers under 12 GB; Qdrant
   upsert p90 flat across a document's lifetime.
4. Step 4 on: GPU duty on the pool above 90 % (power-draw sampling on
   big-dumb, `nvidia-smi` on the R530) with rendering saturated; queue
   depths bounded under a throttled Qdrant.
5. Step 5: plan estimate within 20 % of the measured load time.

## Risks and decisions

- **Decision A, aliases in results.** Show every file name that maps to
  the same bytes (one hit, `aliases` list) or hide all but the canonical.
  Recommendation: show, since the DTIC accession copies are how users will
  look them up.
- **Decision B, fairness scope.** Per knowledge base only (v1) or also per
  submitter/plan. Recommendation: per KB in v1.
- **Decision C, step 4 timing.** It is the largest change and the only one
  that touches crash-recovery semantics. Recommendation: after steps 0–3
  have run one full corpus.
- **Hash cost.** SHA-256 of a 240 MB PDF is ~0.5 s; negligible against
  rendering. Files above the upload cap are already rejected earlier.
- **Policy starvation.** The `max_wait` floor guarantees progress for
  every lane; tested explicitly.
- **Queue in-memory rebuild.** Lanes are rebuilt from the persisted files
  at startup exactly as the FIFO is today.

## Effort

| Step | Days | Removes |
|---|---|---|
| 0 admission | 1 | external runner, staging-reuse re-ingests, half the vacuum churn |
| 1 content identity | 2 | duplicate embeddings, ~25 % of pages on corpora like DTIC |
| 2 policy | 2 | worker synchronization on giants, completion gaps |
| 3 streamed upserts | 1 | write bursts, 48 GB heap, lost work on mid-document failure |
| 4 stage pools | 4–5 | render/embed coupling, unbounded memory, no backpressure |
| 5 plan and bulk mode | 2 | manual Qdrant toggling, guesswork estimates |

Steps 0–3 are one week and can start when the current load completes.
