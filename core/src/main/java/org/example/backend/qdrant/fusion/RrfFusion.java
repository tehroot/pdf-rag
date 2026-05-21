package org.example.backend.qdrant.fusion;

import jakarta.enterprise.context.ApplicationScoped;
import org.example.backend.qdrant.PageHit;
import org.example.ingest.SearchHit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion. The standard rank-based fusion for hybrid retrieval.
 *
 * <p>For each candidate chunk we compute:
 * <pre>
 *   rrf_score(chunk) = 1 / (k + text_rank(chunk))
 *                    + 1 / (k + page_rank(chunk))    if any page in [pageStart, pageEnd] hit
 *                    + 0                              otherwise
 * </pre>
 *
 * <p>Where {@code k=60} is the standard RRF constant and ranks are
 * 1-indexed (first hit has rank 1). Items present in only one pipeline still
 * get scored — they just lose the bonus from the missing pipeline.
 *
 * <p>The matching pageHit's raw ColPali score is propagated onto the
 * resulting {@link SearchHit#pageScore()} so downstream confidence
 * calculation has access to both signals.
 *
 * <p>Sort by RRF score descending, return top-K.
 */
@ApplicationScoped
public class RrfFusion implements FusionStrategy {

    public static final String NAME = "rrf";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SearchHit> fuse(List<SearchHit> chunkHits,
                                 List<PageHit> pageHits,
                                 FusionConfig config) {
        int k = config.rrfK();

        // Index page hits by (docId → list of (rank, pageHit)) for fast lookup.
        Map<String, List<RankedPage>> pagesByDoc = new HashMap<>();
        for (int rank = 0; rank < pageHits.size(); rank++) {
            PageHit ph = pageHits.get(rank);
            if (ph.docId() == null) continue;
            pagesByDoc.computeIfAbsent(ph.docId(), d -> new ArrayList<>())
                    .add(new RankedPage(rank + 1, ph));
        }

        // Score each chunk by RRF, annotate with the best matching page.
        List<Scored> scored = new ArrayList<>(chunkHits.size());
        for (int i = 0; i < chunkHits.size(); i++) {
            SearchHit chunk = chunkHits.get(i);
            int textRank = i + 1;

            RankedPage bestPage = bestMatchingPage(chunk, pagesByDoc.get(chunk.docId()));
            double score = 1.0 / (k + textRank);
            if (bestPage != null) {
                score += 1.0 / (k + bestPage.rank);
            }

            SearchHit annotated = chunk.withFusion(
                    chunk.textScore() != null ? chunk.textScore() : chunk.score(),
                    bestPage == null ? null : bestPage.page.score(),
                    null   // confidence filled later by ConfidenceCalculator
            ).withScore(score);
            scored.add(new Scored(score, annotated));
        }

        // Also surface pages that DID rank but had no overlapping chunk —
        // these only happen when the visual pipeline saw a relevant page
        // whose text was too garbled for the text pipeline to catch. We
        // synthesize a chunk-shaped hit with no text, just page context.
        addOrphanPages(pageHits, chunkHits, k, scored);

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int limit = Math.min(config.topK(), scored.size());
        List<SearchHit> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            out.add(scored.get(i).hit);
        }
        return out;
    }

    /**
     * Find the best (lowest-rank) pageHit whose pageNumber falls within
     * {@code [chunk.pageStart(), chunk.pageEnd()]} (inclusive) and shares
     * the same docId. Returns null if no match.
     */
    private static RankedPage bestMatchingPage(SearchHit chunk, List<RankedPage> docPages) {
        if (docPages == null || docPages.isEmpty()) return null;
        RankedPage best = null;
        for (RankedPage rp : docPages) {
            int pn = rp.page.pageNumber();
            if (pn < chunk.pageStart() || pn > chunk.pageEnd()) continue;
            if (best == null || rp.rank < best.rank) {
                best = rp;
            }
        }
        return best;
    }

    /**
     * If the visual pipeline ranked pages whose docId has NO chunks in the
     * text top-N at all, surface those pages as text-less hits. Otherwise
     * they're invisible to the agent even though ColPali ranked them high.
     */
    private static void addOrphanPages(List<PageHit> pageHits,
                                        List<SearchHit> chunkHits,
                                        int k,
                                        List<Scored> scored) {
        java.util.Set<String> chunkDocIds = new java.util.HashSet<>();
        for (SearchHit ch : chunkHits) {
            if (ch.docId() != null) chunkDocIds.add(ch.docId());
        }
        for (int rank = 0; rank < pageHits.size(); rank++) {
            PageHit ph = pageHits.get(rank);
            if (ph.docId() == null) continue;
            if (chunkDocIds.contains(ph.docId())) continue;   // already represented
            // Build a SearchHit that's visual-only; text-side fields are absent.
            double score = 1.0 / (k + (rank + 1));
            SearchHit orphan = new SearchHit(
                    score,
                    null,            // text — not available
                    ph.source(),
                    ph.filename(),
                    ph.docId(),
                    -1,              // chunkIndex — no chunk
                    ph.pageNumber(),
                    ph.pageNumber(),
                    null,            // textScore
                    ph.score(),
                    null,
                    ph.payload());
            scored.add(new Scored(score, orphan));
        }
    }

    private record RankedPage(int rank, PageHit page) {
    }

    private record Scored(double score, SearchHit hit) {
    }
}
