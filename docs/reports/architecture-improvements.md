# Architecture Improvements Report

Date: 2026-08-27. Companion to `potential-bugs.md` (same review method: five
parallel subsystem subtasks). Improvements only; bugs are tracked separately.
Priority: P1 = do next sprint, P2 = planned work, P3 = opportunistic.

## 1. Ingest path

| P | Improvement | Ref / today's behavior |
|---|-------------|------------------------|
| P1 | Stream-and-abort URL download | `FileFetcher.java:84` buffers the full body; see bug B1. A capped streaming BodySubscriber fixes the bug and bounds memory for all sources. |
| P2 | Per-document generation counter | Jobs carry `(kb, docId, generation)`. Cancel stale queued jobs at submit; `doVisualIngest` skips writes from older generations. Fixes bugs B9/B10 and makes re-scan semantics explicit. |
| P2 | Prune terminal jobs | `IngestQueue.java:60` keeps every job forever (memory + `<jobId>.json`). Prune terminal jobs older than N days; cap `GET /ingest/jobs` output. `refuseReplaceOfLiveJobSource` (`UploadIngestService.java:186-201`) scans all jobs per replace-upload and degrades linearly. |
| P3 | Scope the stray sweep to the request's KB subdirectory | `UploadedDocumentStore.java:652-678` walks the whole corpus after every upload request. |
| P3 | Add `ingest.directory.max_files` | Upload caps parts (`ingest.upload.max_files`); the directory scan has no equivalent cap (`DirectoryIngestService.java:195-206`). |

## 2. Search / fusion

| P | Improvement | Ref / today's behavior |
|---|-------------|------------------------|
| P1 | Wire `text_trust` end-to-end | Store `text_quality` in the chunk payload or copy `PageHit.textQuality()` onto fused hits. Fixes bug B4; makes the documented confidence formula real. |
| P1 | Score visual-only hits at text signal 0 | Bug B3. One-line discriminator plus synthesized-hit `textScore = 0.0` in both strategies. |
| P2 | Page-level orphan suppression | `RrfFusion.java:115-126` suppresses by doc membership; change to page-range overlap (bug B22). |
| P2 | Reuse pre-fetched text hits on the degrade path | `FusionEngine.java:184,231` re-embeds and re-queries exactly when the system is degraded. |
| P2 | Normalize agreement for single-pipeline modes | `text_only` can never exceed 0.5 raw → never "high" (`ConfidenceCalculator.java:96-100`). Either document the ceiling or renormalize. |
| P3 | Env-gate the eval debug logging | `RetrievalEvalRunner.java:168` hardcodes `debugCandidates=true`. |

## 3. Qdrant / sidecar clients

| P | Improvement | Ref / today's behavior |
|---|-------------|------------------------|
| P1 | Bounded retry with backoff in both client wrappers | `QdrantClient.java:554-582`, `Embedder.java:159-175` fail on any non-2xx/IOException. Every verb in use is idempotent and safe to resend. A 1-second Qdrant restart currently fails whole files mid-scan. 3 attempts, exponential 1–4 s, on 429/5xx/IOException only. |
| P1 | Map mid-flight sidecar I/O failures to `SidecarUnavailableException` | `ColPaliClient.java:208-217` (bug B5). One mapping restores the documented transient-requeue design. |
| P2 | Fail fast on embedding-dim mismatch before embedding | `ChunkPipeline.java:119-131` embeds the whole document, then fails at `ensureCollection`. Probe `getCollection(kb)` first. Saves minutes of GPU per file after an `EMBED_MODEL` switch. |
| P2 | Clamp client batch to sidecar `max_batch_size` | `ColPaliClient.java:121-142` reads only its own config; docs claim the clamp exists (`colpali-client.md:59`). `min(client, info.max_batch_size)` from cached `/info`. |
| P2 | Windowed render→embed→upsert for visual ingest | `PageRasterizer.renderAll` holds every page PNG in memory (bug B19). Page windows bound memory and let the fix remove the need for a page-count cap. |
| P3 | Cache `SidecarInfo` | `ColPaliPipeline.safeModelName` (`:394-403`) adds a `/info` round trip per ingest; the record's own javadoc says callers should cache — none do. |
| P3 | Use the short timeout for `/info` | `ColPaliClient.java:82` uses the 300 s embed timeout for a trivial GET. |
| P3 | Reuse ensureCollection's `CollectionInfo` in `ensurePayloadIndexes` | `QdrantClient.java:202,232` — every ingest pays two collection GETs today. |

## 4. Sidecar

| P | Improvement | Ref / today's behavior |
|---|-------------|------------------------|
| P1 | Offload inference off the event loop | `main.py:115-138` blocks `/healthz` during batches (bug B2). `run_in_threadpool` or a single-worker executor; also makes graceful shutdown cleaner. |
| P2 | Rectangular-grid pooling from processor metadata | `pooling.py:41-79` square assumption (bug B18). Use `image_grid_thw`; delete the dead fallback guard. |
| P3 | 503 `/info` until the model is loaded | Prevents the wrong-dim collection race (bug B20). |
| P3 | Honor `COLPALI_ENABLE_ORIGINAL` / `supports_pooled` | Config flags exist but are unread or unused (`config.py:52`, `ColPaliClient.java:273`). |
| P3 | Bind lifespan model load to `create_app(cfg)` | `main.py:55-77` split-brain between passed config and global settings (B41). |

## 5. REST / transport

| P | Improvement | Ref / today's behavior |
|---|-------------|------------------------|
| P1 | Bind the published port to loopback by default; gate Swagger | Compose publishes `0.0.0.0:${PDF_RAG_PORT}` with `MCP_CORS_ORIGINS=*` and Swagger always-on. The whole surface — including `DELETE /kb/{name}?confirm=true` — is unauthenticated. Loopback-by-default costs nothing and is the single biggest posture win. |
| P1 | Add smallrye-health + micrometer | Also fixes the always-unhealthy Docker probe (bug B28). Per-endpoint timers and queue-depth gauges turn `status.sh` into a real health report. |
| P2 | Token-auth request filter | A shared-secret header filter for the REST + MCP surface; the store has no delete protection today. |
| P2 | SSRF host allow/deny on URL fetch | `FileFetcher.java:64-104` (bug B44). Deny private ranges by default. |
| P2 | Timeout in `BatchIngestExecutor` | `future.get(deadline)` with per-item error outcomes (bug B13). |
| P3 | Distinct exception subtypes for upstream failures | Map to 502/503 instead of 400 (bug B32); let `FusionEngine` label degrade warnings correctly (bug B23). |
| P3 | Startup validation of enum-valued config | Fail fast on unknown `INGEST_BACKEND`/`FUSION_STRATEGY`/`RETRIEVAL_MODE` instead of first request. |

## 6. Deployment / scripts

| P | Improvement | Ref / today's behavior |
|---|-------------|------------------------|
| P1 | Make `.env` and GPU mode agree, or remove the duplicate config | Bug B6. Simplest: the GPU-flippable vars stay commented in `.env.example`; `enable_gpu` force-exports. |
| P1 | Pin the model revision or fail fast | Bug B27. The known-good revision must stop being an operator-memory comment. |
| P2 | Compose hardening | No `mem_limit`/`pids`/`read_only`/`cap_drop`/`no-new-privileges` anywhere; the sidecar loads multi-GB checkpoints unbounded. Add `stop_grace_period` on `pdf-rag-http` so in-flight uploads finish. |
| P2 | Decouple text-only availability from the sidecar | `pdf-rag-http` waits on `colpali-server service_healthy` (start_period 15 min). Use compose profiles, or `service_started` when `INGEST_DEFAULT_VISUAL_INDEX=false`. |
| P3 | Add `.dockerignore` | `build.context: .` ships `target/`, `.venv`, `.git`, `incoming/`, `models/` into both build contexts. |
| P3 | Pin `llama.cpp:server` tag | `docker-compose.yml:62` (bug B40). |
| P3 | Fix bash-3.2 empty-array handling | Bug B30; four scripts. |

## 7. Testing

| P | Gap | Note |
|---|-----|------|
| P1 | Fusion degrade branches + strategy dispatch | `FusionEngineTest` covers only `resolveMode`. A stubbed `ColPaliPipeline` throwing `IngestException` covers both warning paths and hit reuse. |
| P1 | Confidence regression tests for visual-only and garbled-text hits | Current tests mask bugs B3/B4 by injecting `text_quality` into synthetic metadata. |
| P2 | `ChunkPipelineTest` (none exists) | Structural→sliding fallback (`ChunkPipeline.java:101-106`), batch-failure compensation, malformed payload defaults. |
| P2 | `StructuredExtractorTest` expansion | 3 tests vs a 440-line heuristic class; heading-stack arithmetic, table grouping, XHTML flattening are pure functions — cheap to cover. |
| P2 | Client edge tests | `batchSize=0` guards (Embedder, ColPaliClient), out-of-order `data`/`index` handling, `deleteByDocId` zero-match. |
| P3 | Sidecar rectangular-grid pooling test | Locks the B18 fix once implemented. |

## 8. Documentation drift (fix in one pass)

- `docs/components/qdrant-backend.md` describes the pre-split ingest design:
  no explicitDocId, no split-queue routing, no eager `<kb>_pages`, no
  delete-before-write, no per-doc-id lock. This is the canonical doc for the
  invariant CLAUDE.md flags "don't simplify away" — highest drift risk.
- `docs/components/qdrant-client.md`: `MultiVectorConfig` signature omits
  `onDisk`; line/test counts and test names stale; "no extra GET" claim false
  (two GETs per ingest).
- `docs/components/dispatcher.md`: shows a four-method `Backend` (actual:
  eight); wrong `listKnowledgeBases` merge condition (code merges only on
  explicit `"all"`).
- `docs/components/ingest-queue.md`: failure table says sidecar-down mid-ingest
  → FAILED (code requeues transiently); worker snippet stale (missing backoff,
  `SidecarUnavailableException` catch, snapshot release); Public API omits
  `listJobs`/`requeueTransient`/`cancelPending`.
- `docs/components/fusion-engine.md`: claims `text_trust` applies to chunk hits
  (never wired — bug B4); claims the degrade catch is `ColPaliClient`-only
  (catches any `IngestException`); orphan definition says "page not touched"
  (code checks doc membership).
- `docs/components/file-fetcher.md`: claims the cap keeps memory bounded
  (false for URLs — bug B1); omits `ingest.fetch.user-agent`.
- `docs/deployment.md`: claims mvnvm + `./mvnw` (no config, no wrapper in the
  repo); `qdrant:latest` examples contradict the pinned `v1.13.4`.
- `docs/mcp-integration.md`: "seven tools" vs eight `@Tool` methods (same doc
  contradicts itself).
- `docs/components/colpali-sidecar.md`: bad-base64 → 400 claim (actual: 500);
  "falls back rather than throwing" (dead code; silently truncates);
  `COLPALI_ENABLE_ORIGINAL` (never read); test count 32 (actual: 34, and the
  doc's own breakdown sums to 26).
- CLAUDE.md: `/info` contract says `batch_size` (actual field:
  `max_batch_size`); sidecar test count stale.
- Tool-surface text: `get_ingest_status` error mentions job "pruning" that does
  not exist (`IngestTools.java:140-142`).

## Summary

Highest-value sequence:

1. Fix B1–B6 (the HIGH bugs) — each is small and removes a production
   failure mode or a wrong-confidence path.
2. Client retry/backoff + correct failure classification (sections 3, 5) —
   restores the resilience the docs already promise.
3. Health/metrics + loopback default + auth story (section 5).
4. Job lifecycle: generation counter + pruning (section 1).
5. One documentation-drift pass (section 8) — the qdrant-backend.md drift
   actively invites removal of load-bearing invariants.
