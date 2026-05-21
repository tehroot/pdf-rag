# Fusion engine

`core/.../backend/qdrant/fusion/` — five classes that combine text-side and
visual-side retrieval into a single ranked result with confidence labels:

| Class | Role |
|-------|------|
| `FusionStrategy` (interface) | Plug-in point for fusion algorithms. |
| `RrfFusion` | Default: rank-based Reciprocal Rank Fusion. |
| `WeightedScoreFusion` | Alternative: linear combination of normalized scores. |
| `FusionConfig` (record) | Operator-tunable knobs (topK, RRF k, weights, score floors). |
| `ConfidenceCalculator` | Heuristic confidence labels (high/medium/low) per hit + response. |
| `FusionEngine` | Orchestrator: resolves `retrieval_mode`, picks strategy, attaches confidence. |

## What the fusion engine does

The text pipeline (`ChunkPipeline`) and visual pipeline (`ColPaliPipeline`)
each produce a ranked list independently. `FusionEngine` combines them into a
single ranked list of `SearchHit`s, with per-hit `pageScore` populated from the
matching page (if any) and per-hit + response-level `confidence` labels
attached.

It also handles the `retrieval_mode` resolution and fallback logic so the
agent sees a consistent surface regardless of whether the KB has a visual
index or whether the sidecar is up.

## The fallback matrix

`FusionEngine.resolveMode(requested, visualAvailable, warnings)`:

| `retrieval_mode` | KB has visual index? | Resolved mode | Warning? |
|------------------|----------------------|----------------|----------|
| `auto` (default) | yes | `fusion` | no |
| `auto` (default) | no | `text_only` | no |
| `fusion` | yes | `fusion` | no |
| `fusion` | no | `text_only_fallback` | yes |
| `text_only` | (either) | `text_only` | no |
| `colpali_only` | yes | `colpali_only` | no |
| `colpali_only` | no | (throws `IngestException`) | — |
| anything else | — | (throws `IngestException`) | — |

Sidecar-down handling differs by stage:

- **At query time** (here): a `IngestException` from `ColPaliClient` triggers
  soft-degrade to `text_only_fallback` with a warning. The agent gets useful
  results even if the sidecar bounced.
- **At ingest time** (`QdrantBackend.ingest`): hard-fail. The user explicitly
  asked for visual indexing; silently ingesting text-only would create
  partial-coverage documents in a "visual-enabled" KB.

## The strategy interface

```java
public interface FusionStrategy {
    String name();   // "rrf" | "weighted"

    List<SearchHit> fuse(List<SearchHit> chunkHits,
                         List<PageHit> pageHits,
                         FusionConfig config);
}
```

Implementations are `@ApplicationScoped` CDI beans. `FusionEngine` looks them
up by name (`ingest.fusion.strategy` env or `fusion_strategy` per-call arg).
Adding a third strategy is a new file plus a unique `name()`.

## `RrfFusion` — the default

For each candidate chunk:

```
rrf_score(chunk) = 1 / (k + text_rank(chunk))
                 + 1 / (k + page_rank(chunk))     if any page in [pageStart, pageEnd] hit
                 + 0                              otherwise
```

`k = 60` (the standard RRF constant) by default; tunable via
`ingest.fusion.rrf.k`. Ranks are 1-indexed.

### The chunk-to-page join

A chunk has `(docId, pageStart, pageEnd)`. A page hit has `(docId,
pageNumber)`. They match iff:

- `chunk.docId == pageHit.docId`, AND
- `chunk.pageStart <= pageHit.pageNumber <= chunk.pageEnd`

When multiple pages in the range hit, the lowest-rank (best) one wins, and
its raw `score` is propagated onto the chunk's `pageScore` field.

### Orphan pages

If the visual pipeline ranked a page from a document the text pipeline didn't
touch (i.e., no chunk shares the docId), that page is surfaced as a
**text-less** `SearchHit`:

- `text = null`
- `chunkIndex = -1`
- `pageStart = pageEnd = pageNumber`
- `pageScore = ph.score`
- `metadata = ph.payload`

This is the "OCR failed completely, but ColPali found the right page" case.
Without orphan promotion, the agent would never see those pages.

### Notable behavior

- **Items in only one pipeline still score** — they just lose the bonus from
  the missing pipeline. RRF on one pipeline = `1/(k + rank)` which is
  monotonic but smaller than the two-pipeline case.
- **`k` controls the slope.** Small `k` (e.g. 1) makes rank differences
  dominant; large `k` (e.g. 1000) flattens contributions across the list.
  Standard `60` works well in published IR research and matches Qdrant's docs.
- **No score normalization needed.** RRF only uses ranks, so the text-side
  cosine [0,1] and visual-side MAX_SIM [0, ~100] live on different scales
  without breaking anything.

## `WeightedScoreFusion` — the tunable alternative

For each candidate chunk:

```
text_signal   = min(1.0, text_score / textScoreFloor)
visual_signal = min(1.0, visual_score / visualScoreFloor)    (0 if no page match)

fused_score   = textWeight * text_signal + visualWeight * visual_signal
```

`textScoreFloor` defaults to 1.0 (cosine is already in [0,1]).
`visualScoreFloor` defaults to 50.0 (empirical typical max for ColPali
MAX_SIM; calibrate per deployment).

### When to use this over RRF

- You have measurement showing a fixed text/visual weight balance is right
  for your corpus.
- You want to tune the balance from config without changing rankings via the
  pipeline-count knobs.
- You're A/B-testing fusion strategies — `WeightedScoreFusion` is more
  amenable to grid-search than RRF (you can sweep weights in [0, 1]).

The trade-off vs RRF: this strategy is sensitive to `visualScoreFloor`
calibration. Set it too low → visual signal saturates at 1.0 always; set it
too high → visual signal never contributes meaningfully.

## `ConfidenceCalculator`

After fusion picks the top-K, this annotates each hit with a confidence
bucket and computes a response-level confidence.

### Per-hit score

```
text_signal   = min(1, text_score / text_score_floor) * text_trust
visual_signal = min(1, visual_score / visual_score_floor)   (0 if no page match)
agreement     = 1.0 if hit's docId in both top-N's else 0.5

raw = WEIGHT_TEXT * text_signal
    + WEIGHT_VISUAL * visual_signal
    + WEIGHT_AGREEMENT * agreement
```

Default weights `0.4 / 0.4 / 0.2`. Buckets:
- `> 0.70` → `"high"`
- `> 0.40` → `"medium"`
- otherwise → `"low"`

### `text_trust` from `text_quality`

The chunk's source page has `text_quality: 0|1|2` in its Qdrant payload (set
by `TextLayerProbe` at ingest). `text_trust = text_quality / 2.0`:

- 2 (full text layer) → 1.0 (full credit)
- 1 (partial / OCR mix) → 0.5
- 0 (no text layer; OCR-only) → 0.0 (text signal collapses to zero)

So a high cosine match in an OCR-garbled chunk doesn't dominate confidence;
the visual side and agreement do.

### Response-level confidence

`max` of per-hit confidence in the returned top-K. Philosophy: if at least
one hit is high-confidence, the response is actionable; the agent can pick
the best hit and trust it.

## `FusionEngine` orchestration

```java
public SearchResponse search(SearchRequest req, String kbBackend) {
    String requested = req.retrievalMode() ?? defaultMode;
    int topK = req.topK();
    boolean visualAvailable = pages.isEnabledFor(req.kbName());

    String mode = resolveMode(requested, visualAvailable, warnings);

    switch (mode) {
        case TEXT_ONLY, FALLBACK     → run chunks.searchChunks, confidence, return
        case COLPALI_ONLY            → run pages.searchPages, promote to SearchHits, confidence, return
        case FUSION:
            int nText = topK * nTextMultiplier;     // default 4×topK = 20 chunks
            int nPages = topK * nPagesMultiplier;   // default 2×topK = 10 pages
            run both pipelines with deeper limits
            strategy.fuse(chunks, pages, config)
            confidence.annotate(fused, chunks, pages)
            return SearchResponse(fusion_mode, response_confidence, warnings, hits)
    }
}
```

The deeper pull from each pipeline (4× and 2× the final topK) is for RRF
recall: items that rank #(topK+1) in one pipeline but high in the other still
get a chance to be fused.

## Configuration

| Env / key | Default | Purpose |
|-----------|---------|---------|
| `RETRIEVAL_MODE` / `ingest.retrieval.default_mode` | `auto` | Per-call default. |
| `FUSION_STRATEGY` / `ingest.fusion.strategy` | `rrf` | `rrf` or `weighted`. |
| `ingest.fusion.rrf.k` | `60` | RRF constant. |
| `ingest.fusion.weighted.text` | `0.5` | Weighted text weight. |
| `ingest.fusion.weighted.visual` | `0.5` | Weighted visual weight. |
| `ingest.fusion.weighted.text_score_floor` | `1.0` | Cosine is already normalized. |
| `ingest.fusion.weighted.visual_score_floor` | `50.0` | Empirical ColPali MAX_SIM cap. |
| `ingest.search.n_text_multiplier` | `4` | Chunks pulled pre-fusion = N × topK. |
| `ingest.search.n_pages_multiplier` | `2` | Pages pulled pre-fusion = N × topK. |
| `ingest.confidence.weight_text` | `0.4` | |
| `ingest.confidence.weight_visual` | `0.4` | |
| `ingest.confidence.weight_agreement` | `0.2` | |
| `ingest.confidence.threshold_high` | `0.7` | |
| `ingest.confidence.threshold_medium` | `0.4` | |
| `ingest.confidence.text_score_floor` | `1.0` | |
| `ingest.confidence.visual_score_floor` | `50.0` | |

Per-call overrides on `search_documents`:
- `retrieval_mode` (string)
- `fusion_strategy` (string)

## Failure modes

| Case | Result |
|------|--------|
| `retrieval_mode=colpali_only`, no visual index | `IngestException` — explicit incompatibility. |
| `retrieval_mode=fusion`, no visual index | Fall back to `text_only_fallback` with warning. |
| Sidecar unreachable at query time | Fall back to `text_only_fallback` with warning. |
| Unknown `retrieval_mode` value | `IngestException`. |
| Unknown `fusion_strategy` value | `IngestException`. |

## Why it's like this

- **Pluggable strategies.** Production retrieval research is active; RRF is
  the proven default but weighted-score and learned-reranker variants exist.
  The `FusionStrategy` interface lets us swap or A/B without rewiring.
- **Heuristic confidence over a learned model.** A learned confidence model
  would need training data we don't have for v1. The weighted heuristic is
  easy to reason about and the weights are config-tunable.
- **Soft-degrade on query, hard-fail on ingest.** Queries should be best-effort
  — a busted sidecar shouldn't blackout retrieval. Ingest is committing data
  that downstream queries assume exists; we don't want to half-commit.
- **Orphan-page promotion in RRF.** Without it, the OCR-failed-completely
  case is invisible to the agent. With it, the agent sees "we found a relevant
  page but the text is unreadable — call `inspect_page` to see it."
- **Why `confidence` matters.** The default failure mode of RAG is the model
  confidently quoting a low-relevance hit. Surfacing `confidence: "low"` on
  the result lets the agent decide whether to trust the snippet, hedge in its
  answer, or escalate to `inspect_page`.

## Tests

- `RrfFusionTest` (9): rank ordering, page-range join, agreement bonus,
  orphan-page surfacing, topK limit, k constant sensitivity.
- `WeightedScoreFusionTest` (8): normalization, weight asymmetry, clamping,
  orphan-page surfacing.
- `ConfidenceCalculatorTest` (8): bucket thresholds, text-quality weighting,
  agreement penalties, response-level (max-of-hits).
- `FusionEngineTest` (9): every cell of the fallback matrix, case-insensitive
  matching, unknown-mode rejection.
- `QdrantBackendTest` exercises the full search path end-to-end through
  `FusionEngine` (it's the routing default).

All plain JUnit 5 + WireMock + reflection-set fields. No live Qdrant or
sidecar needed.
