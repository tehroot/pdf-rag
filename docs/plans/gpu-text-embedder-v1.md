# Plan: move the text embedder to the GPU and cap the sidecar's VRAM (v1)

Status: proposed
Author drafted: 2026-09-17

## Context

Measured on the R530 (Debian 13, 40 cores, 188 GiB, one RTX A4500 20 GB)
during the `dtic_archive` bulk ingest, 2026-09-17, 12,167 PDFs, about
1.3 million pages:

| Pipeline | Rate | Where the time goes |
|---|---|---|
| Text (Tika, chunk, bge embed, upsert) | about 4 documents/min | `pdf-rag-llama` runs bge-small on the CPU at 2000% (20 cores). `pdf-rag-http` at 140%. |
| Visual (render, ColQwen3 embed, upsert) | about 94 pages/min | GPU busy 60% of samples, idle 40%. `pdf-rag-colpali` at 740% CPU: image preprocessing between GPU batches. |

GPU memory sits at 19,259 MiB of 20,470 MiB and does not move. That is the
PyTorch caching allocator's steady reservation for the 4B bf16 model at
batch 16, not a leak. No out-of-memory event in three hours of sidecar
logs. The only load-related failures are readiness timeouts against the
sidecar, about 4% of files in the directory-ingest path, which the caller
retries.

Two facts drive this plan:

1. bge-small-en-v1.5 is 33M parameters, 67 MB in f16. On the GPU it embeds
   thousands of chunks per second. On 20 CPU cores it is the text-side
   bottleneck.
2. The 20 CPU cores that bge consumes are the same cores the sidecar needs
   for image preprocessing and `pdf-rag-http` needs for rasterizing. Freeing
   them lifts the visual duty cycle as a side effect.

## Design

Two changes, both configuration, plus one optional one-line code change.

### 1. `llama-server` on the GPU

Switch the image to the CUDA build and offload all layers. VRAM need:
model 67 MB, KV cache for 4 slots of 2048 context is small, CUDA context
about 300 MB. Budget 500 MB.

`docker-compose.gpu.yml` (the committed override) gains a `llama-server`
block, so the GPU host gets it by default and a CPU host is unaffected:

```yaml
  llama-server:
    image: ghcr.io/ggml-org/llama.cpp:server-cuda
    command: >
      -m /models/${LLAMA_MODEL_FILE:-bge-small-en-v1.5-f16.gguf}
      --embeddings
      --host 0.0.0.0
      --port 8081
      --ctx-size ${LLAMA_CTX_SIZE:-8192}
      --parallel ${LLAMA_PARALLEL:-4}
      --cont-batching
      --n-gpu-layers ${LLAMA_GPU_LAYERS:-99}
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: all
              capabilities: [gpu]
```

`command` must be repeated in full because compose replaces, not merges,
a scalar. The base file keeps the CPU image and command.

### 2. Sidecar VRAM headroom

The sidecar has no memory-fraction setting today (`config.py`). The
allocator will grow back to the ceiling and starve `llama-server` of its
500 MB after the next big batch, unless something bounds it. Two options,
the first is enough on its own:

**2a. Configuration only.** Reduce the peak, and let the allocator return
segments:

```
# .env
COLPALI_MAX_BATCH_SIZE=12       # was 16; peak activations scale with batch
COLPALI_BATCH_SIZE=12           # Java-side batch must match
PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True
```

`PYTORCH_CUDA_ALLOC_CONF` is read by PyTorch at import. The sidecar
service in `docker-compose.gpu.yml` passes it through:

```yaml
  colpali-server:
    environment:
      PYTORCH_CUDA_ALLOC_CONF: ${PYTORCH_CUDA_ALLOC_CONF:-expandable_segments:True}
```

Expected peak at batch 12: model 8.5 GB plus about 0.6 GB per page at
1280 visual tokens, so about 16 GB. That leaves 4 GB for the text
embedder and for allocator slack.

**2b. Hard cap (optional, one line).** Add to `config.py`:

```python
    gpu_memory_fraction: float = 0.0   # 0 = no cap
```

and in `loader.py`, before the model loads:

```python
    if cfg.device.startswith("cuda") and cfg.gpu_memory_fraction > 0:
        torch.cuda.set_per_process_memory_fraction(cfg.gpu_memory_fraction)
```

With `COLPALI_GPU_MEMORY_FRACTION=0.85` the sidecar cannot exceed
17.4 GB. An over-budget batch then raises a CUDA OOM inside the sidecar
instead of starving the neighbour. The sidecar returns 500 for that
request, `pdf-rag-http` maps it to a job failure, and the job record
carries the message. Prefer 2a first, add 2b if the allocator still
crowds out `llama-server`.

### 3. Queue workers

`INGEST_QUEUE_WORKERS` stays at 5 on this host. The override's default of
2 was tuned for a 3070. With 20 cores freed, 5 workers keep the sidecar
fed. Revisit if sidecar readiness timeouts rise above the current 4%.

## Rollout on the R530

The order matters. The sidecar must release VRAM before `llama-server`
claims it, and the ingest runner must not count the restart window as
file errors.

1. Pause the bulk runner: `pkill -STOP -f "[i]ngest_runner2"`. It holds no
   in-flight HTTP call longer than one 40-file batch, so wait for the
   current batch line in `/srv/pdf-corpus/ingest/run2.log`, then stop it.
2. Sync the repo to the R530 as usual. Pull the CUDA image:
   `docker pull ghcr.io/ggml-org/llama.cpp:server-cuda`.
3. Recreate the sidecar alone:
   `docker compose up -d --no-deps --force-recreate colpali-server`.
   Wait for `ready:true` on `http://127.0.0.1:8090/healthz`. Queued visual
   jobs requeue transiently during this window with no retry penalty
   (`SidecarUnavailableException` path, backoff 5 s to 60 s).
4. Recreate the text embedder alone:
   `docker compose up -d --no-deps --force-recreate llama-server`.
   Wait for the TCP healthcheck. Text embeds fail for about 30 s; only
   inline directory-ingest calls see that, and the runner was paused.
5. Verify (section below), then resume the runner:
   `pkill -CONT -f "[i]ngest_runner2"`.

`--no-deps` keeps compose from touching `pdf-rag-http`. If it restarts
anyway, that is safe (see "Stop and resume" below), it only bumps the
retry counter on the five or six in-progress jobs.

## Verification

- `nvidia-smi --query-compute-apps=pid,used_memory,process_name --format=csv`
  shows two processes: the sidecar under 17 GB and `llama-server` under
  1 GB.
- `curl -s http://127.0.0.1:8081/v1/embeddings -d '{"input":"test"}' -H 'Content-Type: application/json'`
  returns a 384-float vector.
- `docker stats --no-stream pdf-rag-llama` shows CPU under 100%.
- Text rate: `document_count` on `GET /kb/dtic_archive` rises by more than
  10 per minute while the runner posts batches. Before: 4.
- Visual rate: `visual_index_pages` rises faster than 94 per minute.
  Measure over 30 minutes; the queue is long enough.
- Sidecar readiness failures in `docker logs --since 1h pdf-rag-http`
  matching "sidecar is unreachable" stay at or below the current rate.

## Expected gain

| Side | Before | After (estimate) | Basis |
|---|---|---|---|
| Text | 4 docs/min | 12 to 20 docs/min | Embed cost drops to near zero; Tika and upsert remain, `pdf-rag-http` at 140% today has headroom to about 400%. |
| Visual | 94 pages/min | 110 to 130 pages/min | 20 freed cores shorten the preprocessing gaps that hold the GPU at 60% duty. |

The visual side stays GPU-bound after this change. Further gains come
from the visual token budget (`COLPALI_MAX_VISUAL_TOKENS` 1280 to 640) or
from a second sidecar replica, both out of scope for v1.

## Risks

- **Sidecar OOM at batch 12 with a pathological page.** Symptom: sidecar
  500 on one batch, job fails with the message. Mitigation: 2b, or batch 8.
- **CUDA image pull fails offline.** The R530 has outbound HTTPS; verified
  against archive.org on 2026-09-16.
- **Compose recreates `pdf-rag-http`.** Safe, see below. The
  `service_healthy` dependency then waits for both models before the queue
  drains, which is the designed behaviour.
- **Rollback.** Remove the two blocks from the override, `.env` values back
  to 16, recreate the two services with the same commands. No data is
  touched.

## Stop and resume of the visual queue

Verified against `core/src/main/java/org/hayden/backend/qdrant/QdrantBackend.java`
and `docs/components/ingest-queue.md` at commit 416c794.

**Every queued job survives a restart.** Each job is one JSON file in the
`pdf-rag_ingest-queue` volume. On startup `IngestQueue.init()` re-adds
`QUEUED` jobs to the in-memory queue. On 2026-09-17 that is about 4,100
jobs, all `kind: VISUAL`, each holding a `PATH` source.

**In-progress jobs are retried, not lost.** A job left `IN_PROGRESS` by a
stop is requeued at startup with `retryCount + 1`. After three such
restarts a job is marked `FAILED` with a "retry cap reached" marker. With
5 workers, at most 5 or 6 jobs are in this state at any stop.

**A retried job re-indexes the whole document, never part of it.** The
worker path `doVisualIngest` calls `pages.deleteDoc(kb, docId)` first,
then ingests every page. Page points use `UuidV5.forPage(docId, page)`,
so a re-run overwrites rather than duplicates. The cost of a stop is the
partial work of the in-flight jobs, a few documents.

**A sidecar restart alone costs nothing.** With `pdf-rag-http` running and
the sidecar down, the worker throws `SidecarUnavailableException`, the job
goes back to `QUEUED` with no retry penalty, and the worker backs off. The
5 in-flight jobs restart from the beginning when the sidecar returns.

**One dependency: the source paths must still exist.** A `PATH` job
re-fetches its file at run time. For `dtic_archive` the paths are the
staging symlinks under `/tank/documents/archive-org-dtic/ingest/`. Do not
delete `batch_*` or `r/r*` directories while jobs reference them. A missing
file fails the job inline, and inline failures do not retry.

**No re-index is needed for a planned stop.** The procedure is
`docker compose stop pdf-rag-http` (5 s grace for workers), then
`docker compose start pdf-rag-http`. A re-index of the visual side is only
needed if the model or its vector dimension changes, which this plan does
not do.
