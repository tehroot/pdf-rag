# Potential Bugs Report

Date: 2026-08-27. Method: five parallel review subtasks (ingest/jobs, Qdrant
text pipeline, visual pipeline + sidecar, fusion/search, REST/deploy). The
HIGH-severity findings in this report were spot-checked directly against the
code on 2026-08-27. Severity reflects likelihood × blast radius, not fix size.

## Critical / High

### B1. URL ingest buffers the whole body before the size cap — OOM
- `core/src/main/java/org/hayden/ingest/FileFetcher.java:84,95` (verified)
- `BodyHandlers.ofByteArray()` materializes the entire response; `enforceSize`
  runs only afterward. `HttpRequest.timeout` bounds the response head, not the
  stream. A URL that streams a huge body (or omits Content-Length) allocates it
  all on heap before the check.
- Failure: one unauthenticated `ingest_document(url=...)` call OOM-kills the
  server and every in-flight job.
- Fix: check Content-Length before the call; stream with a BodySubscriber that
  aborts at `maxFileBytes`.

### B2. Sidecar runs inference inside the event loop — false "unhealthy"
- `sidecar/src/colpali_server/main.py:115-138` (verified: no
  `run_in_threadpool`/`to_thread` in the route)
- `/embed_pages` and `/embed_query` are `async def` but call the blocking
  inference function directly. While one batch embeds, `/healthz` cannot
  answer within the Docker healthcheck timeout (4 s, 3 retries).
- Failure: a busy-but-healthy sidecar flips unhealthy; the Java pre-flight
  `isHealthy()` (10 s timeout, `ColPaliClient.java:95`) then hard-fails new
  visual ingests (`QdrantBackend.java:130`) and queued jobs requeue-spin.
- Fix: offload inference with `fastapi.concurrency.run_in_threadpool` or a
  single-worker executor.

### B3. Visual-only hits get a phantom text signal in confidence
- `core/src/main/java/org/hayden/backend/qdrant/fusion/ConfidenceCalculator.java:92` (verified)
- `textRaw = h.textScore() != null ? h.textScore() : h.score()`. For
  page-promoted hits `textScore` is null and `h.score()` is the raw ColPali
  MAX_SIM. With `text_score_floor=1.0`, any MAX_SIM ≥ 1.0 saturates
  `text_signal` to 1.0.
- Failure: `colpali_only` returns a zero-text-evidence hit at confidence 0.9
  "high"; the agent trusts it as if text confirmed it.
- Fix: treat visual-only hits (e.g. `chunkIndex < 0`) as text signal 0; pass
  `textScore = 0.0` for synthesized hits in `RrfFusion.java:138` and
  `WeightedScoreFusion.java:118`.

### B4. `text_trust` is never wired for chunk hits — always 1.0
- `ChunkPipeline.buildPayload` (`ChunkPipeline.java:275-296`) never stores
  `text_quality`; only page points carry it (`ColPaliPipeline.java:182-183`).
  Neither fusion strategy copies `PageHit.textQuality()` onto fused hits.
  `ConfidenceCalculator.textTrustFromPayload` therefore returns 1.0
  (`ConfidenceCalculator.java:111-118`).
- Failure: an OCR-garbled chunk joined with a visual hit scores ~0.94 "high"
  where the documented formula gives 0.6 "medium". Unit tests mask this by
  injecting `text_quality` into synthetic metadata.
- Fix: propagate the joined page's `text_quality` into fused-hit metadata, or
  add it to the chunk payload at ingest.

### B5. Mid-flight sidecar failure is terminal, not transient
- `core/src/main/java/org/hayden/backend/qdrant/ColPaliClient.java:208-217` +
  `core/src/main/java/org/hayden/jobs/IngestWorker.java:163-175`
- A connection reset mid-`embed_pages` surfaces as plain `IngestException`.
  The worker requeues without penalty only for `SidecarUnavailableException`,
  which the pre-flight checks alone throw.
- Failure: a sidecar restart mid-batch burns the retry budget and marks the job
  FAILED — contrary to the documented "sidecar-down mid-drain is transient"
  design (CLAUDE.md, docs/components/ingest-queue.md).
- Fix: map `IOException`/`HttpTimeoutException` in the embed calls to
  `SidecarUnavailableException`.

### B6. Bootstrap `.env` silently defeats `--gpu` and the GPU override
- `scripts/up.sh:18`, `scripts/lib.sh` `enable_gpu` (`:=` defaults),
  `.env.example:45-56` (verified: `COLPALI_DOCKERFILE=Dockerfile.cpu`,
  `COLPALI_DEVICE=cpu` uncommented)
- `load_env` exports every uncommented `.env` line first; `: "${VAR:=...}"`
  and compose `${VAR:-default}` both lose to an already-set var.
- Failure: the documented path `bootstrap.sh && up.sh --gpu` reserves the GPU
  (docker-compose.gpu.yml:42-48) but builds `Dockerfile.cpu` and runs
  `device=cpu` — a CPU sidecar in a GPU-reserved container, no warning.
- Fix: comment the four GPU-flippable vars in `.env.example`, or force-set
  them in `enable_gpu`.

## Medium

| # | Bug | Refs | Mechanism / failure | Fix |
|---|-----|------|---------------------|-----|
| B7 | Queue submit leaves phantom QUEUED job on persist failure | `IngestQueue.java:82-84`, `QdrantBackend.java:154-167` | `jobs.put` before `persist`; persist throws (disk full) → in-memory job never runs, caller errors, text chunks + hardlink snapshot stay. | Persist first; on failure remove map entry, release snapshot. |
| B8 | Delete does not cancel the doc's queued VISUAL job | `QdrantBackend.java:299-319`; `cancelPending` wired only to KB teardown (`:334`) | Delete a doc while its visual job is QUEUED → worker later writes pages under the deleted docId; doc reappears visual-only. Same for `drop_visual_index`. | Add doc-level and KB-level cancel to the delete paths. |
| B9 | Eager `<kb>_pages` creation locks KB mode when text side fails | `QdrantBackend.java:143-170` | Pages collection is created before `ingestChunks` and before job submit. A scanned PDF that extracts no text fails with no chunks but with the pages collection → later `enable_visual_index=false` ingests are hard-rejected for an empty KB. | Create pages collection + submit job only after `ingestChunks` succeeds; on failure delete the eager collection. |
| B10 | Stale visual pages win after re-scan with a VISUAL job in flight | `IngestQueue.java:78` (no per-docId dedupe), `QdrantBackend.java:150-158, 203-218`; GPU overlay defaults 2 workers | Worker A (old job) is pre-lock fetching old bytes; re-scan queues job2; job2 writes new pages first; A then acquires the lock and overwrites with pages from old bytes. Final state: old pages + new chunks, silently. | Stamp jobs with a per-(kb,docId) generation; cancel stale jobs; skip writes from older generations. |
| B11 | Failed upsert batch orphans chunks under a random docId | `ChunkPipeline.java:149-152`, `QdrantBackend.java:121-123,251` | Upsert is atomic per request, not across the batch loop. A mid-list 502 leaves earlier batches committed; the docId is never returned (logged only on success); `delete_document` cannot target them; retry ingests a second full copy. | On batch failure run `deleteByDocId` as compensation, or carry the docId in the error surface. |
| B12 | `markCompleted` persist failure strands job IN_PROGRESS + pins snapshot | `IngestWorker.java:157-159`, `IngestQueue.java:184-192` | Disk-full: `markCompleted` throws; `markFailed` throws the same; job stays IN_PROGRESS in memory and on disk (requeued every restart); `releaseSnapshot` never runs, so the hardlink pins the source file forever. | Catch the status persist separately; release the snapshot in `finally`. |
| B13 | `BatchIngestExecutor` waits forever on a hung task | `BatchIngestExecutor.java:48-49,61` | `future.get()` without timeout; a wedged embedder call blocks the REST thread; `shutdownNow()` in `finally` cannot run. `POST /ingest/directory` / `/ingest/upload` never return. | `future.get(deadline)`; emit an error outcome for timed-out items. |
| B14 | `fromPath` size cap is a check-then-read race | `FileFetcher.java:120-122` | `Files.size` is checked, then `readAllBytes` reads whatever the file became between the calls. | Read with a bounded stream capped at `maxFileBytes`. |
| B15 | `EMBED_BATCH_SIZE=0` spins an infinite loop of empty embed requests | `Embedder.java:81-84` | `i += 0` never advances; each empty batch passes the 0==0 size check. Silent hang of every ingest and search. A typo'd env var causes this. | Validate `batchSize >= 1` in `@PostConstruct`. |
| B16 | `COLPALI_BATCH_SIZE=0` spins an infinite loop | `ColPaliClient.java:126` | Same pattern; the sibling configs (`ColPaliPipeline.java:197,228`) do have guards. | Validate in `init()`. |
| B17 | Embedder maps vectors to inputs by position, ignores `index` | `Embedder.java:189-193` | The OpenAI spec carries `index` per data item; a proxy that returns `data` reordered embeds chunk N with chunk M's vector, silently degrading retrieval. | Parse `index`; place by it; throw on gaps/duplicates. |
| B18 | Sidecar grid pooling assumes a square patch grid | `sidecar/src/colpali_server/pooling.py:41-79` | Tokens beyond `grid²+n_special` are silently dropped; the "fallback" guard at :57 is mathematically always false. Dynamic-resolution models (ColQwen2 — the GPU compose default) emit rectangular grids, so `mean_pool_rows/cols` averages unrelated patches. Prefetch quality degrades with zero errors. | Derive the grid from processor metadata (`image_grid_thw`) or fall back to bucket pooling; delete the dead guard. |
| B19 | Visual ingest holds all page PNGs in memory; no page/byte cap | `PageRasterizer.java:50-72`, `ColPaliPipeline.java:127-151` | `renderAll` keeps every page image (plus base64 and point JSON downstream). A few-thousand-page PDF OOMs the JVM and kills all in-flight jobs. | Render→embed→upsert in page windows; add a max-pages guard. |
| B20 | `/info` before model load guesses `vector_dim=128`; collection gets the wrong dim | `sidecar/.../main.py:104-112`, `ColPaliPipeline.java:273-280` | If the sidecar restarts between the health check and `getInfo`, a 320-dim (tomoro) deployment gets a 128-dim `<kb>_pages`; every page upsert then fails until the KB is dropped. | 503 `/info` until ready, or refuse `device: not-yet-loaded` in `ensureCollectionFor`. |
| B21 | Java always requests pooled vectors and ignores `supports_pooled` | `ColPaliPipeline.java:167-195,284-287`, `ColPaliClient.java:273` (parsed, never used) | `COLPALI_ENABLE_POOLED=false` → empty pooled arrays → Qdrant rejects the upserts. | Honor `supports_pooled` or reject the combination at startup. |
| B22 | Orphan suppression is per-doc, not per-page | `RrfFusion.java:115-126`, `WeightedScoreFusion.java:95-106` | A ranked page is dropped when its docId appears in any chunk hit, even if the page overlaps no chunk's `[pageStart, pageEnd]`. A garbled diagram page in a 100-page manual (the exact use case) is silently suppressed. | Suppress only when the page falls inside a same-doc chunk's page range. |
| B23 | FusionEngine catch-all mislabels Qdrant errors "sidecar unreachable" | `FusionEngine.java:182-185,228-231`; `QdrantClient.java:556-559` | `searchPages()` throws plain `IngestException` for Qdrant HTTP 4xx/5xx (e.g. dim mismatch after a `COLPALI_MODEL` swap); the degrade warning names the wrong service. | Throw `SidecarUnavailableException` only for sidecar I/O; catch that alone. |
| B24 | No upper bound on `top_k` | `FusionEngine.java:121` | `top_k=100000` → `nText = 400k`-limit ANN query. Agent input flows unbounded. | Clamp topK (e.g. 100). |
| B25 | `rrfK` unvalidated | `RrfFusion.java:64-66` | `ingest.fusion.rrf.k=-1` → rank-1 division by zero → `Infinity` scores sort first. | Reject `k <= 0` at init. |
| B26 | Oversize structural blocks split with overlap while offsets claim "disjoint" | `StructuralChunker.java:121-125`; contract at `:31-33` | `emitOversize` delegates to the sliding chunker (shared `overlapChars`) but `emit` assigns disjoint offsets. ResultDeduper treats those offsets as authoritative → duplicated 200-char passages survive in results. | Split oversize blocks with overlap 0, or mark the shared region in offsets. |
| B27 | GPU-pinning fix is incomplete: defaults still reproduce the crash | `sidecar/pyproject.toml:32,36`, `.env.example:71` | `COLPALI_MODEL_REVISION` defaults to empty (track `main`) and the known-good hash stays commented — the 2026-08-14 transformers-5.x crash returns on the next recreate. Also `transformers>=4.57.2,<5.0` conflicts with `colpali-engine>=0.3.5` (≥0.3.18 requires transformers ≥5.3); pip silently backtracks on every lockfile-less build. | Fail startup when the tomoro handle has no revision; uncomment the pin; add a lockfile or cap `colpali-engine<0.3.18`. |
| B28 | Docker HEALTHCHECK probes a non-existent endpoint | `server-http/Dockerfile:37-38`; no `quarkus-smallrye-health` in `server-http/pom.xml` | `/q/health` → 404 → container reports `unhealthy` forever; monitors keyed on health alarm or restartloop a healthy server. | Add smallrye-health or probe `/q/openapi`. |
| B29 | `smoke.sh` directory check can never pass against Docker | `scripts/smoke.sh:36-45` | A host `mktemp -d` path does not exist inside the container → 400. `pipeline.sh:51` inherits the always-FAILED step. | POST under the mounted `/docs` or `/host`. |
| B30 | Empty-array expansion under `set -u` breaks on bash 3.2 | `scripts/up.sh:34`, `restart.sh:18`, `build-images.sh:32`, `down.sh:27` (verified on /bin/bash 3.2.57) | `"${arr[@]}"` on an empty array → `unbound variable`, exit 127 — though `lib.sh:5-6` claims 3.2 support. | Use `${arr[@]+"${arr[@]}"}` or drop the claim. |
| B31 | Open WebUI client sends `Bearer ` with a space-key default | `OpenWebUiClient.java:112`, `docker-compose.yml:206` (`:- ` literal space) | `backend=openwebui` against an unauthenticated Open WebUI → every call 401s with a confusing header. | Send the header only when the key is non-blank (mirror `QdrantClient.java:548`). |
| B32 | REST maps upstream Qdrant failures to 400 | `IngestExceptionMapper.java:22`; `QdrantClient.java:91,112,224` | `GET /kb` while Qdrant is down returns 400 with embedded upstream 5xx text — wrong class for clients/monitoring. | Map upstream-unavailable subtypes to 502/503. |

## Low

| # | Bug | Ref | Note |
|---|-----|-----|------|
| B33 | `deleteByDocId` reports success on zero matches | `QdrantClient.java:124-140` | Hide typos'd/already-deleted docIds from the agent. Parse `result.deleted`. |
| B34 | KB named `*_pages` invisible in listings | `ChunkPipeline.java:250-269` | Skip only when the base name is an existing KB. Same loop is N+1 GETs. |
| B35 | Degrade path re-runs the whole text pipeline | `FusionEngine.java:184,231` | Re-embeds the query exactly when the system is degraded. Reuse `chunkHits`. |
| B36 | Sidecar returns 500 (not 400) on bad base64 | `inference.py:85`; doc claims 400 (`colpali-sidecar.md:270`) | Add a ValueError handler. |
| B37 | Embeddings paired to pages by list index, `page_id` parsed but unverified | `ColPaliPipeline.java:167-195` | Pair by `page_id`. |
| B38 | `smoke.sh:24` `grep -q ready` matches `"ready": false` | sidecar "ready" check passes during model load; `pipeline.sh:46` uses the correct pattern. | Match `'"ready":true'`. |
| B39 | `${INGEST_HOST_ROOT:-${HOME}}:/host` fails when HOME is unset | `docker-compose.yml:243` | cron/systemd context → invalid volume spec. |
| B40 | Unpinned `llama.cpp:server` tag | `docker-compose.yml:62` | `compose pull` swaps server behavior under a running corpus. |
| B41 | `create_app(cfg)` binds routes to `cfg` but lifespan loads from global `settings()` | `main.py:55-77` | Custom cfg yields a split-brain app. |
| B42 | `load_env` does not strip surrounding quotes | `scripts/lib.sh:59-66` | Scripts and compose disagree on quoted values. |
| B43 | `get_ingest_status` leaks the internal snapshot path | `IngestTools.java:137-143` | Return a view that omits `request.sourceValue`. |
| B44 | No SSRF restriction on URL fetch | `FileFetcher.java:64-104` | Unauthenticated surface + loopback/metadata reachability = internal port scanner. |

## Notes

- B3 + B4 are the highest-impact correctness bugs for agent behavior: the
  confidence signal is currently wrong in both directions (inflates visual-
  only hits; never penalizes garbled text).
- B9 and B10 share one root cause: the visual job has no lifecycle tied to the
  document. A per-(kb, docId) generation counter fixes both cleanly.
- B18 changes vector semantics. Verify against a golden set before/after; a
  fix requires re-embedding the visual index.
