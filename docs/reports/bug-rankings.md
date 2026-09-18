# Bug Rankings

Date: 2026-08-27. Companion to `potential-bugs.md` (full detail per bug) and
`architecture-improvements.md`. Rank = impact × likelihood. Silent-wrongness
ranks above crashes: the system's value is trustworthy retrieval plus a
trustworthy confidence signal. A bug that lies is worse than a bug that fails.

## Top 15

| Rank | Bug | Why ranked here |
|------|-----|-----------------|
| 1 | B3 — visual-only hits get a phantom text signal (`ConfidenceCalculator.java:92`) | Lies on every `colpali_only` search. The agent trusts zero-text hits as "high". Silent, always-on. |
| 2 | B4 — `text_trust` never wired (`ChunkPipeline.java:275-296`) | Garbled OCR chunks never penalized; the documented confidence formula never runs. Silent, every fusion search. Tests mask it. |
| 3 | B2 — sidecar blocks its event loop (`sidecar/.../main.py:115-138`) | Triggers under normal visual load. False-unhealthy flips, restart pressure, hard-failed ingests. Recurring. |
| 4 | B18 — square-grid pooling assumption (`sidecar/.../pooling.py:41-79`) | Silently averages unrelated patches on the GPU-default model (colqwen2). Degrades the visual pipeline's whole purpose. Verify against a golden set before the fix. |
| 5 | B6 — bootstrap `.env` defeats `--gpu` (`scripts/lib.sh`, `.env.example:45-56`) | The documented happy path builds a CPU sidecar into the GPU reservation. Every visual job crawls; no warning. |
| 6 | B1 — URL body fully buffered before the size cap (`FileFetcher.java:84,95`) | Worst blast radius: one request OOM-kills the JVM and all in-flight jobs. Unauthenticated. Likelihood is lower on a LAN box — raise it if the port is exposed (see B44). |
| 7 | B9 — eager `<kb>_pages` locks KB mode when the text side fails (`QdrantBackend.java:143-170`) | Fails in the common scanned-PDF case (no text layer). Leaves an empty KB that rejects all later text-only ingests. Needs operator recovery. |
| 8 | B10 — stale visual pages overwrite fresh ones after re-scan (`IngestQueue.java:78`, `QdrantBackend.java:203-218`) | Silent wrong data that persists. Likely with the 2-worker GPU default. |
| 9 | B5 — mid-flight sidecar failure is terminal, not transient (`ColPaliClient.java:208-217`, `IngestWorker.java:163-175`) | Kills jobs the design says survive; burns the retry budget on every sidecar restart. |
| 10 | B8 — delete does not cancel the doc's queued visual job (`QdrantBackend.java:299-319`) | A deleted document resurrects itself. Confusing, data-integrity. |
| 11 | B22 — orphan suppression is per-doc, not per-page (`RrfFusion.java:115-126`) | Defeats the exact use case: the garbled page that visual promotion exists to catch. Silent recall loss. |
| 12 | B28 — Docker HEALTHCHECK probes a missing `/q/health` (`server-http/Dockerfile:37-38`) | Container reports unhealthy forever; any health-keyed orchestrator restarts a healthy server. |
| 13 | B27 — model revision unpinned by default + dependency backtracking (`sidecar/pyproject.toml:32,36`) | The known 2026-08-14 transformers-5.x crash reproduces on the next recreate; pip silently downgrades colpali-engine on every build. |
| 14 | B19 — visual ingest holds all page PNGs in memory (`PageRasterizer.java:50-72`) | OOM on large PDFs; takes down other in-flight jobs with it. Corpus-dependent. |
| 15 | B11 — failed upsert batch orphans chunks under a random docId (`ChunkPipeline.java:149-152`) | Silent duplicate copies, undeletable through `delete_document`. Rare trigger (Qdrant restart). |

## Tier 2 — operational correctness, moderate trigger rate

| Bug | One-line reason |
|-----|-----------------|
| B44 — SSRF on URL fetch | Jumps into the top 8 if the REST port is exposed (it is, by default — see improvements §5). |
| B13 — `BatchIngestExecutor` waits forever on a hung task | Wedged embedder hangs a REST request thread permanently. |
| B7 — queue submit leaves phantom QUEUED job | Disk-full leaves a job that never runs, plus partial state. |
| B12 — `markCompleted` persist failure strands job + pins snapshot | Disk-full strands IN_PROGRESS forever; hardlink pins the source file. |
| B20 — `/info` guesses `vector_dim=128` before load | Narrow race, but the KB is unusable until dropped. |
| B29 — `smoke.sh` directory check can never pass against Docker | The verification step always reports FAILED. |
| B38 — smoke greps `ready`, matches `"ready": false` | Sidecar readiness check passes while the model loads. |
| B23 — catch-all mislabels Qdrant errors "sidecar unreachable" | Sends operators to the wrong service. |
| B30 — empty-array expansion breaks on bash 3.2 | Kills four scripts on stock macOS, despite the claimed support. |
| B24 — no upper bound on `top_k` | Agent input flows unbounded into a 400k-limit ANN query. |

## Tier 3 — misconfig-only, cosmetic, or narrow races

B32 (400 for upstream failures) · B35 (degrade re-runs text pipeline) ·
B15 / B16 (batchSize=0 infinite loops — typo-only) · B14 (TOCTOU size cap) ·
B31 (Open WebUI unconditional Bearer) · B17 (embeddings paired by position) ·
B37 (pages paired by index) · B21 (`supports_pooled` ignored) ·
B25 (`rrfK` unvalidated) · B26 (oversize-split offsets claim "disjoint") ·
B33 (delete success on zero matches) · B34 (KB named `*_pages` invisible) ·
B36 (bad base64 → 500, not 400) · B39 (HOME unset breaks compose volume) ·
B40 (unpinned llama.cpp tag) · B42 (`load_env` keeps quotes) ·
B41 (`create_app` config split-brain) · B43 (snapshot path leak)

## Repair notes

- B3 + B4 share one file and one fix pass. Together they are the single
  highest-value repair.
- B9 + B10 share one root cause: no job lifecycle tied to the document. The
  per-(kb, docId) generation counter (improvements §1) clears both.
- B18 changes vector semantics. Verify before/after against a golden set; the
  fix requires re-embedding the visual index.
- B1's fix (streaming size cap) also removes the need for a separate B14 fix.
