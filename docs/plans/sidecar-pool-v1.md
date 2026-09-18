# Plan: ColPali sidecar pool across GPU hosts (v1)

Status: done (2026-09-17). Running on the R530 + big-dumb for the DTIC ingest.
Author drafted: 2026-09-17

## Context

The visual index was the long pole (92 pages/min on the R530's A4500, nine
days for the DTIC corpus). big-dumb has two CMP 170HX cards (GA100, 64 GB
each) that an idle chat LLM had been holding. Page embedding is stateless
and page vectors are keyed by doc id + page number, so replicas anywhere
are interchangeable. The ingest service takes ONE sidecar URL, so the pool
needs a balancer in front.

## Layout

```
pdf-rag-http (r530, N queue workers)
   └─ COLPALI_SIDECAR_URL=http://colpali-lb:8090
        └─ nginx least_conn (deploy/colpali-lb.conf, docker-compose.pool.yml)
             ├─ colpali-server:8090          r530 A4500   (1 replica)
             └─ 192.168.1.45:8100-8103, 8110-8113  big-dumb  (4 per card)
```

- big-dumb replicas: deploy/bigdumb-sidecar-compose.yml, copied to
  /srv_big/pdf-rag-sidecar/docker-compose.yml. Same image (exported from the
  r530 with docker save) and the same model cache (tar of the hf-cache
  volume) so vectors match; same COLPALI_* env. Profile "pool" = 8
  replicas; ~9 GB each, 44 GB per card used.
- Bring up: `docker compose -f docker-compose.yml -f docker-compose.gpu.yml
  -f docker-compose.pool.yml up -d --no-deps <service>`. Always --no-deps
  during a run (health gating, see gpu-text-embedder-v1.md outcome).
- After editing deploy/colpali-lb.conf and pulling on the host:
  `docker compose ... exec colpali-lb nginx -s reload`. The config is
  mounted as a directory for exactly this reason.

## Measurements

| Setup | Pages/min |
|---|---|
| 1 sidecar, 5 workers | 92 |
| one big-dumb replica alone, batch 8-12, real DTIC pages | 196 (0.30 s/page; A4500 under load: 0.58 s/page) |
| two replicas on one card | 318 combined |
| 5 sidecars, 16 workers | 403 |
| 9 sidecars, 16 workers | 530 |
| 9 sidecars, 24 workers | 450-620 (windows of 8-13 min) |

Exact Qdrant page counts over >= 8 min windows. `GET /kb/{name}` counters
are approximate and lag; do not use them for rates. Worker completions
also lag because a document's pages upsert only after the whole document
embeds.

Parity across GPU generations (GA102 vs GA100, bf16): the same host is
bit-reproducible; across hosts about 3% of the 1,251 token vectors per
page have cosine < 0.9, pooled vectors match to 0.999, cross-host
self-MaxSim 0.993. Accepted for a bulk corpus. Queries also route through
the balancer, so query embeddings carry the same variance.

## Things that bit, in order

1. **max_conns=1 on the pool upstream.** nginx OSS cannot queue; with all
   replicas busy it answered 502 "no live upstreams", and pdf-rag-http maps
   a non-2xx embed response to a terminal job FAILURE. Removed (5ed7ccd);
   requests now queue at the sidecars.
2. **Single-file bind mount of the nginx config.** `git pull` writes a new
   inode; `nginx -s reload` kept the old bytes through two "fixes". Now a
   directory mount + `nginx -c` (20df05a).
3. **Health probes through the balancer.** With every replica mid-batch,
   /healthz had no upstream to answer. /healthz now probes with a 1 s
   timeout and falls back to ready when the pool is busy (20df05a).
4. **Every nginx reload costs a few jobs** on the old code: old workers
   close idle keep-alive connections the JDK client still holds, the next
   POST fails, POSTs are not retried. keepalive_timeout 3600 s (8ea99c2)
   and the client-side retry (9851501) cover it. Still: do not reload
   casually during a run.
5. **The residual "I/O error" was the ingest JVM's heap** (see
   sidecar-throughput-v1.md status update), not the pool.
6. **Graceful restarts FAILED in-flight jobs** until 9851501.
7. **Scanned PDFs were rejected** ("PDFBox extracted no text") instead of
   getting their visual index; 579 of ~12k DTIC files skipped until
   18cae9d.

## Operating notes

- Chat LLM on big-dumb (`llamacpp-128g-llama-1`, no restart policy) is
  stopped for the ingest window; `docker start` brings it back once the
  replicas are stopped.
- Failed jobs are terminal. Resubmit by staging symlinks under
  /tank/documents/archive-org-dtic/ingest/r/<batch>/ and POST
  /ingest/directory (text replace + new visual job). Lists and scripts under
  /srv/pdf-corpus on the R530.
- Sizing: each queue worker needs ~1.2-2.7 GB of JVM heap on long
  documents while the wire format is JSON. 20 workers, -Xmx48g on the
  188 GB host.
