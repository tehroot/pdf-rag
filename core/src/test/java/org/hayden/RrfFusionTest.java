package org.hayden;

import org.hayden.backend.qdrant.PageHit;
import org.hayden.backend.qdrant.fusion.FusionConfig;
import org.hayden.backend.qdrant.fusion.RrfFusion;
import org.hayden.ingest.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    private final RrfFusion fusion = new RrfFusion();

    @Test
    void name_isRrf() {
        assertThat(fusion.name()).isEqualTo("rrf");
    }

    @Test
    void textOnly_returnsTextHitsInOrder() {
        List<SearchHit> text = List.of(
                chunk("doc-1", 0, 1, 0.9),
                chunk("doc-1", 1, 1, 0.8),
                chunk("doc-2", 0, 2, 0.7));

        List<SearchHit> fused = fusion.fuse(text, List.of(), config(10));

        assertThat(fused).hasSize(3);
        // Order preserved from text input (no page boosts).
        assertThat(fused.get(0).docId()).isEqualTo("doc-1");
        assertThat(fused.get(0).chunkIndex()).isEqualTo(0);
    }

    @Test
    void chunkWithMatchingPage_outranksLonerChunk() {
        // Two chunks: chunk-A from doc-1 page 1, chunk-B from doc-2 page 1.
        // Text pipeline ranks B slightly higher than A.
        List<SearchHit> text = List.of(
                chunk("doc-2", 0, 1, 0.85),     // text rank 1
                chunk("doc-1", 0, 1, 0.80));    // text rank 2
        // Visual pipeline ranks doc-1 page 1 #1 — strong agreement.
        List<PageHit> pages = List.of(page("doc-1", 1, 0.95));

        List<SearchHit> fused = fusion.fuse(text, pages, config(10));

        // With RRF: B has 1/(60+1) = 0.0164; A has 1/(60+2) + 1/(60+1) = 0.0162 + 0.0164 = 0.0326.
        // A wins despite lower text rank because of the page agreement bonus.
        assertThat(fused.get(0).docId()).isEqualTo("doc-1");
        assertThat(fused.get(0).pageScore()).isCloseTo(0.95, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void pageMatchUsesPageRangeNotJustPageNumber() {
        // Chunk spans pages 3-4. Page hit at page 4 should match.
        List<SearchHit> text = List.of(spanningChunk("doc-1", 0, 3, 4, 0.5));
        List<PageHit> pages = List.of(page("doc-1", 4, 0.9));

        List<SearchHit> fused = fusion.fuse(text, pages, config(5));

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).pageScore()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void pageMatchOutsideChunkRange_doesNotJoin() {
        // Chunk on page 1; page hit on page 5 — no match.
        List<SearchHit> text = List.of(chunk("doc-1", 0, 1, 0.5));
        List<PageHit> pages = List.of(page("doc-1", 5, 0.9));

        List<SearchHit> fused = fusion.fuse(text, pages, config(10));

        // The original chunk + orphan page hit both present (doc ids match
        // but page out of range means chunk gets no page boost; the page is
        // surfaced as an orphan only if no chunk shared its doc — which is
        // not the case here, so we expect only the chunk).
        SearchHit chunkHit = fused.stream()
                .filter(h -> h.chunkIndex() == 0)
                .findFirst().orElseThrow();
        assertThat(chunkHit.pageScore()).isNull();
    }

    @Test
    void orphanPageWhenDocHasNoChunks_isSurfaced() {
        // Text pipeline has chunks from doc-1 only.
        // Visual pipeline ranks doc-2 page 1 highly.
        // The doc-2 page should appear as an orphan in the fused output.
        List<SearchHit> text = List.of(chunk("doc-1", 0, 1, 0.5));
        List<PageHit> pages = List.of(page("doc-2", 1, 0.9));

        List<SearchHit> fused = fusion.fuse(text, pages, config(10));

        assertThat(fused).extracting(SearchHit::docId)
                .containsExactlyInAnyOrder("doc-1", "doc-2");
        SearchHit orphan = fused.stream()
                .filter(h -> "doc-2".equals(h.docId()))
                .findFirst().orElseThrow();
        assertThat(orphan.chunkIndex()).isEqualTo(-1);
        assertThat(orphan.text()).isNull();
        assertThat(orphan.pageScore()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void topKLimit_isApplied() {
        List<SearchHit> text = List.of(
                chunk("doc-1", 0, 1, 0.9),
                chunk("doc-2", 0, 1, 0.8),
                chunk("doc-3", 0, 1, 0.7),
                chunk("doc-4", 0, 1, 0.6));

        List<SearchHit> fused = fusion.fuse(text, List.of(), config(2));

        assertThat(fused).hasSize(2);
    }

    @Test
    void agreementProducesMonotonicScoreBoost() {
        // Same text rank, same page score, but one with agreement and one without.
        // Without agreement (no matching page) should score lower.
        List<SearchHit> text = List.of(
                chunk("doc-A", 0, 1, 0.5),
                chunk("doc-B", 0, 1, 0.5));
        List<PageHit> pages = List.of(page("doc-A", 1, 0.5));

        List<SearchHit> fused = fusion.fuse(text, pages, config(10));

        // doc-A has page boost; doc-B doesn't. doc-A should rank first.
        assertThat(fused.get(0).docId()).isEqualTo("doc-A");
        assertThat(fused.get(0).score()).isGreaterThan(fused.get(1).score());
    }

    @Test
    void rrfKConstant_isHonored() {
        FusionConfig smallK = new FusionConfig(5, 1, 0.5, 0.5, 1.0, 50.0);
        FusionConfig largeK = new FusionConfig(5, 1000, 0.5, 0.5, 1.0, 50.0);

        List<SearchHit> text = List.of(
                chunk("doc-1", 0, 1, 0.9),
                chunk("doc-1", 1, 1, 0.8));
        List<PageHit> pages = List.of(page("doc-1", 1, 0.5));

        List<SearchHit> fusedSmall = fusion.fuse(text, pages, smallK);
        List<SearchHit> fusedLarge = fusion.fuse(text, pages, largeK);

        // Small k: 1/(1+1) = 0.5 per pipeline; large gap between ranks.
        // Large k: 1/(1000+1) ≈ 0.000999; ranks become nearly indistinguishable.
        assertThat(fusedSmall.get(0).score()).isGreaterThan(fusedLarge.get(0).score());
    }

    // ---- helpers ------------------------------------------------------------

    private static SearchHit chunk(String docId, int chunkIndex, int page, double score) {
        return new SearchHit(score, "text-" + chunkIndex, "src", "f.pdf",
                docId, chunkIndex, page, page, score, null, null, Map.of("text_quality", 2));
    }

    private static SearchHit spanningChunk(String docId, int chunkIndex,
                                            int pageStart, int pageEnd, double score) {
        return new SearchHit(score, "text-" + chunkIndex, "src", "f.pdf",
                docId, chunkIndex, pageStart, pageEnd, score, null, null,
                Map.of("text_quality", 2));
    }

    private static PageHit page(String docId, int pageNumber, double score) {
        return new PageHit(score, docId, pageNumber, "f.pdf", "src", 2,
                "key-" + docId + "-" + pageNumber, Map.of());
    }

    private static FusionConfig config(int topK) {
        return new FusionConfig(topK, 60, 0.5, 0.5, 1.0, 50.0);
    }
}
