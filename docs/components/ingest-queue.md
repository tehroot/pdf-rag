# Async ingest queue (`IngestJob` / `IngestQueue` / `IngestWorker`)

`core/src/main/java/org/example/jobs/` — three classes that let large PDF
ingests run in the background so the MCP tool call doesn't block the agent
for minutes.

| File | Role |
|------|------|
| `JobStatus.java` | Enum: `QUEUED` / `IN_PROGRESS` / `COMPLETED` / `FAILED`. |
| `IngestJob.java` | Record: jobId, status, request snapshot, docId, timestamps, result, error, warnings, retryCount. |
| `IngestQueue.java` | In-memory queue + file-backed persistence. |
| `IngestWorker.java` | Background thread(s) draining the queue. |

The queue exists because **CPU-only ColPali sidecars are slow**. Embedding a
100-page PDF on a CPU is multi-minute work. We can't hold an MCP connection
that long; the agent times out. Async ingest lets the tool return a
`{processing_status: "queued", job_id: ...}` immediately, and the agent
polls `get_ingest_status(job_id)` until completion.

## When does an ingest get queued?

`QdrantBackend.shouldQueue(file, visualRequested)`:

- **No** if `enable_visual_index=false` (text-only ingest is fast everywhere).
- **No** if the file isn't a PDF (no page count available; embedding a single
  DOCX is fast).
- **No** if PDF page count `< ingest.queue.sync_threshold_pages` (default 20).
- **Yes** otherwise.

The threshold is the one knob. On a CPU sidecar deployment, set
`INGEST_ASYNC_THRESHOLD_PAGES=5` to queue almost everything. On a GPU
deployment where 100-page ingests are ~10 seconds, leave the default at 20
so most ingests stay synchronous.

## Lifecycle

```
agent calls ingest_document
       │
       ▼
QdrantBackend.ingest
   ├─► fetch file
   ├─► validate mode consistency
   ├─► pre-flight sidecar health check
   │
   └─► shouldQueue? ──── yes ────► IngestQueue.submit
                                       │   (writes <jobId>.json to disk,
                                       │    appends to in-memory queue)
                                       ▼
                                  IngestResult.queued{jobId, "queued", 0 chunks}
                                       │
                                       ▼
                                  agent receives, starts polling
                                  get_ingest_status(jobId)
                       (background)
                       ──────────────►
                       IngestWorker thread
                         loop:
                           queue.take()  ◄── blocks up to poll_timeout_ms
                                  │
                                  ▼ (atomically marks IN_PROGRESS)
                           processOne(job)
                             ├─► QdrantBackend.ingestForWorker(job)
                             │       (re-fetches, re-validates, doIngest)
                             ├─► queue.markCompleted(jobId, result)   on success
                             └─► queue.markFailed(jobId, error)        on exception
```

When the agent's next `get_ingest_status` call lands, the job's status is
`COMPLETED` or `FAILED` and the response carries the final `IngestResult`
(with chunk count, page count, etc.) under `job.result`.

## `JobStatus`

```
       submit              worker pick           finish
new ───────────► QUEUED ────────────► IN_PROGRESS ───────► COMPLETED
                  ▲                          │ error
                  │ restart recovery         ▼
                  │                       FAILED  (terminal — no retry)
                  └────── retry ──────── (only on restart, not on inline error)
```

`isTerminal()` returns true for `COMPLETED` and `FAILED`. Agents poll until
`isTerminal() == true`.

**Important nuance: inline failures don't retry.** When the worker catches an
exception (sidecar down, corrupt PDF, mode mismatch detected late), the job
moves straight to `FAILED`. Retries only happen on **crash recovery** —
i.e., if the JVM dies mid-ingest, leaving the job in `IN_PROGRESS`.

## `IngestJob`

```java
public record IngestJob(
    String jobId,
    JobStatus status,
    IngestRequest request,
    String docId,                // pre-allocated, shared with worker
    Instant submittedAt,
    Instant startedAt,
    Instant completedAt,
    IngestResult result,         // null until COMPLETED
    String error,                // null unless FAILED
    List<String> warnings,
    int retryCount               // bumped on crash recovery
)
```

`IngestJob.queued(request, docId)` creates a fresh job. The state-transition
helpers (`withStarted`, `withCompleted`, `withFailed`, `requeueAfterCrash`)
return new records — the type is immutable, all writes go through the queue.

## `IngestQueue`

In-memory + file-backed. Two data structures:

- `ConcurrentHashMap<String, IngestJob> jobs` — full state by jobId.
- `LinkedBlockingQueue<String> pending` — FIFO of jobIds awaiting a worker.

Every state change writes a JSON file:

```
${ingest.queue.persistence_path}/
  <jobId>.json
  <jobId>.json
  ...
```

Atomic writes via tmp file + rename (same pattern as `FilesystemPageImageStore`).

### Persistence format

A direct Jackson serialization of `IngestJob`. Round-trips clean because the
record is a tree of simple types + Instants (Jackson's `JavaTimeModule`
handles those via ISO-8601 strings).

### Startup recovery

`@PostConstruct init()` scans the persistence dir:

| Persisted state | Action on startup |
|-----------------|-------------------|
| `QUEUED` | Re-add to in-memory pending queue. |
| `IN_PROGRESS` | Worker crashed mid-ingest → `requeueAfterCrash` (status → `QUEUED`, retryCount++). |
| `IN_PROGRESS` AND retryCount ≥ `max_retries` (default 3) | Move to `FAILED` with "retry cap reached" marker. |
| `COMPLETED` / `FAILED` | Load into the map, don't requeue. |

A corrupt JSON file (parse error) doesn't blow up the queue — we write a
sibling `<file>.json.err` for the operator to investigate and skip.

### Public API

```java
public IngestJob submit(IngestJob job);                              // adds to queue, persists
public Optional<IngestJob> take(long timeout, TimeUnit unit);        // blocks up to timeout
public Optional<IngestJob> getJob(String jobId);
public List<IngestJob> listJobs();
public void markCompleted(String jobId, IngestResult result);
public void markFailed(String jobId, String error);
public int pendingCount();
public int totalCount();
```

`take()` atomically dequeues + transitions the job to `IN_PROGRESS`,
persisting the new state before returning. This is the load-bearing
synchronization point — once `take()` returns, the worker owns the job
until it calls `markCompleted` or `markFailed`.

`markFailed` honors the retry cap: when `job.retryCount >= max_retries`,
the error message gets a `"Retry cap (N) reached."` prefix so operators
can spot exhausted jobs.

## `IngestWorker`

`@ApplicationScoped @Startup` — Quarkus's `@Startup` ensures the
`@PostConstruct` fires at boot rather than waiting for first injection.
Spawns `ingest.queue.worker_threads` daemon threads.

```java
private void workerLoop() {
    while (running.get()) {
        try {
            Optional<IngestJob> next = queue.take(pollTimeoutMs, TimeUnit.MILLISECONDS);
            next.ifPresent(this::processOne);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Throwable t) {
            // A single job's RuntimeException shouldn't kill the thread.
            LOG.error("Worker loop hit unexpected exception", t);
        }
    }
}

public void processOne(IngestJob job) {
    try {
        IngestResult result = backend.ingestForWorker(job);
        queue.markCompleted(job.jobId(), result);
    } catch (Exception e) {
        queue.markFailed(job.jobId(), e.getMessage());
    }
}
```

`processOne` is public so tests can drive single-iteration logic without
thread-timing dances. The worker thread just calls it in a loop.

### Shutdown

`@PreDestroy stop()`:
1. Flip `running` to false.
2. Interrupt each worker thread.
3. Wait up to 5 seconds for each to exit via `Thread.join`.

Jobs mid-ingest at shutdown get left in `IN_PROGRESS` on disk. Next startup
will requeue them (with `retryCount++`).

### Why `ingestForWorker` instead of `ingest`?

The public `ingest()` method does the sync-vs-queue routing decision. The
worker needs to bypass that and run the work directly. So `QdrantBackend`
exposes both:

```java
public IngestResult ingest(IngestRequest req)        // public; may queue
public IngestResult ingestForWorker(IngestJob job)   // public; always runs
IngestResult doIngest(req, file, docId, visualRequested)   // package-private shared work
```

The worker re-fetches the file from the persisted `IngestRequest`. For URL
sources, the URL must still resolve. For PATH sources, the file must still
exist. For INLINE sources, the base64 lives inside the persisted job
itself, so no external dependency.

## The `get_ingest_status` tool

Wraps `IngestQueue.getJob(jobId)`:

```java
@Tool("get_ingest_status")
public IngestJob getIngestStatus(String job_id) {
    return ingestQueue.getJob(job_id)
            .orElseThrow(() -> new IngestException("Unknown job_id: " + job_id));
}
```

Returns the full `IngestJob` record. Agents poll until
`status == COMPLETED` or `FAILED`, then read `result` (or `error`).

## Configuration

| Key | Env | Default | Notes |
|-----|-----|---------|-------|
| `ingest.queue.persistence_path` | `INGEST_QUEUE_PATH` | `${user.home}/.pdf-rag-ingest/queue` | File-backed persistence root. |
| `ingest.queue.worker_threads` | `INGEST_QUEUE_WORKERS` | `1` | Concurrent workers. Most useful on multi-GPU sidecars. |
| `ingest.queue.sync_threshold_pages` | `INGEST_ASYNC_THRESHOLD_PAGES` | `20` | PDFs at or above this page count get queued; smaller stay sync. |
| `ingest.queue.max_retries` | — | `3` | Cap on crash-recovery retries before a job is permanently `FAILED`. |
| `ingest.queue.poll_timeout_ms` | — | `1000` | Worker poll interval. |

## Failure modes

| Case | Result |
|------|--------|
| Queue persistence dir not writable | `IngestException` at startup; container fails healthcheck. |
| `INGEST_QUEUE_PATH` not set | `IngestException` at startup. |
| Persisted JSON corrupt | `.json.err` sibling written; queue continues without that job. |
| Worker thread exits unexpectedly | Logged; remaining worker threads continue (degraded throughput). |
| Worker thread interrupted (shutdown) | Thread exits cleanly; mid-ingest job left `IN_PROGRESS` → next startup requeues with `retryCount++`. |
| Job exceeds retry cap | Marked `FAILED` permanently with "Retry cap (N) reached" prefix. |
| Sidecar down mid-ingest | Worker catches exception, marks job `FAILED`. Agent sees the error message on next poll. |
| Same URL ingested twice into a visual KB | Two distinct doc_ids → duplicate page coverage. Same as sync ingest; no dedupe by source. |
| Worker can't re-fetch URL after submit | Worker marks `FAILED` with the I/O error. |

## Why it's like this

- **File-backed persistence over Qdrant payload / external DB.** A
  `<jobId>.json` file is the simplest possible persistence. No new
  infrastructure dependency. Easy to debug — just `cat` the file. Easy to
  back up — `tar` the queue dir. The trade-off is no built-in multi-host
  coordination; in v1 we assume a single-host deployment.
- **At-least-once retry on crash, no retry on inline failure.** Restart
  recovery is the only retry case because we know the job didn't finish.
  Inline failures (sidecar errors, bad PDFs) are usually deterministic;
  retrying would just fail again. If the operator wants to retry a failed
  job, they re-call `ingest_document`.
- **Re-fetch in the worker.** Storing fetched bytes in the job would inflate
  persistence (a 100 MB PDF in a JSON file). Re-fetching is fast for path
  and inline sources; URL sources accept the "URL must still resolve"
  constraint. vNext could add an optional bytes-cache for URL jobs.
- **Sync path for small ingests.** Not every ingest needs to queue. Small
  born-digital PDFs and text-only ingests finish in seconds; making the
  agent poll for those would add latency. The page-count heuristic catches
  the "this will be slow" case automatically.
- **`@Startup` for the worker.** Without it, the worker bean is constructed
  lazily on first inject — which doesn't happen if no one references it.
  Eager startup ensures the queue is being drained even if no client has
  connected yet.
- **`pollTimeoutMs` instead of an indefinite blocking take.** Lets the
  worker periodically check `running` so shutdown is clean. The cost is
  one wakeup per second when the queue is idle — negligible.

## Tests

`IngestQueueTest` (14 tests):

- Submit + persist round-trip.
- Take blocking semantics: empty queue returns empty Optional after timeout.
- FIFO order across multiple submits.
- `markCompleted` and `markFailed` transitions with attached result/error.
- Retry-cap behavior on `markFailed`.
- `markCompleted` on unknown jobId throws.
- `listJobs` covers all states.
- Restart recovery: `QUEUED` re-enters the pending queue; `COMPLETED`/`FAILED`
  do not.
- `IN_PROGRESS` on restart → requeued with `retryCount++`.
- `IN_PROGRESS` past the retry cap → moved to `FAILED`.

`IngestWorkerTest` (6 tests):

- `processOne` happy path: completes + attaches result.
- `processOne` with `IngestException` → marks `FAILED` with the exception message.
- `processOne` with `RuntimeException` → marks `FAILED`.
- Null exception message → records the exception's class name.
- Worker thread start/stop honors the shutdown flag.
- End-to-end: submit → worker picks up → completes (polls for status).

Both use plain JUnit 5 + reflection (no `@QuarkusTest`). Worker tests use a
`FakeBackend` (extends `QdrantBackend`, overrides `ingestForWorker`) so we
don't need a live Qdrant or sidecar.
