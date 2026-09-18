# Plan: ColPali sidecar throughput (v1)

Status: proposed — steps 1-4 NOT started. Superseded for now by the
replica pool (sidecar-pool-v1.md), which gave 5-6x without touching the
sidecar. The findings below still hold and steps 1 and 4 are the next
per-replica gains; step 4 moved up in priority (see "Status update").
Author drafted: 2026-09-17

## Context

The visual index is the long pole of a bulk ingest. On the R530 (RTX A4500
20 GB, `TomoroAI/tomoro-colqwen3-embed-4b`, bf16, batch 12, 1,280 visual
tokens) the sidecar sustains about 92 pages/min. The DTIC corpus has about
1.2 million pages left, which is nine days. Moving the text embedder to the
GPU and fixing the doc-lock stripes (`gpu-text-embedder-v1.md`) did not
change this number, because the sidecar is the bottleneck.

## Measurement (2026-09-17, live workload)

Method: `py-spy record --pid 1 --rate 50 --duration 60 --nonblocking`
inside `pdf-rag-colpali` (2,201 samples), plus the uvicorn access log for
service time, plus `pdf-rag-http` log lines `ingest visual ... render=
embed= upsert=` for the client view.

**Service time.** 256 consecutive `/embed_pages` completions: median
7.0 s, p25 6.6 s, p75 8.2 s, for batches of up to 12 pages. That is about
0.58 s per page at full batches and matches 92 pages/min.

**Where the 7 s goes.** All samples are on ONE thread (the asyncio event
loop). The endpoint is `async def embed_pages` and calls the synchronous
embed directly, so nothing overlaps and `/healthz` cannot answer during a
batch.

| Phase | Share | Seconds of a 7 s batch | Note |
|---|---|---|---|
| Model forward | 43.8% | 3.1 | Includes CPU waits on the GPU. 21.4% of ALL samples sit on one line: `modeling_qwen3_vl.py:227`, the per-image attention loop in the vision tower (see below). |
| `.to("cpu").float().tolist()` | 17.2% | 1.2 | The final GPU sync plus conversion of 12 x 1,280 x 320 floats to Python lists. |
| pydantic response model + `dump_json` | 9.0% + 6.9% | 1.1 | Validating and serializing about 60 MB of JSON floats per batch. |
| Pooling (`bucket_pool`, pure Python loops) | 8.7% | 0.6 | Row and column pooling over 1,280 x 320 per page, in Python. |
| `processor.process_images` | 8.8% | 0.6 | Qwen image processor on the CPU. |
| PIL decode of the PNGs | 6.0% | 0.4 | |
| Request body parse (pydantic, base64) | 5.6% | 0.4 | 12 PNGs as base64 JSON. |

So roughly 3 s of GPU-bound time and 4 s of single-threaded CPU work per
batch, during which the GPU has nothing queued. `nvidia-smi` shows 79%
utilization because kernels keep draining for a while after Python moves
on, then the card idles until the next forward.

**The hot model line.** In transformers 4.57.6, `Qwen3VLVisionAttention`
takes the packed varlen path (`cu_seqlens`, one kernel per layer) only for
`attn_implementation == "flash_attention_2"`. For `sdpa` it does
`lengths.tolist()` (a GPU sync) and then runs attention once per image,
every layer. `flash_attn` is not installed in the image; the A4500 is
sm_86, which FlashAttention-2 supports.

## Design

Four changes, ordered by gain per effort. 1 and 2 are sidecar-only and
keep the wire format. 3 is a dependency. 4 changes the wire format and
touches the Java client.

### 1. Do the post-processing in torch, once, on the GPU

`embed_images` returns Python lists today. Instead:

- Keep the embeddings as a tensor. Compute row and column pooling with
  tensor ops (`view` + `mean` over buckets) on the GPU. Removes the 0.6 s
  of Python loops and shrinks the transfer.
- One `.to("cpu")` for the whole batch, then `.numpy()`. No `tolist()`.
- Build the response with `orjson` (`OPT_SERIALIZE_NUMPY`) from numpy
  arrays and return a `Response(content=..., media_type="application/json")`.
  Skip constructing `PageEmbedding` pydantic models for the output; keep
  the pydantic classes for the request and for tests.

Expected: the 2.9 s of tolist + pydantic + dump_json + pooling drops to
about 0.3 s. Batch time 7.0 s to about 4.4 s. Gain 1.6x.

### 2. Overlap CPU work with the GPU

Make `embed_pages` a plain `def` endpoint so Starlette runs it in its
thread pool, and guard only the forward with a `threading.Lock`. With
`INGEST_QUEUE_WORKERS=5` there are always several requests in flight, so
request B's PNG decode, `process_images`, and response encoding run while
request A's forward holds the GPU. The GIL limits this: the forward's own
Python overhead needs the GIL, and `orjson` and PIL release it only
partly. Expected gain 1.2x to 1.4x on top of 1, and `/healthz` answers
during batches, which removes the "ColPali sidecar is unreachable"
transient failures (about 4% of directory-ingest files today).

Set `COLPALI_MAX_BATCH_SIZE` back to 16 once the allocator cap from
`gpu-text-embedder-v1.md` is confirmed to hold at that size.

### 3. FlashAttention-2 for the vision tower

Add `flash-attn` to `Dockerfile.cuda` (prebuilt wheel for torch 2.8,
cu128, Python 3.11; a source build takes an hour and 40 cores) and set
`COLPALI_ATTN_IMPL=flash_attention_2`. The vision tower then runs one
packed varlen kernel per layer instead of 12 small ones plus a sync.
Expected: the 3.1 s forward drops to 2.0 to 2.5 s. Must be A/B measured;
if the wheel is unavailable for this stack, skip.

### 4. Binary embeddings on the wire (later)

Per batch the sidecar emits about 60 MB of JSON floats and `pdf-rag-http`
parses it with Jackson into `List<List<Double>>`. A base64 float16 array
per page is 10 MB and parses in one `ByteBuffer` pass. Deferred: it needs
the Java client and the sidecar to change together, and after 1 to 3 the
http side has idle CPU to absorb the JSON.

## Expected result

| Step | Batch time | Pages/min | DTIC remaining |
|---|---|---|---|
| Today | 7.0 s | 92 | 9 days |
| After 1 | 4.4 s | 150 | 5.5 days |
| After 1 + 2 | 3.4 s | 190 | 4.4 days |
| After 1 + 2 + 3 | 2.6 s | 250 | 3.3 days |

Estimates, not measurements. Each step is measured before the next
starts. The 640-token budget from `gpu-text-embedder-v1.md` stacks on top
and cuts the forward share again.

## Validation

- `py-spy record` again for 60 s; the forward share should rise above
  70% and the postprocess phases fall under 10%.
- Service time from the uvicorn access log: median completion interval
  over 200 requests, before and after each step.
- Exact page count growth: `POST :6333/collections/dtic_archive_pages/points/count {"exact":true}`
  over 30 minutes. Do not use `GET /kb/{name}` counters; they are
  approximate and lag under upsert load.
- Retrieval parity: the pooled vectors from the torch implementation must
  equal the Python `bucket_pool` output within float32 rounding on a
  fixed test batch. Add that as a unit test in `sidecar/tests`.
- `docker logs pdf-rag-http | grep "sidecar is unreachable"` count per
  hour should drop to zero after step 2.

## Rollout

Sidecar only for steps 1 to 3: `docker compose build colpali-server`,
then `docker compose up -d --no-deps --force-recreate colpali-server`.
The queue treats the restart window as transient (no retry penalty). The
bulk runner should be paused at a batch boundary during the swap, as in
`gpu-text-embedder-v1.md`.

## Risks

- **Pooling parity.** The Python `bucket_pool` has defined bucket
  boundaries; the tensor version must reproduce them exactly or already
  indexed pages will score differently from new ones. The unit test above
  is the gate.
- **GIL.** Step 2's gain is the least certain. If measured below 1.1x,
  keep the change anyway for the health-probe fix.
- **flash-attn wheel.** If no matching wheel exists, do not build from
  source on the production box; skip step 3.
- **Memory.** Step 2 admits concurrent requests; only one forward runs at
  a time under the lock, so peak VRAM does not change, but host RAM per
  in-flight request (decoded PNGs, numpy outputs) adds up to a few hundred
  MB per worker. The box has 97 GB free.

## Status update (2026-09-17, evening)

Not implemented yet. What changed the priorities:

- The pool (sidecar-pool-v1.md) took the visual rate from 92 to 450-600
  pages/min with the sidecar code unchanged. Per-replica efficiency (steps
  1-3) now multiplies across nine replicas.
- Step 4 (binary embeddings on the wire) is no longer "later". The
  60-78 MB JSON response per batch is what filled the ingest JVM's heap:
  each of 24 workers held the response bytes plus the boxed
  `List<List<Double>>` parse plus a document's accumulated vectors, and
  the JDK default 30 GiB heap overflowed inside the HTTP client
  ("IOException: Java heap space" on embed reads, about one job per
  minute). Mitigated with -Xmx48g and 20 workers (5b1b74f) and a client
  retry (9851501). A float16 array on the wire is 10 MB and parses in one
  pass, which removes the memory ceiling on the pool's consumer side.
- Step 2 (thread pool + lock) also fixes the health-probe problem the
  balancer had to work around (deploy/colpali-lb.conf, /healthz fallback).

Order now: 4, then 1, then 2, then 3.
