package org.hayden.backend.qdrant.fusion;

import jakarta.enterprise.context.ApplicationScoped;
import org.hayden.backend.qdrant.PageHit;
import org.hayden.ingest.SearchHit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Linear-combination fusion of normalized scores.
 *
 * <pre>
 *   text_signal   = min(1.0, text_score / text_score_floor)
 *   visual_signal = min(1.0, visual_score / visual_score_floor)   (0 if no page match)
 *   fused_score   = textWeight * text_signal + visualWeight * visual_signal
 * </pre>
 *
 * <p>The score floors are empirical normalizers — cosine is already in
 * {@code [0, 1]} so the text floor defaults to 1.0; ColPali MAX_SIM is
 * unbounded above and the visual floor is set by observed-typical-max for
 * the deployment's chosen model.
 *
 * <p>This strategy is more tunable than RRF but sensitive to score-floor
 * calibration. Use RRF as the default and switch only when measurement
 * shows fixed-weight behavior is preferable for a specific corpus.
 */
@ApplicationScoped
public class WeightedScoreFusion implements FusionStrategy {

    public static final String NAME = "weighted";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SearchHit> fuse(List<SearchHit> chunkHits,
                                 List<PageHit> pageHits,
                                 FusionConfig config) {
        Map<String, List<PageHit>> pagesByDoc = new HashMap<>();
        for (PageHit ph : pageHits) {
            if (ph.docId() == null) continue;
            pagesByDoc.computeIfAbsent(ph.docId(), d -> new ArrayList<>()).add(ph);
        }

        double textFloor = positiveFloor(config.textScoreFloor(), 1.0);
        double visualFloor = positiveFloor(config.visualScoreFloor(), 50.0);

        List<Scored> scored = new ArrayList<>(chunkHits.size());
        for (SearchHit chunk : chunkHits) {
            PageHit bestPage = bestMatchingPage(chunk, pagesByDoc.get(chunk.docId()));

            double textRaw = chunk.textScore() != null ? chunk.textScore() : chunk.score();
            double textSig = Math.min(1.0, Math.max(0.0, textRaw) / textFloor);
            double visualSig = bestPage == null
                    ? 0.0
                    : Math.min(1.0, Math.max(0.0, bestPage.score()) / visualFloor);

            double fused = config.textWeight() * textSig + config.visualWeight() * visualSig;

            SearchHit annotated = chunk
                    .withFusion(textRaw, bestPage == null ? null : bestPage.score(), null)
                    .withScore(fused);
            scored.add(new Scored(fused, annotated));
        }

        addOrphanPages(pageHits, chunkHits, visualFloor, config.visualWeight(), scored);

        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int limit = Math.min(config.topK(), scored.size());
        List<SearchHit> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            out.add(scored.get(i).hit);
        }
        return out;
    }

    private static PageHit bestMatchingPage(SearchHit chunk, List<PageHit> docPages) {
        if (docPages == null || docPages.isEmpty()) return null;
        PageHit best = null;
        for (PageHit ph : docPages) {
            int pn = ph.pageNumber();
            if (pn < chunk.pageStart() || pn > chunk.pageEnd()) continue;
            if (best == null || ph.score() > best.score()) {
                best = ph;
            }
        }
        return best;
    }

    private static void addOrphanPages(List<PageHit> pageHits,
                                        List<SearchHit> chunkHits,
                                        double visualFloor,
                                        double visualWeight,
                                        List<Scored> scored) {
        java.util.Set<String> chunkDocIds = new java.util.HashSet<>();
        for (SearchHit ch : chunkHits) {
            if (ch.docId() != null) chunkDocIds.add(ch.docId());
        }
        for (PageHit ph : pageHits) {
            if (ph.docId() == null) continue;
            if (chunkDocIds.contains(ph.docId())) continue;
            double visualSig = Math.min(1.0, Math.max(0.0, ph.score()) / visualFloor);
            double fused = visualWeight * visualSig;
            SearchHit orphan = new SearchHit(
                    fused,
                    null,
                    ph.source(),
                    ph.filename(),
                    ph.docId(),
                    -1,
                    ph.pageNumber(),
                    ph.pageNumber(),
                    null,
                    ph.score(),
                    null,
                    ph.payload());
            scored.add(new Scored(fused, orphan));
        }
    }

    private static double positiveFloor(double v, double fallback) {
        return v > 0 ? v : fallback;
    }

    private record Scored(double score, SearchHit hit) {
    }
}
