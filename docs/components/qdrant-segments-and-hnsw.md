# Qdrant segments, HNSW graphs, and where the vectors live

What a segment is, why the number of segments sets search speed, how the
graphs relate to them, and which storage tier each kind of vector sits
in. Written from the `dtic_archive` load on the R530 (2026-09-16 to 20),
where a 934k-page collection ended up in 1,150 segments and visual search
took minutes per query. The numbers in this document are from that host.

## 1. Segments

A segment is the unit Qdrant stores and searches. Each one is a
self-contained directory with its own vector files, its own HNSW graph
per named vector, its own payload store and its own id tracker. A
collection is a list of segments, and a query runs against every one of
them, then merges the per-segment results.

On disk:

```
/tank/qdrant/collections/dtic_archive_pages/
  0/                                 shard 0 (single node: one shard)
    segments/
      30b771bc-…/                    one segment (~900 pages here)
        segment.json                 vector names, distances, storage types
        id_tracker.mappings          point UUID <-> internal offset
        id_tracker.versions          per-point version (upsert conflicts)
        id_tracker.deleted           deletion bitmap
        vector_storage-original/
          vectors/chunk_0..40.mmap   f32 rows, 33.6 MB per chunk file
          offsets/chunk_0.mmap       per point: first row, row count
          quantized.data             binary-quantized copy (loaded to RAM)
          deleted/flags_a.dat
        vector_storage-pooled_rows/  same layout, 1-2 chunks
        vector_storage-pooled_cols/
        vector_index-original/       hnsw_config.json only (m = 0: no graph)
        vector_index-pooled_rows/    graph.bin + links_compressed.bin
        vector_index-pooled_cols/
        payload_storage/page_0.dat   payload pages (on_disk_payload)
        payload_index/               RocksDB: doc_id and filename indexes
      dc0bc0e0-…/                    next segment, same shape
```

### How points land in a segment

A shard has one or more *appendable* segments that accept writes. An
upsert goes to one of them. When an appendable segment passes
`indexing_threshold` (or `max_segment_size`), the optimizer freezes it
into an *indexed* segment: vectors are rewritten as mmap chunk files, the
graph is built, the quantized copy is made, and a fresh appendable
segment is opened.

A point lives in exactly one segment. An update to a point that sits in a
frozen segment writes the new version into an appendable segment and
marks the old copy deleted; the version file decides which copy is live.

### How segments relate to each other

They do not. There is no ordering, range or key partition between
segments. The same document's pages can sit in dozens of them, whichever
segment was appendable when each batch arrived. A query cannot skip any
segment, and a payload filter on `doc_id` still visits every segment's
payload index.

### What the optimizer does with them

Three kinds of rewrite, each producing a new segment that is swapped in
atomically while the old one keeps serving reads:

| Operation | Trigger | Result |
|---|---|---|
| indexing | appendable segment passes `indexing_threshold` | frozen, indexed segment |
| vacuum | deleted fraction passes `deleted_threshold` (0.2) | rewritten without the deleted points |
| merge | more segments than `default_segment_number`, candidates below `max_segment_size` | several small segments become one |

A proxy captures writes during a rewrite, so nothing goes offline. This
is also why a consolidation pass can run under live search.

### Why the DTIC collection has 1,150 segments

Graph building was suspended for the bulk load (`indexing_threshold`
raised to 100 M), and upserts streamed in for two days. Qdrant kept
freezing small segments of about 900 pages and never merged them:
`max_segment_size` was on auto, which is sized for indexing speed, so a
merge into large segments was never a legal move, and `default_segment_number`
0 (auto) gave no target to merge toward.

## 2. HNSW graphs are per segment

An HNSW graph is built per segment, never per collection.

**Construction.** When a segment is frozen, Qdrant builds one graph for
each named vector over that segment's points only. Nodes are the
segment's internal offsets; links point only inside the segment. That is
what lets the graph be immutable and stored as a flat link file. A
segment of 900 points has a graph of about 25 KB.

**Search.** A query runs the HNSW search independently in every segment:
enter at the top layer, descend greedily, expand `ef` candidates at the
bottom, return the segment's top-k. Qdrant merges the lists. Nothing
links one segment's graph to another, so no segment can be skipped and
no work is shared.

**Why segment size sets index quality.** HNSW's advantage is logarithmic:
a search over N points visits on the order of log N × ef nodes. That
pays off at N in the hundreds of thousands. Over 900 points the search
visits a large fraction of the segment, so the index does close to a
brute-force scan, 1,150 times per query. Eight segments of about 117k
pages give graphs with real layers and roughly two orders of magnitude
fewer visited nodes.

**Multivectors.** For `pooled_rows` and `pooled_cols` a node is a page's
32-row multivector and the distance is MaxSim over all rows. Every
visited node reads a 41 KB block and does 32 dot products per query row.
Fewer visited nodes means proportionally fewer blocks read; that is the
disk volume per query.

**Parameters.** `m`, `ef_construct` and `full_scan_threshold` apply to
each segment's graph. `full_scan_threshold` (10,000 KB) makes a segment
whose vector data is below it use brute force instead of its graph; the
DTIC segments are above it, so they do have graphs, just tiny ones.

**A merge does not stitch graphs.** It copies the points into a new
segment and builds a new graph from scratch. That is the expensive part
of consolidation, and also the part that makes the index useful.

### Why many tiny graphs lose to a few large ones

A search evaluates about `ef` candidates per segment however small the
segment is. With 900 points and `ef` 100 that is 11 percent of the
segment scored with full MaxSim, and the layers add nothing because
there is nowhere to descend. Over 1,152 segments a query scores about
115,000 pages and merges 1,152 result lists: close to a brute-force scan
over 11 percent of the corpus, with graph overhead on top. That is also
why its recall was 0.9994: it barely approximates.

With 60,000 points per segment the upper layers route the search to the
right neighbourhood in a few dozen hops and the `ef` candidates at the
bottom are spent there. Eighteen segments score a few thousand pages per
query. Same `ef`, about 30× fewer distance computations; the recall given
up is what `ef` buys back (section 7.4).

The other costs of many small segments are fixed per segment: opening
files, id lookups, payload index probes, and on a cold cache one set of
disk reads each. Small segments do win on rebuild cost (a delete or
update rewrites the segment it lands in) and on very selective payload
filters; neither applies to a read-mostly corpus. Parallelism is not an
argument either: Qdrant searches segments in parallel and 18 already
keeps the cores busy. The right size is the smallest at which the graphs
have real layers; the measurements put that knee below 18 here.

### What a distance costs

Cosine on every named vector, turned into dot products at write time:
vectors are L2-normalized on insert and on query. For the multivectors the
score is MaxSim: for each query row, the best dot product over the page's
rows, summed over query rows. Per candidate page:

| Vector | Page rows | Query rows | Dot products of 320 | Work per candidate |
|---|---|---|---|---|
| `pooled_rows` or `pooled_cols` | 32 | 14 to 20 | up to 640 | about 0.2 MFLOP, 41 KB read |
| `original`, f32 | up to 1,280 | 14 to 20 | up to 25,600 | about 8 MFLOP, 1.6 MB read |
| `original`, binary-quantized | up to 1,280 | 14 to 20 | same count, 1 bit per dim | popcount over 40 bytes per row, 51 KB read |

The graph walk evaluates roughly `ef` candidates per segment, so:

| Layout | Candidates per query | Arithmetic |
|---|---|---|
| 1,152 segments, `ef` 100 | about 115,000 | about 23 GFLOP |
| 18 segments, `ef` 256 | about 4,600 | about 1 GFLOP |

A core does a few GFLOP/s on this loop with AVX2, so the 18-segment query
is milliseconds of math; the measured 0.25 s is memory traffic and
per-segment overhead. At 1,152 segments the math alone was a second or
two, and the disk reads to feed it were the rest.

The rerank on `original` scores about 100 candidates: fast bit operations
against the binary copy in RAM, or 0.8 GFLOP plus 160 MB of reads when
rescoring from f32. The reads are the cost.

Only `ef` changes the arithmetic, linearly. `m` changes graph quality at
build time and memory per link, not per-query work. Segment count changes
the candidate count multiplicatively, which is why it dominated
everything else.

## 3. Where each vector lives

Three tiers, chosen per named vector in the collection config:

| Tier | Config | What it means |
|---|---|---|
| "in memory" (default) | `on_disk: false` | still mmap chunk files on disk; Qdrant expects them resident in the page cache |
| on disk | `on_disk: true` | same files, read on demand through the page cache; nothing loaded at start |
| quantized, always in RAM | `quantization_config` with `always_ram: true` | a compact copy (binary 32×, int8 4× smaller) held in anonymous memory; the f32 copy is read only to rescore final candidates |

The HNSW graph itself is a mapped file too (`hnsw_config.on_disk` false
means expected resident, not loaded).

The page cache is the read cache. Qdrant has no bounded vector cache of
its own, so the effective cache is whatever RAM the kernel can spare.

### Sizes on the DTIC collection

| Data | Size |
|---|---|
| f32 pooled vectors, real, both names | 77 GB |
| same as chunk files on disk (1,150 segments, preallocated 33.6 MB chunks) | 140 GB apparent |
| offset tables for those files | 78 GB apparent |
| binary-quantized `original`, pinned in RAM | about 60 GB |
| f32 `original` rows on disk | 1.87 TB |
| text collection (`dtic_archive`, 2 M chunks, 8 segments) | 5 GB |
| host RAM | 188 GB |

The preallocation waste comes from tiny segments: a 900-page segment
fills its `pooled_rows` chunk to 37 MB, so it gets two files (67 MB),
and its offsets file is 33.6 MB for a table of a few kilobytes.

## 4. ZFS: ARC, page cache, and L2ARC

On ZFS a memory-mapped file is cached twice: once in the ARC (ZFS's own
read cache in kernel memory) and once in the Linux page cache that mmap
uses. The ARC gives memory back only slowly under pressure. On the R530
the ARC was capped at 64 GiB with a 63 GiB *floor* in
`/etc/modprobe.d/zfs.conf`, so with Qdrant at 107 GB resident the page
cache had about 1 GB, and every query re-read its files from the
mirrors.

Rules that follow:

- Cap the ARC (`zfs_arc_max`) so the page cache has room, and check
  `zfs_arc_min` too: the cap cannot go below the floor. Both are runtime
  writable under `/sys/module/zfs/parameters/`, but the ARC only evicts
  under memory pressure, so the size does not fall until something asks
  for memory.
- A plain `cat` of a file fills the ARC, not the page cache. It does not
  warm mmap access. Faulting the pages through mmap does, but only if
  they fit alongside everything else.
- L2ARC on an NVMe device is the durable fix for a spinning pool: the
  data's only home stays the mirrors, the cache device holds the hot set,
  and losing the device loses nothing. It persists across reboots and
  Qdrant restarts. It fills only from ARC eviction, throttled by
  `l2arc_write_max`, and by default excludes prefetched (sequential)
  reads (`l2arc_noprefetch=1`). A deliberate warm-up is a sequential read
  of the hot files with `l2arc_noprefetch=0` and a raised feed rate, then
  restore `l2arc_noprefetch=1`.

## 5. What was measured

All queries: one random 320-d row against `pooled_rows`, `hnsw_ef` 16,
limit 5, unless noted. Mirrors: two ZFS mirrors of 9.1 TB spinning
disks. Random reads from them ran at about 12 MB/s.

| Condition | Time | Disk read |
|---|---|---|
| ARC at 64 GiB, page cache ~1 GB, text collection HNSW (ef 64) | 18-25 s | 180-200 MB per query, every query |
| after ARC cap, text collection: one exact scan (2.7 GB), then HNSW | 11 s, then 1.1 s | 2.7 GB, then 0 |
| exact scan on binary-quantized `original` (in RAM) | 1.9 s | 0 |
| pooled HNSW, cold | timeout at 300 s | 3.5 GB |
| pooled HNSW, six consecutive queries | 227, 173, 126, 86, 68, 52 s | 2.8 → 0.6 GB |
| pooled HNSW after mmap-faulting all 70 GB of `pooled_rows` chunk files | 119, 94, 85 s | 1.3 → 0.9 GB (offsets and other files still cold; cache full) |
| sequential read of 120 `original` chunk files, cold | 77 MB/s | files fragmented by the concurrent load |

Reading: time tracks disk bytes at the mirrors' random-read rate.
Nothing in the compute path is slow. The text collection, with 8
segments, is fast once its 3 GB working set is cached. The pages
collection cannot be made resident at 1,150 segments because the
preallocated files (218 GB apparent for the pooled side alone) exceed
RAM, and each query touches every segment.

## 6. The consolidation plan

Applied as one `PATCH` on the live collection:

```json
{
  "optimizers_config": {"default_segment_number": 8, "max_segment_size": 300000000},
  "vectors": {"pooled_rows": {"on_disk": true}, "pooled_cols": {"on_disk": true}}
}
```

`max_segment_size` is in KB (300 GB), sized for 934k pages of originals
per segment. `on_disk: true` on the pooled vectors makes Qdrant read them
on demand through the cache rather than expect them resident. FP32 is
kept; int8 scalar quantization with `always_ram` remains a later, online
`PATCH` if measured latency calls for it.

The optimizer merges a few segments per operation, so the segment count
falls gradually and search stays available. That also means the curve of
speed against segment count comes from a single pass: sample a fixed
query set as the count passes 512, 128, 32 and 8.

**Recall measurement.** Segment count does not change the vectors; what
can change is HNSW recall (a 117k-point graph is approximate, a 900-point
one is near exact). Embed 50 real queries once, compute ground truth once
with `exact: true` on `pooled_rows` (top 100), and at each checkpoint
record HNSW time and overlap with the ground truth at the production
`ef`. Expect recall in the high 0.9s at 8 segments, tunable with
`hnsw_ef` at linear time cost.

**Where the pass runs.** In place on the mirrors, the rewrite reads the
fragmented layout three times (about 1,150 → 100 → 10 → 8) at 77 MB/s
while writing to the same spindles: a day or more. The alternative taken
on 2026-09-20: `zfs send` the dataset (its own dataset, `tank/qdrant`) to
a temporary pool on the two NVMe devices at about 300 MB/s (2 h), run the
pass there (hours), `zfs send` the 8-segment result back to a new dataset
on the mirrors as one sequential write (2 h), swap datasets, return the
EVO to its L2ARC role and warm it. The old dataset stays as the fallback
until the new one is verified. Cost: Qdrant stopped for the two copies.
Benefit beyond time: the collection lands on the mirrors defragmented.

**The rerank step on spinning disks.** The service's visual search
prefetches 10 × top_k candidates from each pooled vector, then reranks on
`original`. Qdrant's default for a quantized vector rescores from the
f32 copy on disk: about 100 candidates × 1.6 MB of random reads, 13 s
cold on the mirrors, about 1 s from L2ARC. This step does not shrink with
consolidation. It can be switched off per query (`rescore: false`), at a
recall cost on MaxSim not yet measured.

### What the pass looked like in practice (2026-09-20/21, NVMe)

- On auto, the optimizer started five merges at once, each building toward
  the 300 GB cap in `<shard>/temp_segments/segment_builder_*`. A merge holds
  its whole output there until it is swapped in, so the worst case is
  `max_optimization_threads × max_segment_size`. That was 1.5 TB against
  540 GB free; 480 GB went in 35 minutes. Cancelled by re-`PATCH`ing.
- Settled at `max_segment_size` 100 GB with 4, later 5, threads: a round of
  4 merges (about 65 small segments each) lands every ~65 minutes, most of
  it graph construction. 1,152 → 386 segments in 3.3 hours. A second short
  stage with the cap at 300 GB takes the ~18 results to 8.
- Any `PATCH` to `optimizers_config` cancels the merges in flight and
  discards their temp output. Change thread count or cap right after a
  round lands, not in the middle of one.
- Free space, the segment count and per-round timing are cheap to log
  from a shell loop as a systemd unit; a brake that drops the thread count
  to 1 below a free-space floor is worth having on a pool without much
  headroom.

## 7. What was done, step by step (2026-09-20 to 21)

The sequence as executed on the R530, with the numbers observed at each
step. Section 6 has the reasoning; this is the record.

### 7.1 Diagnosis (2026-09-19 to 20)

1. After the load, `indexing_threshold` was restored to 20,000 and the
   graphs built (about 12 hours). The collection turned green, but a
   visual query still timed out at 300 s and a text query took 18 to
   25 s.
2. Measuring disk bytes per query alongside time showed both collections
   were I/O-bound: 180 to 200 MB per text query, 3.5 GB per pooled query,
   served from the mirrors at about 12 MB/s random. Repeating a query
   answered from Qdrant's query cache in 0.01 s, which ruled out compute.
3. The host had 1 GB of page cache: Qdrant at 107 GB resident, the ZFS
   ARC pinned at 64 GiB by an explicit `zfs_arc_min` in
   `/etc/modprobe.d/zfs.conf`.
4. The ARC was capped to 24 GiB at runtime (the floor had to be lowered
   first, then memory pressure applied, since the ARC only evicts on
   demand). Text search dropped to about 1 s once its 3 GB working set
   had been read once. The pages collection did not improve: its
   preallocated chunk files (218 GB apparent for the pooled side, 1,150
   segments) exceeded what could be cached, and every query touched every
   segment. An mmap warm-up of the 70 GB of `pooled_rows` files did not
   help either, because the offsets files and the rest of the per-segment
   set stayed cold.
5. Per-segment telemetry (`/telemetry?details_level=4`) confirmed all
   1,150 segments had graphs; the file listing showed each graph was 21 to
   28 KB. The cost was fan-out, not missing indexes.

### 7.2 Storage moves

6. The Samsung 970 EVO Plus 2 TB, which held a Windows system volume, was
   emptied: `Users` (162 GB) and `backup_files` (401 GB) were copied to a
   new dataset `tank/evo-backup` (rsync, dry-run diff of zero afterwards),
   then the user wiped the drive.
7. Of the two ADATA 1 TB drives, one had 7,691 media errors in a single
   region (SMART and a `badblocks` read scan agreed) and was excluded. The
   other was clean.
8. The EVO was first attached to `tank` as an L2ARC cache device. That
   remains the intended end state. It was detached again for the
   consolidation, because the pass runs far faster on NVMe than on the
   fragmented mirrors (a cold sequential read of the original chunk files
   ran at 77 MB/s).
9. Pool `nvme` was created from the EVO and the healthy ADATA (striped,
   2.74 TB, no redundancy). `tank/qdrant` was snapshotted as
   `@pre-consolidation` and sent with `zfs send -c` at about 300 MB/s
   (1.78 TB in 1 h 42 min), Qdrant and the ingest service stopped for the
   duration. `QDRANT_DATA_DIR` was pointed at `/nvme/qdrant` and Qdrant
   restarted there. Counts matched (934,834 pages, 2,012,201 chunks) after
   one partial duplicate resurrected by WAL replay was deleted again.

### 7.3 The consolidation pass

10. Baseline on NVMe, still 1,152 segments: pooled query 7 s cold, 3 s
    warm; text 0.7 s. A 50-query benchmark set was embedded once through
    the sidecar, and exact top-100 ground truth computed for both pooled
    vectors (15 s per scan on NVMe, 20 minutes in total).
11. `PATCH`: `default_segment_number` 8, `max_segment_size` 300 GB,
    pooled vectors `on_disk: true`. The optimizer started five merges at
    once toward the 300 GB cap and consumed 480 GB of temp space in 35
    minutes against 540 GB free. A second `PATCH` (1 thread, 100 GB cap)
    cancelled them and returned the space.
12. Settled at 4 threads, then 5, at the 100 GB cap. A systemd unit on the
    host logged free space and segment count every minute and would have
    dropped the thread count to 1 below 250 GB free; it never fired. A
    second unit sampled query latency every ten minutes.
13. Rounds landed as batches: 1,152 → 842 → 575 → 448 → 386 → 325 → 205 →
    86 → 27 → 18, from 21:45 to 04:36 EDT. Each round of four or five
    merges took about 65 minutes, most of it graph construction. Raising
    the thread count mid-round cancelled the merges in flight once more
    (the `PATCH` blocked for 502 s while they stopped), which is the origin
    of the rule in section 8.
14. Result: 18 segments of 79 to 99 GB, green, counts unchanged, 1.13 TB
    free on the NVMe pool.

### 7.4 Measurements at 18 segments

Real 50 queries, top 100, against exact ground truth; optimizer idle:

| `ef` | `pooled_rows` p50 / p90 | recall@100 | `pooled_cols` p50 / p90 | recall@100 |
|---|---|---|---|---|
| 32 | 0.05 / 0.07 s | 0.886 | 0.05 / 0.09 s | 0.913 |
| 100 (service default) | 0.26 / 0.40 s | 0.969 | 0.56 / 1.82 s | 0.981 |
| 256 | 0.25 / 0.33 s | 0.991 | 0.31 / 0.49 s | 0.995 |
| 512 | 0.44 / 0.51 s | 0.996 | 0.39 / 0.46 s | 0.997 |
| 1,152 segments, ef 100 (baseline) | 5.7 / 7.6 s | 0.9994 | 6.4 / 8.2 s | 0.9988 |

Consolidation bought about 20× on latency and cost about 3 percent of
recall at the default `ef`; `ef` 256 recovers it at no latency cost. The
end-to-end `colpali_only` search through the service, which had timed
out for two days, returns in about a second. An exact scan takes 6 s.

The random-vector sampler, once the optimizer went idle, read 0.1 s per
pooled query and 0.09 s per text query.

### 7.5 Concurrency (2026-09-21, NVMe, 18 segments)

Real 50 queries, `ef` 256, `pooled_rows`, top 100, N parallel clients:

| Clients | Throughput | p50 | p90 |
|---|---|---|---|
| 1 | 3.8 q/s | 0.26 s | 0.32 s |
| 4 | 7.8 q/s | 0.52 s | 0.57 s |
| 8 | 7.8 q/s | 1.02 s | 1.09 s |
| 16 | 7.7 q/s | 2.06 s | 2.15 s |

During the 16-client run Qdrant used 3,890 percent CPU (all 40 cores),
with zero NVMe reads and zero major page faults: the ceiling is compute,
5.2 core-seconds per query. Work per query is segments × `ef` × `m` (the
walk scores every neighbour of every expanded node, each a 41 KB MaxSim
block), and the levers scale accordingly: `ef` 128 gives 14.7 q/s at
recall@50 0.98, `ef` 64 gives 27.4 q/s. The service-shaped query (two
prefetches plus the `original` rerank) ran at 4.0 q/s, p50 1.8 s, with 8
clients. There is no isolation or admission control: clients share the
pool fairly and all slow together past saturation.

Because the hot path is CPU, the mirrors with an L2ARC give the same
numbers once the cache is warm; the mirrors only serve the rerank's f32
reads for candidates the cache has not seen.

### 7.6 Decisions taken

- Stay at 18 segments; `default_segment_number` set to 18 with the 100 GB
  cap so future segments merge into the same shape. Going to 8 would have
  doubled throughput at constant recall, at the cost of 300 GB segments
  for every future merge; revisit if concurrent load demands it.
- The service now sets `hnsw_ef` on its prefetch stages:
  `COLPALI_PREFETCH_HNSW_EF`, default 256, with 128 as the setting under
  heavy concurrency (commit 77ecbf3). Measured at 18 segments, top-1 is
  exact from 128 up and recall@50 is 0.98 at 128, 0.99 at 256, 0.997 at
  512; each doubling halves the misses and doubles the CPU.
- Copy back to the mirrors as a fresh dataset (`zfs send` at about
  170 MB/s), swap, EVO back to L2ARC, warm-up.

### 7.7 Open at the time of writing

- The copy back: Qdrant stopped, `zfs send` of 1.61 TB to a new dataset
  on `tank` (a sequential write, so the mirrors get a defragmented copy),
  swap, restart, EVO re-attached as L2ARC, warm-up read of the hot files.
- The `original` rerank still rescores from f32 on disk; measure its
  recall with `rescore: false` before deciding what it costs on the
  mirrors behind the cache.

## 8. Operating rules

- Two settings seal segments during a load, and they need different
  treatment. `indexing_threshold` (20 MB by default, a seal every 12
  pages of `original`) makes the optimizer build graphs continuously and
  throttles upserts; raise it for the duration of a bulk load.
  `max_segment_size` on auto (about 1.4 GB here) keeps sealing every
  ~900 pages regardless and makes merges illegal; set it explicitly to the
  cap you want to live with (100 GB here) with `default_segment_number`
  to match, so merges proceed during the load into full-size unindexed
  segments. Restoring the threshold afterwards then builds 18 graphs
  once, instead of 1,150 graphs and a merge pass.
- Set `default_segment_number` and `max_segment_size` explicitly for a
  large collection; the auto values never merge into large segments.
- Keep `max_optimization_threads × max_segment_size` below the pool's
  free space during a consolidation; merges hold their output in
  `temp_segments` until they land.
- Size RAM for the resident set: quantized copies with `always_ram`,
  plus the page cache the pooled vectors and graphs need, plus the ARC,
  plus the ingest JVM heap during loads.
- Check `zfs_arc_min` as well as `zfs_arc_max` when capping the ARC.
- Use exact counts (`POST /collections/<c>/points/count` with
  `exact: true`) and per-segment telemetry (`/telemetry?details_level=4`)
  rather than the collection info counters, which are approximate and
  count named vectors, not points.
- A query timing on its own says little; record disk bytes per query
  (`/proc/diskstats`) alongside it to tell I/O-bound from CPU-bound.
