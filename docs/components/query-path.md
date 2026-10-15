# Query path and speedups

How one `search_documents` call becomes Qdrant work, what each stage
costs, which speedups are in production, and which are proposed.

State: 2026-10-15, Qdrant 1.13.4, branch `qdrant/migration-1.19` (C1
included). The measurements are from the R530. The C240 (`vector-store`)
has no measurements yet; step 0 of
[../plans/qdrant-upgrade-1.19-v1.md](../plans/qdrant-upgrade-1.19-v1.md)
takes them.

## 1. The path

```
agent ── search_documents(kb, query, top_k) ──► IngestTools
  IngestService ─► QdrantBackend ─► FusionEngine
    1. resolve retrieval_mode (auto → fusion if <kb>_pages exists)
    2. text side:   ChunkPipeline.searchChunks   (runs first)
    3. visual side: ColPaliPipeline.searchPages  (runs after the text side)
    4. fuse (RRF default) → dedup → confidence
  ◄── top_k hits
```

The text side and the visual side run one after the other, not in
parallel (`FusionEngine.java:220-232`). If the sidecar fails during
the visual side, the search returns the text result with a warning.

Fusion, dedup and confidence: [fusion-engine.md](fusion-engine.md),
[result-deduper.md](result-deduper.md).

## 2. The top_k chain

Each stage multiplies the agent's `top_k`. Values for the default
`top_k` 5:

| Stage | Formula | fusion | colpali_only |
|---|---|---|---|
| agent request | `top_k` | 5 | 5 |
| text side, chunks | `top_k × n_text_multiplier` (4) | 20 | — |
| visual side, pages (`searchPages` topK) | `top_k × n_pages_multiplier` (2) | 10 | 5 |
| each prefetch `limit` | visual topK × `prefetch-multiplier` (10) | 100 | 50 |
| rerank candidates | union of the two prefetch lists | 100–200 | 50–100 |
| rerank `limit` | visual topK | 10 | 5 |
| fusion output | `top_k × dedup.headroom` (2) | 10 | — |
| to the agent | dedup back to `top_k` | 5 | 5 |

The rerank can only return pages that a prefetch found. Thus
`prefetch-multiplier` sets the recall limit of the visual side.

## 3. The text side

1. `Embedder.embedOne` sends the query to llama-server (bge-small,
   384 dims, on GPU since 2026-09-17).
2. `QdrantClient.search` sends `POST /collections/dtic_archive/points/search`
   with `limit` 20 and no `params`, so the collection default `ef` applies.
3. The collection has 2,012,201 chunks in 8 segments, about 5 GB.

Measured: about 1 s after the ARC cap (section 6), 0.09 s with a warm
cache. C3 of the upgrade plan moves this call to `/points/query`, because
1.19 removes `/points/search` from the OpenAPI spec.

## 4. The visual side

### 4.1 Query embedding

The sidecar (`/embed_query`, through `colpali-lb`) returns one 320-dim
vector per query token. A short query gives 14–20 rows; a 50-word
question gives about 70–80. Every MaxSim cost below is linear in the
row count. The numbers in this document use 20 rows.

### 4.2 The vectors per page

| Vector | Rows × dims | Copy in RAM (scoring) | Copy on disk | Use |
|---|---|---|---|---|
| `pooled_rows` | 32 × 320 | int8, `always_ram`, 19 GB for both | f32, 41 KB per page, 78 GB for both | prefetch (HNSW) |
| `pooled_cols` | 32 × 320 | (same) | (same) | prefetch (HNSW) |
| `original` | up to 1,280+ × 320 | binary, 51 KB per page, 56–62 GB | f32, about 1.6 MB per page, 1.6 TB | rerank only, no graph (`m` 0) |

`pooled_rows` and `pooled_cols` are 32-bucket pools of the token
sequence: contiguous buckets and strided buckets. They are not image
rows and columns. The two poolings make different errors, so two
prefetches find more relevant pages than one.

### 4.3 The request

`ColPaliPipeline.searchPages` sends one `POST /points/query`
(`QdrantClient.java:495-534`):

```json
{
  "prefetch": [
    {"query": [[...]], "using": "pooled_rows", "limit": 100,
     "params": {"hnsw_ef": 128, "quantization": {"rescore": true, "oversampling": 2.0}}},
    {"query": [[...]], "using": "pooled_cols", "limit": 100,
     "params": {"hnsw_ef": 128, "quantization": {"rescore": true, "oversampling": 2.0}}}
  ],
  "query": [[...]],
  "using": "original",
  "limit": 10,
  "params": {"quantization": {"rescore": false}},
  "filter": {...},
  "with_payload": true
}
```

- `params` in a prefetch entry applies to that prefetch only.
- `params` at the top level applies to the rerank only. C1 added it.
- Qdrant applies the top-level `filter` to every prefetch too
  (1.13.4 `planned_query.rs:239-263`).
- All three stages use the same query vectors.

### 4.4 Inside Qdrant (1.13.4 source)

The collection has 18 segments. Each segment has its own HNSW graph per
pooled vector, so a query walks 36 graphs. Qdrant searches the segments
in parallel and merges the lists.

**One prefetch, one segment** (`limit` 100, `hnsw_ef` 128, oversampling 2.0):

| Step | Rule (source) | Value |
|---|---|---|
| per-segment top | `min(limit, max(poisson_estimate, hnsw_ef))` (`segments_searcher.rs:561-563`) | `min(100, 128)` = 100 |
| oversampled top | `oversampling × top` (`hnsw.rs:1061-1075`) | 200 |
| walk candidate list | `max(oversampled top, ef)` (`graph_layers.rs:249`) | 200 |
| walk scoring | int8 copy in RAM | — |
| rescore | f32 copy on disk, then sort and cut to top (`hnsw.rs:1100-1126`) | 200 read, 100 kept |

The shard merges 18 × 100 = 1,800 pages and keeps 100. If a segment
had a cap below `limit` and its lowest kept score is above the merged
cut, Qdrant searches that segment again without the cap
(`segments_searcher.rs:137-155`).

`hnsw_ef` limits the per-segment work only when `limit` is above it:

| Agent `top_k` (fusion) | Prefetch `limit` | Per-segment top | Walk list |
|---|---|---|---|
| 5 | 100 | 100 | 200 |
| 10 | 200 | 128 | 256 |
| 20 | 400 | 128 | 256 |

**The rerank.** The rerank is a search on `original` with a `has_id`
filter of the 100–200 candidates (`local_shard/query.rs:285-298`).
`original` has no graph, so each segment scores its candidates directly
with the binary copy and keeps up to 10. With `rescore: true`, each
segment then reads the f32 rows of those (up to 10) pages from disk.

### 4.5 Cost per query

Default fusion query, 20 query rows:

| Stage | Pages scored | Data touched | Where from |
|---|---|---|---|
| prefetch walks (both) | more than 2 × 18 × 200 (the walk also scores the neighbours of each expanded node) | 10 KB per page (int8) | RAM |
| prefetch rescore (both) | 2 × 18 × 200 = 7,200 | 41 KB per page, about 295 MB (derived, see section 8) | page cache, else disk |
| rerank, `rescore: false` | 100–200 | 51 KB per page, up to 10 MB | RAM |
| rerank, `rescore: true` | + up to 180 | 1.6 MB per page, up to 290 MB | page cache, else disk |

Measured on the R530 (mirrors + L2ARC, `ef` 128, oversampling 2.0):

| Query | 1 client | 8 clients |
|---|---|---|
| one prefetch (`pooled_rows`) only | p50 0.16 s | 12.7 q/s, p50 0.63 s |
| service-shaped (two prefetches + rerank) | 3.9 q/s, p50 0.25 s | 9.6 q/s, p50 0.74 s, p90 1.2 s |
| rerank alone, 100 ids | 0.02 s, with or without rescore (warm) | — |
| rerank with rescore, cold | 13 s (mirrors), about 1 s (L2ARC) | — |

The limit at 8 clients is CPU: after #5 (section 6), the I/O pressure
was 1–4 %. The segments doc labels the service-shaped row
"with rescore". That label is correct only if `concurrency.py` sent
`rescore: true` (section 8).

## 5. Settings

| Key | Env | Default | Effect |
|---|---|---|---|
| `ingest.search.n_text_multiplier` | — | 4 | chunks pulled before fusion |
| `ingest.search.n_pages_multiplier` | — | 2 | pages pulled before fusion |
| `ingest.search.dedup.headroom` | — | 2 | fusion output before dedup |
| `ingest.colpali.prefetch-multiplier` | `COLPALI_PREFETCH_MULTIPLIER` | 10 | prefetch `limit`; sets visual recall and rerank work |
| `ingest.colpali.prefetch-hnsw-ef` | `COLPALI_PREFETCH_HNSW_EF` | 128 | walk effort; 256 gives +0.003 recall@50 at half the throughput |
| `ingest.colpali.prefetch-oversampling` | `COLPALI_PREFETCH_OVERSAMPLING` | 2.0 | int8 candidates rescored from f32; at 1.0, top-1 falls to 0.94 |
| `ingest.colpali.rerank-rescore` | `COLPALI_RERANK_RESCORE` | false | f32 rescore on the rerank; see 6 and 7 |

## 6. Speedups in production

| # | Change | Date | Measured effect |
|---|---|---|---|
| 1 | ZFS ARC cap 64 → 24 GiB, so the page cache has room | 2026-09-20 | text search 18–25 s → about 1 s |
| 2 | Segment merge 1,150 → 18 (`default_segment_number` 18, `max_segment_size` 100 GB) | 2026-09-21 | pooled query about 20× faster; recall@100 0.999 → 0.97–0.98 at `ef` 100 |
| 3 | `hnsw_ef` set on the prefetch stages | 2026-09-21 | recovers the recall from #2 at no latency cost |
| 4 | Binary `original` in RAM, f32 on disk, rerank without rescore | collection design (rescore off by the 1.13.4 default) | rerank 0.02 s; top-1 identical, top-5 overlap 0.988 against rescore |
| 5 | int8 pooled copies `always_ram` + oversampling 2.0 | 2026-09-23 | 8 clients: 0.33 q/s, p50 18 s → 9.6 q/s, p50 0.74 s; top-1 1.00 |
| 6 | L2ARC on the 970 EVO | 2026-09-22 | cold f32 reads served from NVMe; **not present on the C240** |
| 7 | C1: rerank rescore pinned to `false` | 2026-10-13, not merged | keeps #4 after 1.16.1, where the default becomes `true` |

\#5 is the reason the prefetch walk does not depend on the page cache.
Before #5, the walk read the f32 pooled files and stalled on 4 KB page
faults from the L2ARC.

## 7. Proposed speedups

Scale context: the full corpus is 42.3 M pages. In today's layout that
needs about 3.7 TB of RAM and about 80 TB of Qdrant storage. The brute-force
engines below exist because the RAM need grows with the corpus.

### 7.1 Inside Qdrant

| Proposal | State | Expected effect | Cost and risk |
|---|---|---|---|
| Upgrade to 1.19.2 | in progress: C1, C2 on the branch; steps 0–6 not started | faster AVX2 code, io_uring reads, batched HNSW search; no number yet | 5 migrations on HDD; ZFS snapshot before each |
| TurboQuant on 1.19.2 | after the upgrade | the only AVX-512 VNNI path in Qdrant; the current int8 and binary scoring stay on AVX2 | config `PATCH` rebuilds all 18 segments; test on a copy collection first |
| NVMe (B650 card, about 4 TB) | after step 6 | cold f32 reads from NVMe; makes `rerank-rescore: true` affordable (13 s → about 1 s) | the defective ADATA must not be used |
| 8 segments instead of 18 | deferred (2026-09-21) | about 2× throughput at the same recall | every future merge builds 300 GB segments |

### 7.2 Outside Qdrant: a prefetch service

[../accelerators/prefetch-service-contract.md](../accelerators/prefetch-service-contract.md)
defines one HTTP contract for all engines. The service replaces the two
Qdrant prefetches:

1. `POST /v1/prefetch` returns the top 200 page ids by int8 brute-force
   MaxSim over `pooled_rows` (one pool is sufficient).
2. Qdrant reranks those ids on `original` with a `has_id` filter.
3. If the service is down or its index is older than the last ingest,
   the caller uses the Qdrant prefetch, or adds its result.

The service is not built. Candidate engines:

| Engine | Per query | Recall@50 | State |
|---|---|---|---|
| A4500, int8 | 73 ms alone, 34 ms in batches of 4 | 0.9948 (0.9988 after f32 rescore) | measured 2026-09-24 |
| CMP 170HX (big-dumb, GPU 1) | estimate about 60 ms (6.4 ms on the 100k slice × 9.35); not run on the real corpus | — | planned production engine |
| C240 CPUs, AVX-512 VNNI | estimate about 50–60 ms (53 ms memory floor at 181 GB/s, 40 ms compute) | — | kernel not run on the C240 |
| R530 CPUs, AVX2 C kernel | 1,485 ms | 0.9948 | measured |
| Xeon Phi 7120P (KNC) | estimate about 340 ms (191 GMAC per query at the measured kernel rate of 560 GMAC/s); compute-bound | — | experiment only (user decision 2026-10-07) |
| KNL nodes (Phi 7230) | no design yet; the full-corpus int8 `pooled_rows` (433 GB) fits in the DDR4 of 2 nodes | — | hardware not bought |

### 7.3 In the service code

| Proposal | State | Expected effect |
|---|---|---|
| Run the text side and the visual side in parallel | in no plan | fusion latency = the slower side, not the sum |
| Larger `prefetch-multiplier` (20) | blocked on a gold query set | more rerank candidates; 0.25 → 0.5 s alone. Overlap with a reference does not measure quality here, so the eval harness must decide |

## 8. Open items and conflicts

1. **The f32 pooled rescore volume.** The 1.13.4 source gives 18 × 200
   pages per prefetch, about 295 MB per query. The segments doc (§7.7)
   says about 4 MB. If the source reading is correct, the 78 GB of f32
   pooled files must stay in the page cache. The C240 has no L2ARC, so
   a cold page goes to the mirrors. Measure the disk bytes per query at
   step 0.
2. **The contract and C1 disagree.** The prefetch contract reranks with
   rescoring on. On the HDD mirrors that is up to 200 × 1.6 MB per query.
   Decide when the service is built, after the NVMe change.
3. **The benchmark scripts.** `concurrency.py` and `rescore_check.py`
   send their own requests. If they send no rerank `rescore`, their
   numbers change at 1.16.1 for a reason that is not the upgrade. Read
   them before step 0 (plan precondition 7).
4. **Stale documents.** `benchmarks.md` still predicts 40–80 ms for the
   Phi (the 191 GMAC correction is not in it). `colpali-pipeline.md` and
   `qdrant-client.md` show the request without `hnsw_ef`, oversampling
   and the rerank `params`.
5. **Version drift.** The per-segment rules in 4.4 are from 1.13.4.
   Check `segments_searcher.rs` and `hnsw.rs` again at 1.19.2.

## 9. Sources

- Code: `FusionEngine.java:212-245`, `ColPaliPipeline.java:243-290`,
  `ChunkPipeline.java:167-180`, `QdrantClient.java:456-534`.
- Qdrant v1.13.4: `lib/collection/src/collection_manager/segments_searcher.rs`,
  `lib/segment/src/index/hnsw_index/hnsw.rs`, `.../graph_layers.rs`,
  `lib/collection/src/operations/universal_query/planned_query.rs`,
  `lib/collection/src/shards/local_shard/query.rs`.
- Measurements: [qdrant-segments-and-hnsw.md](qdrant-segments-and-hnsw.md)
  §5, §7.4–7.8; [../accelerators/benchmarks.md](../accelerators/benchmarks.md).
- Plans: [../plans/qdrant-upgrade-1.19-v1.md](../plans/qdrant-upgrade-1.19-v1.md),
  [../plans/ingest-planner-v1.md](../plans/ingest-planner-v1.md) step 6.
