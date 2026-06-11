package org.hayden;

import org.hayden.backend.qdrant.fusion.ResultDeduper;
import org.hayden.ingest.SearchHit;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultDeduperTest {

    @Test
    void adjacentChunks_collapseKeepingBetterRank() throws Exception {
        ResultDeduper d = newDeduper(true);
        List<SearchHit> ranked = List.of(
                hit("d-1", 4, 0.9),
                hit("d-1", 5, 0.8),   // adjacent to chunk 4 → dropped
                hit("d-2", 0, 0.7));

        List<SearchHit> out = d.collapse(ranked, 5);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).chunkIndex()).isEqualTo(4);
        assertThat(out.get(1).docId()).isEqualTo("d-2");
    }

    @Test
    void charRangeOverlap_collapsesEvenWhenIndexesNotAdjacent() throws Exception {
        ResultDeduper d = newDeduper(true);
        // chunk_index distance 2 but char ranges overlap (defensive case).
        SearchHit a = hit("d-1", 2, 0.9, Map.of("char_start", 100, "char_end", 300));
        SearchHit b = hit("d-1", 4, 0.8, Map.of("char_start", 250, "char_end", 500));

        List<SearchHit> out = d.collapse(List.of(a, b), 5);

        assertThat(out).containsExactly(a);
    }

    @Test
    void adjacentIndexes_withNonOverlappingRanges_doNotCollapse() throws Exception {
        ResultDeduper d = newDeduper(true);
        // Structural chunks: adjacent indexes but disjoint char ranges —
        // offsets are authoritative, so these are NOT duplicates.
        SearchHit a = hit("d-1", 0, 0.9, Map.of("char_start", 0, "char_end", 500));
        SearchHit b = hit("d-1", 1, 0.8, Map.of("char_start", 501, "char_end", 900));

        assertThat(d.collapse(List.of(a, b), 5)).hasSize(2);
    }

    @Test
    void differentDocs_neverCollapse() throws Exception {
        ResultDeduper d = newDeduper(true);
        List<SearchHit> ranked = List.of(hit("d-1", 0, 0.9), hit("d-2", 0, 0.8));

        assertThat(d.collapse(ranked, 5)).hasSize(2);
    }

    @Test
    void visualOrphanHits_neverCollapse() throws Exception {
        ResultDeduper d = newDeduper(true);
        // chunkIndex=-1 marks a page-only hit; complementary evidence, keep both.
        List<SearchHit> ranked = List.of(hit("d-1", 0, 0.9), hit("d-1", -1, 0.8));

        assertThat(d.collapse(ranked, 5)).hasSize(2);
    }

    @Test
    void backfill_fillsFreedSlotsFromDeeperCandidates() throws Exception {
        ResultDeduper d = newDeduper(true);
        // 6 candidates, 2 are dups of earlier hits, topK=4 → 4 unique survive
        // including the deep ones that would have been cut by plain truncation.
        List<SearchHit> ranked = List.of(
                hit("d-1", 0, 0.9),
                hit("d-1", 1, 0.85),  // dup of [0]
                hit("d-2", 7, 0.8),
                hit("d-2", 8, 0.75),  // dup of [2]
                hit("d-3", 0, 0.7),
                hit("d-4", 0, 0.65));

        List<SearchHit> out = d.collapse(ranked, 4);

        assertThat(out).extracting(SearchHit::docId)
                .containsExactly("d-1", "d-2", "d-3", "d-4");
    }

    @Test
    void disabled_truncatesWithoutCollapsing() throws Exception {
        ResultDeduper d = newDeduper(false);
        List<SearchHit> ranked = List.of(
                hit("d-1", 0, 0.9), hit("d-1", 1, 0.85), hit("d-2", 0, 0.8));

        List<SearchHit> out = d.collapse(ranked, 2);

        assertThat(out).hasSize(2);
        assertThat(out.get(1).chunkIndex()).isEqualTo(1); // dup kept
    }

    @Test
    void missingCharOffsets_fallBackToAdjacencyRuleOnly() throws Exception {
        ResultDeduper d = newDeduper(true);
        // Distance 2, no char offsets → not duplicates.
        List<SearchHit> ranked = List.of(hit("d-1", 0, 0.9), hit("d-1", 2, 0.8));

        assertThat(d.collapse(ranked, 5)).hasSize(2);
    }

    private static SearchHit hit(String docId, int chunkIndex, double score) {
        return hit(docId, chunkIndex, score, Map.of());
    }

    private static SearchHit hit(String docId, int chunkIndex, double score,
                                 Map<String, Object> metadata) {
        return new SearchHit(score, "text", "src", "f.pdf", docId, chunkIndex,
                1, 1, null, null, null, metadata);
    }

    private static ResultDeduper newDeduper(boolean enabled) throws Exception {
        ResultDeduper d = new ResultDeduper();
        Field f = ResultDeduper.class.getDeclaredField("enabled");
        f.setAccessible(true);
        f.setBoolean(d, enabled);
        return d;
    }
}
