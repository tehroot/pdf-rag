package org.hayden.backend.qdrant.fusion;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.ingest.SearchHit;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapses overlapping/adjacent chunks of the same document in a ranked
 * result list. Adjacent chunks share {@code ingest.chunk.overlap-chars} of
 * text, so when both rank highly they waste top-K slots on near-identical
 * content. The engine over-fetches (top-K × headroom) before calling
 * {@link #collapse}, so dropped duplicates are backfilled by the next-ranked
 * candidates automatically.
 *
 * <p>Two hits are duplicates iff they share a non-null {@code docId}, both
 * are chunk hits ({@code chunkIndex >= 0} — visual-only orphan hits are never
 * collapsed: a page hit and a chunk hit are complementary evidence), and
 * their text ranges actually overlap. When both hits carry
 * {@code char_start}/{@code char_end} payload offsets those are authoritative
 * (sliding-window neighbors overlap; structural chunks don't, and must not
 * collapse just for being adjacent). Only when offsets are missing (old
 * payloads) does the {@code |Δ chunk_index| <= 1} adjacency heuristic apply.
 * The earlier-ranked (better) hit wins.
 */
@ApplicationScoped
public class ResultDeduper {

    @ConfigProperty(name = "ingest.search.dedup.enabled", defaultValue = "true")
    boolean enabled;

    public boolean enabled() {
        return enabled;
    }

    /**
     * Walk {@code ranked} in order, dropping duplicates of already-accepted
     * hits, until {@code topK} hits are accepted or candidates run out.
     * When dedup is disabled, simply truncates to {@code topK}.
     */
    public List<SearchHit> collapse(List<SearchHit> ranked, int topK) {
        if (!enabled) {
            return ranked.size() <= topK ? ranked : List.copyOf(ranked.subList(0, topK));
        }
        List<SearchHit> accepted = new ArrayList<>(Math.min(topK, ranked.size()));
        for (SearchHit candidate : ranked) {
            if (accepted.size() >= topK) {
                break;
            }
            boolean duplicate = false;
            for (SearchHit kept : accepted) {
                if (isDuplicate(kept, candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                accepted.add(candidate);
            }
        }
        return accepted;
    }

    private static boolean isDuplicate(SearchHit a, SearchHit b) {
        if (a.docId() == null || b.docId() == null || !a.docId().equals(b.docId())) {
            return false;
        }
        if (a.chunkIndex() < 0 || b.chunkIndex() < 0) {
            return false;
        }
        int aStart = metaInt(a, "char_start");
        int aEnd = metaInt(a, "char_end");
        int bStart = metaInt(b, "char_start");
        int bEnd = metaInt(b, "char_end");
        if (aStart >= 0 && aEnd >= 0 && bStart >= 0 && bEnd >= 0) {
            return aStart < bEnd && bStart < aEnd;
        }
        return Math.abs(a.chunkIndex() - b.chunkIndex()) <= 1;
    }

    private static int metaInt(SearchHit h, String key) {
        Object o = h.metadata() == null ? null : h.metadata().get(key);
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}
