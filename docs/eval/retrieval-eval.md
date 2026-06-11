# Retrieval evaluation protocol

A fixed yardstick for measuring retrieval accuracy before/after changes to
chunking, fusion, or search parameters. Without this, "it seems better" is
the only signal — and chunking changes are exactly the kind that look better
on one query and quietly regress five others.

## The gold set

Two fixed inputs, versioned in this repo under `eval/`:

1. **Document set** — 5–10 representative PDFs from the production corpus.
   Pick documents that cover the failure modes you care about: dense text,
   tables, multi-column layouts, scanned pages. The files themselves stay
   out of git (put them in `eval/docs/`, which is gitignored); list their
   filenames + provenance in `eval/docs.md` so the set is reproducible.
2. **Query set** — 20–40 queries in `eval/queries.json`, each with the
   document and page that contains the correct answer:

```json
[
  {"query": "What is the maximum operating temperature?",
   "gold_filename": "thermal-spec.pdf", "gold_page": 12},
  {"query": "fan curve configuration",
   "gold_filename": "cooling-guide.pdf", "gold_page": 7}
]
```

Gold targets are `(filename, page)` — **not** chunk ids — because page
numbers are stable across chunking changes; chunk boundaries are not.

## Metrics

Scored per query from the top-K (K=5) search hits, page-level:

- A hit is **correct** when `gold_filename` matches the hit's filename and
  `gold_page` falls within the hit's `[page_start, page_end]`.
- **hit@1** — fraction of queries whose top hit is correct.
- **hit@5** — fraction of queries with any correct hit in the top 5.
- **MRR** — mean of `1/rank` of the first correct hit (0 when absent).

## Procedure (side-by-side KBs)

Re-ingesting a file always creates new points under a fresh `doc_id` — old
chunks remain and keep matching queries. So **never compare strategies inside
one KB**. Instead:

1. Ingest the document set into one throwaway KB per configuration, e.g.
   `eval_sliding` (INGEST_CHUNK_STRATEGY=sliding) and `eval_structural`
   (INGEST_CHUNK_STRATEGY=structural), same files, same order.
2. Run the query set against each KB with identical search settings.
3. Compare hit@1 / hit@5 / MRR. Inspect losses query-by-query using the
   candidate logs (below) before concluding anything — a metric drop with
   sensible candidates usually means a bad gold label, not a regression.

When done, drop the eval KBs (Qdrant `DELETE /collections/eval_*` and
`eval_*_pages` if visual was enabled).

## Candidate logs

Set `INGEST_SEARCH_DEBUG_CANDIDATES=true` and every search logs, at INFO:

```
text[0] score=0.8123 doc=… chunk=14 pages=12-12 "Operating range: -10..70 °C …"
page[0] score=18.42 doc=… page=12 tq=2 file=thermal-spec.pdf
fused[0] score=0.03279 text=0.8123 page=18.4200 conf=high doc=… chunk=14 pages=12-12
search kb=eval_structural mode=fusion strategy=rrf topK=5 … textMs=12 visualMs=85 fuseMs=1
```

These are the raw material for diagnosing *why* a query missed: wrong chunk
retrieved (chunking problem), right chunk ranked low (embedding/fusion
problem), or right page found only by the visual side (text-extraction
problem).

## Automated runner

`core/src/test/java/org/hayden/eval/RetrievalEvalRunner.java` runs the whole
query set against a live stack and prints the metrics table. It is excluded
from normal test runs and activates only when `EVAL_KB` is set:

```bash
# Stack up (qdrant + embedder + sidecar if the KB has a visual index), then:
cd core
EVAL_KB=eval_structural \
EVAL_QUERIES=../eval/queries.json \
QDRANT_URL=http://localhost:6333 \
EMBED_BASE_URL=http://localhost:8081/v1 \
EMBED_MODEL=bge-small-en-v1.5 \
COLPALI_SIDECAR_URL=http://localhost:8090 \
mvn test -Dtest=RetrievalEvalRunner
```

Optional env: `EVAL_TOP_K` (default 5), `EVAL_RETRIEVAL_MODE`
(default `auto`).
