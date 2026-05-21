package org.example.backend.qdrant.fusion;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.backend.qdrant.PageHit;
import org.example.ingest.SearchHit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compute per-hit and response-level confidence labels.
 *
 * <p>Per-hit formula (heuristic; defaults match the plan):
 * <pre>
 *   text_signal   = min(1, text_score / text_score_floor) * text_trust
 *   visual_signal = min(1, visual_score / visual_score_floor)
 *   agreement     = 1.0 if hit's docId appears in both pipelines' top-N, else 0.5
 *
 *   raw = WEIGHT_TEXT * text_signal
 *       + WEIGHT_VISUAL * visual_signal
 *       + WEIGHT_AGREEMENT * agreement
 *
 *   bucket: > THRESHOLD_HIGH    → "high"
 *           > THRESHOLD_MEDIUM  → "medium"
 *           otherwise           → "low"
 * </pre>
 *
 * <p>{@code text_trust} comes from the source page's {@code text_quality}
 * (0|1|2) divided by 2 — so chunks from a clean text layer get full text
 * weight; chunks from OCR'd scans get zero text weight (visual dominates).
 *
 * <p>Response-level confidence is the maximum per-hit confidence in the
 * returned top-K, on the philosophy that if at least one hit is high-confidence
 * the response is usable.
 */
@ApplicationScoped
public class ConfidenceCalculator {

    @ConfigProperty(name = "ingest.confidence.weight_text", defaultValue = "0.4")
    double weightText;

    @ConfigProperty(name = "ingest.confidence.weight_visual", defaultValue = "0.4")
    double weightVisual;

    @ConfigProperty(name = "ingest.confidence.weight_agreement", defaultValue = "0.2")
    double weightAgreement;

    @ConfigProperty(name = "ingest.confidence.threshold_high", defaultValue = "0.7")
    double thresholdHigh;

    @ConfigProperty(name = "ingest.confidence.threshold_medium", defaultValue = "0.4")
    double thresholdMedium;

    @ConfigProperty(name = "ingest.confidence.text_score_floor", defaultValue = "1.0")
    double textScoreFloor;

    @ConfigProperty(name = "ingest.confidence.visual_score_floor", defaultValue = "50.0")
    double visualScoreFloor;

    /**
     * Annotate each hit with a {@code confidence} label and return the new list
     * along with the response-level (max-of-hits) confidence.
     */
    public Result annotate(List<SearchHit> hits,
                            List<SearchHit> textHitsRaw,
                            List<PageHit> pageHitsRaw) {
        Set<String> textDocIds = collectDocIds(textHitsRaw, true);
        Set<String> pageDocIds = collectDocIdsFromPages(pageHitsRaw);

        List<SearchHit> annotated = new ArrayList<>(hits.size());
        double maxScore = -1;
        for (SearchHit h : hits) {
            double rawScore = score(h, textDocIds, pageDocIds);
            String bucket = bucket(rawScore);
            annotated.add(h.withFusion(h.textScore(), h.pageScore(), bucket));
            if (rawScore > maxScore) maxScore = rawScore;
        }
        String responseConfidence = annotated.isEmpty() ? null : bucket(maxScore);
        return new Result(annotated, responseConfidence);
    }

    /**
     * Visible-for-testing: compute the raw confidence score for a single hit
     * given the pre-fusion doc-id sets from each pipeline's top-N.
     */
    public double score(SearchHit h, Set<String> textDocIds, Set<String> pageDocIds) {
        double textTrust = textTrustFromPayload(h.metadata());
        double textRaw = h.textScore() != null ? h.textScore() : h.score();
        double visualRaw = h.pageScore() != null ? h.pageScore() : 0.0;

        double textSig = normalize(textRaw, textScoreFloor) * textTrust;
        double visualSig = h.pageScore() == null ? 0.0 : normalize(visualRaw, visualScoreFloor);

        boolean inText = h.docId() != null && textDocIds.contains(h.docId());
        boolean inVisual = h.docId() != null && pageDocIds.contains(h.docId());
        double agreement = (inText && inVisual) ? 1.0 : 0.5;

        return weightText * textSig + weightVisual * visualSig + weightAgreement * agreement;
    }

    public String bucket(double raw) {
        if (raw > thresholdHigh) return "high";
        if (raw > thresholdMedium) return "medium";
        return "low";
    }

    private static double textTrustFromPayload(Map<String, Object> payload) {
        if (payload == null) return 1.0;   // assume clean text when we have no signal
        Object q = payload.get("text_quality");
        if (q instanceof Number n) {
            return Math.max(0.0, Math.min(1.0, n.intValue() / 2.0));
        }
        return 1.0;
    }

    private static double normalize(double raw, double floor) {
        if (floor <= 0) return 0.0;
        return Math.min(1.0, Math.max(0.0, raw) / floor);
    }

    private static Set<String> collectDocIds(List<SearchHit> hits, boolean fromChunks) {
        Set<String> out = new HashSet<>();
        if (hits == null) return out;
        for (SearchHit h : hits) {
            if (h.docId() != null) out.add(h.docId());
        }
        return out;
    }

    private static Set<String> collectDocIdsFromPages(List<PageHit> pages) {
        Set<String> out = new HashSet<>();
        if (pages == null) return out;
        for (PageHit p : pages) {
            if (p.docId() != null) out.add(p.docId());
        }
        return out;
    }

    /** Result bundle: annotated hits + response-level confidence label. */
    public record Result(List<SearchHit> hits, String responseConfidence) {
    }
}
