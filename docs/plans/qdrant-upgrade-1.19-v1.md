# Plan: upgrade Qdrant from 1.13.4 to 1.19.2 on vector-store (v1)

Status: draft for review, revised 2026-10-12. No step has started.
Author drafted: 2026-10-09

## Context

Qdrant moves with `tank` from the R530 to the Cisco C240 M5
`vector-store` (192.168.1.166): 2 × Xeon Gold 6252 (Cascade Lake,
AVX-512 VNNI, no VPOPCNTDQ), 12 × 16 GB DDR4-2133 (one DIMM per channel),
Debian 13.7. The compose file pins `qdrant/qdrant:v1.13.4`;
`/tank/qdrant` holds 1.62 TB.

State on 2026-10-12, before the host was shut down: `tank` ONLINE with no
errors; the scrubs of 2026-10-09 and 2026-10-11 repaired 0 B; SMART OK on
all four disks (`7JJG2H7G` has 1 reallocated sector); no containers; all
21 collections present under `/tank/qdrant`, newest file 2026-09-28.

**Order (user decision, 2026-10-12): this upgrade comes before any NVMe
change.** All steps run on the HDD mirrors of `tank`, with no L2ARC. The
B650 switch card (about 4 TB of NVMe) moves from the R530 to vector-store
after step 6 passes. One variable at a time: the baseline and every step
are measured on the same storage.

**VNNI.** The upgrade alone does not put the current collections on a VNNI
path. In v1.19.2 the AVX-512 VNNI code is used only by TurboQuant. The
current int8 and binary quantization use the AVX2 + FMA path on this CPU
(the binary AVX-512 path needs VPOPCNTDQ, which Cascade Lake does not
have). The VNNI gain needs the TurboQuant test in "After the upgrade".

Data to keep intact:

| Collection | Points | Layout |
|---|---|---|
| `dtic_archive_pages` | 934,834 | 18 segments; `original` binary-quantized in RAM + f32 on disk (rerank only); `pooled_rows` / `pooled_cols` f32 on disk + int8 `always_ram` |
| `dtic_archive` | 2,012,201 | text chunks, bge-small 384-dim |

The target is **v1.19.2** (released 2026-10-05). The upgrade gives faster
AVX2 code, io_uring reads, batched HNSW search and prefetch in quantized
scoring with no config change. TurboQuant (from 1.18.0, AVX-512 VNNI path)
needs a config change and a rebuild of all segments, so it is out of scope
here (see "After the upgrade").

## The path is mandatory

The Qdrant upgrade guide says: "When upgrading Qdrant, upgrade to the
latest patch version of each intermediate minor version first", and "If
you run a single node cluster, you also can not skip versions to ensure
that all data migrations are properly applied." The guide says nothing
about downgrades.

| Step | Image tag | Released | Storage change at first start |
|---|---|---|---|
| 1 | `v1.14.1` | 2025-05-23 | none known |
| 2 | `v1.15.5` | 2025-09-30 | shard-key format migration (from 1.15.4, #7110) |
| 3 | `v1.16.3` | 2025-12-19 | **vectors, payload and payload indexes move from RocksDB to Gridstore** (from 1.16.1, #7551) |
| 4 | `v1.17.1` | 2026-03-27 | none known; 1.17 notes say 1.15 → 1.17 is not possible directly |
| 5 | `v1.18.3` | 2026-07-17 | RocksDB support removed (#8529): a start fails if step 3 left any RocksDB data |
| 6 | `v1.19.2` | 2026-10-05 | single-file mmap is the default for immutable segments (#9332); whether existing segments are rewritten is not known |

No source gives the migration time for collections of this size. Step 3 is
the one to expect to be long.

## Changes that affect us

| Version | Change | Effect on us | Action |
|---|---|---|---|
| 1.16.1 | `default_rescoring()` for binary multivectors (`BinaryRamMulti`) changes from false to true (verified in the source at v1.13.4 and v1.19.2) | Our rerank on `original` sent no `params`. From step 3 it would rescore about 100 candidates × 1.6 MB from f32 on the mirrors: 13 s cold, about 1 s from L2ARC (R530); p50 2.34 s, about 410 MB per query (C240, step 0) | **C1, before any search on step 3 or later** |
| 1.15.5 | `vectors_count` removed from collection info (#7244) | `QdrantClient` DTO field reads 0. `ChunkPipeline` already uses `points_count` | none; delete the field in C3 |
| 1.17.0 | gRPC vector output format changed (#7183) | `QdrantGrpcUpserter` only writes; it uses the deprecated `data` + `vectors_count` fields, which the v1.19.2 proto still accepts | test an upsert after step 6; move to `multi_dense` in C3 |
| 1.18.0 | default 60 s timeout on update requests (#8944) | large `wait=true` upserts during a busy optimizer can fail | watch the next bulk load |
| 1.19.0 | `/points/search` removed from the OpenAPI spec (#9982); the route still works in v1.19.2 | the text search (`QdrantClient.java:567`) uses it | move to `/points/query` in C3 |
| 1.19.0 | update queue 1M → 200 (#9448) | bulk loads see backpressure sooner | none now |
| 1.19.0 | `on_disk` / `always_ram` deprecated for `memory: cold \| cached \| pinned`; old fields still accepted | none now | update config docs later |
| 1.15–1.19 | default `indexing_threshold` 20,000 → 10,000 | our collection stores 20,000 explicitly | confirm in the config diff |

Java client: `io.qdrant:client` 1.13.0, used only for the gRPC upsert.

## Code and config changes (separate PRs)

- **C1 (required before step 3 serves searches).** Set
  `params.quantization.rescore` explicitly on the rerank stage of the
  multistage query (`QdrantClient.queryMultistage`, the top-level `query` on
  `original`). New key `ingest.colpali.rerank-rescore`, default `false`,
  which keeps the 1.13.4 behavior. Correct
  `docs/components/qdrant-segments-and-hnsw.md` (the paragraph "The rerank
  step on spinning disks", line 314): it says Qdrant's default
  rescores, but the 1.13.4 source shows that it does not for a binary
  multivector, which agrees with the 0.02 s rerank measured "with or
  without rescoring" in §7.
- **C2.** `docker-compose.yml`: `image: qdrant/qdrant:${QDRANT_VERSION:-v1.13.4}`,
  so each step is one `.env` line. Update the comment above it.
- **C3 (after step 6, not blocking).** Text search to `/points/query`;
  `QdrantGrpcUpserter` to `multi_dense`; `io.qdrant:client` to the 1.19
  line; remove the `vectors_count` DTO field; compare the server's 60 s
  update timeout with `ingest.qdrant.request-timeout-seconds=120`.
- **C4 (after step 6).** CLAUDE.md, the `huge-dumb` skill and the compose
  default to v1.19.2 and vector-store.

## Preconditions

1. Done: the scrubs of `tank` on 2026-10-09 and 2026-10-11 repaired 0 B
   with no errors.
2. Done (decision): no L2ARC and no NVMe during the upgrade (see "Order"
   in Context). The latency numbers are not comparable with the R530
   numbers; they are comparable with the step 0 baseline on this host.
3. No writes: `pdf-rag-http` and the bulk runner are stopped for the whole
   upgrade. Only the `qdrant` service runs.
4. Done 2026-10-15: C1 and C2 are merged (PR #2, `ed6b314`) and
   `/srv/pdf-rag` is at that commit (moved with a git bundle: the host key
   has no GitHub access). C1 sends
   `rescore: false` on the rerank, which is the 1.13.4 behavior, so the
   deploy changes no result before step 1.
5. Free space on `tank` is checked (15.3 TB free on 2026-10-12). A snapshot
   holds the old blocks of every file that a migration rewrites, up to
   the 1.62 TB of the dataset per step in the worst case.
6. `.env` on vector-store still has
   `COMPOSE_FILE=docker-compose.yml:docker-compose.gpu.yml:docker-compose.pool.yml`
   from the R530, and vector-store has no NVIDIA card. Set
   `COMPOSE_FILE=docker-compose.yml` for the whole upgrade. The full-stack
   layout for this host is a separate decision (After the upgrade, item 1).
7. The recall scripts in `/srv/pdf-corpus/ingest/recall` start on
   vector-store (Python and its imports). Not checked since the move.
   Check with `python3 recall_check.py --help` or one dry run before step 0.
   Also read what `concurrency.py` and `rescore_check.py` send on the
   `original` rerank. If they send no `rescore`, they rescore from 1.16.1
   and their numbers change for a reason that is not the upgrade. Pin
   them to the service value (C1) before step 0.
   Done 2026-10-15: the "rescore" side of `rescore_check.py` sent no
   params, so it never rescored on 1.13.4. Both sides now send it
   explicitly; `recall_check.py` and `concurrency.py` take an optional
   oversampling argument (backups `*.orig-2026-10-15`).

## Step 0: baseline on 1.13.4

One variable at a time: the host changed, so measure 1.13.4 on vector-store
before any upgrade.

```bash
cd /srv/pdf-rag
sudo docker compose up -d --no-deps qdrant          # v1.13.4
curl -s :6333/readyz
mkdir -p /srv/pdf-corpus/ingest/upgrade
for c in dtic_archive_pages dtic_archive; do
  curl -s :6333/collections/$c > /srv/pdf-corpus/ingest/upgrade/$c.v1.13.4.json
  curl -s -X POST :6333/collections/$c/points/count -H 'Content-Type: application/json' -d '{"exact":true}'
done
cd /srv/pdf-corpus/ingest/recall
python3 recall_check.py 128
python3 concurrency.py 8 60 128
python3 rescore_check.py agree
```

Record: exact counts, top-1, recall@50, p50 alone, q/s at 8 clients, and
the rescore agreement and latency. Optional: a STREAM run per socket and
for both sockets, for the VNNI estimates.

## Steps 1–6: the procedure for each step

For each tag in the path table:

1. Stop Qdrant cleanly and confirm a clean exit in the log:
   `sudo docker compose stop -t 300 qdrant`, then
   `sudo docker logs --tail 20 pdf-rag-qdrant`.
2. Snapshot: `sudo zfs snapshot tank/qdrant@pre-<tag>-<date>`.
3. Set `QDRANT_VERSION=<tag>` in `.env`, then
   `sudo docker compose pull qdrant && sudo docker compose up -d --no-deps qdrant`.
4. Follow the log until `/readyz` answers. Record the start and end time
   of any migration messages. On an error, stop and roll back (below); do
   not go on to the next tag.
5. Wait for both collections to report `green`. If the optimizer runs,
   wait for it to finish, and record `zfs list -o used tank/qdrant` and
   the snapshot's `used` before and after.
6. Check:
   - exact counts equal the baseline for both collections;
   - a diff of `GET /collections/<c>` against the baseline JSON shows no
     change in vectors, quantization, HNSW or optimizer settings (new
     fields and moved defaults are expected; record them);
   - `recall_check.py 128` gives top-1 and recall@50 equal to the
     baseline within noise;
   - from step 3 on, the rerank with explicit `rescore: false` matches the
     baseline timing.
7. Add a row to the log table below, then go to the next tag.

**Rollback.** Stop Qdrant, set the previous tag in `.env`, then
`sudo zfs rollback tank/qdrant@pre-<tag>-<date>` and start it.
`zfs rollback` goes back only to the newest snapshot; to go back further,
`-r` destroys the newer snapshots. A rollback across step 3 is possible
only through the snapshot: the RocksDB → Gridstore migration has no
documented reverse.

**Snapshots.** Keep every `pre-*` snapshot until step 6 passes and the
service has run on it for a week, then destroy the intermediate ones. The
older `tank/qdrant@int8-layout-2026-09-24` (27.4 MB used on 2026-10-09)
grows with every rewrite; decide whether to keep it before step 3.

## Log

| Step | Tag | Start | Ready | Migration time | Snapshot growth | Counts OK | Recall OK | Notes |
|---|---|---|---|---|---|---|---|---|
| 0 | v1.13.4 | 2026-10-15 03:22 | 663 s | — | — | 934,834 / 2,012,201 | recall@100 0.9608 / 0.9654; os 2.0: recall@50 0.990 / 0.991, top-1 1.00 | 8 clients: 50.4 q/s (os 1.0), 32.4 q/s (os 2.0, warm). Rerank rescore vs not: top-1 0.54, p50 2.34 / 0.32 s. Logs `step0-v1.13.4.log`, `step0b-v1.13.4.log` |
| 1 | v1.14.1 | | | | | | | |
| 2 | v1.15.5 | | | | | | | |
| 3 | v1.16.3 | | | | | | | |
| 4 | v1.17.1 | | | | | | | |
| 5 | v1.18.3 | | | | | | | |
| 6 | v1.19.2 | | | | | | | |

## After the upgrade

1. Start the full stack with the C1 build. Run one small ingest with a
   visual index to test the gRPC upsert, and one search through the MCP
   surface.
2. Run the step 0 measurements again and compare. Then measure
   `rescore: true` on the rerank with the L2ARC state recorded, and decide
   the C1 default from the numbers.
3. TurboQuant: test it on a separate collection (for example 50,000 pages
   copied from `dtic_archive_pages`), never on the production collection
   first. A `PATCH` on production rebuilds all 18 segments of 79–99 GB.

## Open questions

- Answered 2026-10-12: the NVMe change (the B650 card, with the 970 EVO
  or other drives) comes after step 6, not during the upgrade.
- How long is the step 3 migration for 934,834 multivector points and
  2,012,201 text points? It runs on the HDD mirrors, so expect it to be
  slower than on NVMe.
- Does step 6 rewrite existing immutable segments to single-file mmap, and
  how much does the snapshot grow if it does?
- Keep or destroy `tank/qdrant@int8-layout-2026-09-24` before step 3?

## Sources

- Qdrant upgrade guide: https://qdrant.tech/documentation/guides/upgrades/
- Release notes: https://github.com/qdrant/qdrant/releases (v1.14.1 to v1.19.2)
- Source read on 2026-10-09: `lib/segment/src/vector_storage/quantized/quantized_vectors.rs`
  (v1.13.4, `default_rescoring`) and `quantized_vectors/accessors.rs`
  (v1.19.2); `lib/api/src/grpc/proto/points.proto` (`message Vector`, both tags)
- Quantization guide (TurboQuant, PATCH): https://qdrant.tech/documentation/guides/quantization/
