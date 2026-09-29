# MaxSim benchmarks across the fleet

All figures are for the first-pass page search: a query multivector (20
rows of 320) against 32-row pooled page vectors, MaxSim reduced, over
the `dtic_archive` corpus (934,834 pages) or a 100,000-page synthetic
slice scaled ×9.35. Dates and scripts are given so a number can be
re-run.

## Real corpus, real queries, exact ground truth (2026-09-24)

50 queries embedded once; exact f32 top-100 per pooled vector as truth.

| Engine, kernel | top-1 | recall@50 | recall@100 | per query | notes |
|---|---|---|---|---|---|
| Qdrant HNSW on the R530 CPUs, int8 pooled, ef 128, oversampling 2.0 | 1.00 | 0.990 | 0.987 | 0.10 s alone; 9.6 service q/s at 8 clients | production path today |
| A4500, int8 brute force, per-page scale (`gpu_prefetch_bench_v2.py`) | 0.98 | 0.9948 | 0.992 | 73 ms single, 34 ms per query in batches of 4 | 9.6 GB VRAM |
| same, top-200 then f32 rescore | 1.00 | 0.9988 | 0.999 | + rescore | production shape |
| A4500, naive kernel (per-row scale, per-chunk transpose, float intermediates) | 1.00 | 0.9952 | 0.9952 | 1,100 ms | what to avoid |
| R530 Xeons, C kernel `phi_maxsim.c` host build, 40 threads (2026-09-29) | 0.98 | 0.9948 | 0.992 | 1,485 ms | identical recall to the GPU per-page int8; the Phi's baseline to beat |

## Synthetic slice, 100k pages (2026-09-24), `cpu_gpu_bench.py`

| Processor | f32 | bf16 | int8 | memory bandwidth |
|---|---|---|---|---|
| R530, 2× Xeon E5-2650 v3, 40 threads, AVX2 | 0.176 s | 0.341 s (emulated) | 0.478 s (no VNNI) | 37 GB/s |
| big-dumb, Ryzen 9 9950X, 32 threads, AVX-512 + VNNI + BF16 | 0.747 s (anomalous) | 0.116 s | 0.033 s (oneDNN proxy) | 50 GB/s |
| A4500 (R530, external dock, gen 1 ×4) | 9.8 ms | 5.7 ms (fp16) | 8.0 ms | 640 GB/s |
| CMP 170HX (big-dumb) | 7.3 ms | 3.1 ms (fp16) | 6.4 ms | HBM2e ~1.5 TB/s |

Reading: the pass is bound by reading the matrix. DRAM at 37-50 GB/s
versus HBM at 1.5 TB/s is the gap; AVX-512 raises the CPU's arithmetic
3-15× but not its bandwidth. The 170HX is the production home for the
index; the Ryzen would be the better CPU for Qdrant's graph walk if
Qdrant were ever re-homed (16 cores, 60 GB RAM rule that out today).

## Xeon Phi 7120P (pending)

Prediction to test: 16 GB GDDR5 at 352 GB/s theoretical, 170-200 GB/s
sustained, so a sweep of the 9.6 GB matrix in 50-60 ms per batch; VPU
arithmetic about 6 ms per query. Expected 40-80 ms per query alone,
50-100 q/s batched. Kernel and harness: `/srv/pdf-corpus/phi/` on the
R530 (see [xeon-phi-bringup.md](xeon-phi-bringup.md)).

## Rerank depth (2026-09-24)

The final top-5 depends on how many candidates Qdrant reranks on
`original` more than on how they were found: 50 + 50 candidates agree
with a 100 + 100 reference on top-1 for 86 % of queries and 0.85 of the
top-5; 100 + 100 agree 98 % / 0.98; 200 + 200 disagree again because the
deeper set holds pages the reference never saw. Agreement with a
reference is therefore not a quality measure at that stage; a gold query
set with relevance judgments is (`docs/eval/retrieval-eval.md`, never yet
run). `COLPALI_PREFETCH_MULTIPLIER` stays at 10 until then.
