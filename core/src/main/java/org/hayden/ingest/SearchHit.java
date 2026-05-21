package org.hayden.ingest;

import java.util.Map;

/**
 * One search result. Carries enough context for an agent to act on the hit
 * directly (text, source, filename) plus the provenance and scoring
 * breakdown needed when fusion is in play.
 *
 * <p>Fields beyond the original chunk-level scoring are non-null only when
 * fusion ran:
 * <ul>
 *   <li>{@code textScore} — the raw text-pipeline cosine score for this hit.
 *       Null if the hit came purely from the visual pipeline.
 *   <li>{@code pageScore} — the raw visual-pipeline ColPali MAX_SIM score for
 *       this hit's source page (when fusion matched it via doc/page join).
 *       Null when no matching page hit existed.
 *   <li>{@code confidence} — per-hit confidence bucket
 *       ({@code "high" | "medium" | "low"}). Null when fusion didn't compute it.
 * </ul>
 */
public record SearchHit(
        double score,
        String text,
        String source,
        String filename,
        String docId,
        int chunkIndex,
        int pageStart,
        int pageEnd,
        Double textScore,
        Double pageScore,
        String confidence,
        Map<String, Object> metadata) {

    /** Back-compat: pre-fusion hits with no page tags / fusion metadata. */
    public SearchHit(double score, String text, String source, String filename,
                     String docId, int chunkIndex, Map<String, Object> metadata) {
        this(score, text, source, filename, docId, chunkIndex,
                1, 1, null, null, null, metadata);
    }

    /** Convenience: rebuild a hit with a different score (used by fusion). */
    public SearchHit withScore(double newScore) {
        return new SearchHit(newScore, text, source, filename, docId, chunkIndex,
                pageStart, pageEnd, textScore, pageScore, confidence, metadata);
    }

    /** Convenience: rebuild a hit with fusion annotations. */
    public SearchHit withFusion(Double textScore, Double pageScore, String confidence) {
        return new SearchHit(score, text, source, filename, docId, chunkIndex,
                pageStart, pageEnd, textScore, pageScore, confidence, metadata);
    }

    /** Convenience: rebuild a hit with page tags read from chunk payload. */
    public SearchHit withPageRange(int pageStart, int pageEnd) {
        return new SearchHit(score, text, source, filename, docId, chunkIndex,
                pageStart, pageEnd, textScore, pageScore, confidence, metadata);
    }
}
