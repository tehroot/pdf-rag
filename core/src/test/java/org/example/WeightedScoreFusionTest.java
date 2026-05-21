package org.example;

import org.example.backend.qdrant.PageHit;
import org.example.backend.qdrant.fusion.FusionConfig;
import org.example.backend.qdrant.fusion.WeightedScoreFusion;
import org.example.ingest.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedScoreFusionTest {

    private final WeightedScoreFusion fusion = new WeightedScoreFusion();

    @Test
    void name_isWeighted() {
        assertThat(fusion.name()).isEqualTo("weighted");
    }

    @Test
    void textOnly_scoresAreNormalizedAndWeighted() {
        // cosine 0.8 with floor 1.0 → text_signal = 0.8.
        // weight 0.5 * 0.8 = 0.4 final score.
        List<SearchHit> text = List.of(chunk("doc-1", 0, 1, 0.8));
        FusionConfig cfg = new FusionConfig(5, 60, 0.5, 0.5, 1.0, 50.0);

        List<SearchHit> fused = fusion.fuse(text, List.of(), cfg);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).score()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void fullVisualMatch_addsWeightedBoost() {
        // text 0.5 with floor 1.0 → 0.5; visual 50 with floor 50 → 1.0.
        // 0.5*0.5 + 0.5*1.0 = 0.75.
        List<SearchHit> text = List.of(chunk("doc-1", 0, 1, 0.5));
        List<PageHit> pages = List.of(page("doc-1", 1, 50.0));
        FusionConfig cfg = new FusionConfig(5, 60, 0.5, 0.5, 1.0, 50.0);

        List<SearchHit> fused = fusion.fuse(text, pages, cfg);

        assertThat(fused.get(0).score()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void asymmetricWeights_areApplied() {
        // Same inputs, but weight visual 80%, text 20%.
        // text 0.5 floor 1.0 → 0.5; visual 50 floor 50 → 1.0
        // 0.2*0.5 + 0.8*1.0 = 0.9.
        List<SearchHit> text = List.of(chunk("doc-1", 0, 1, 0.5));
        List<PageHit> pages = List.of(page("doc-1", 1, 50.0));
        FusionConfig cfg = new FusionConfig(5, 60, 0.2, 0.8, 1.0, 50.0);

        List<SearchHit> fused = fusion.fuse(text, pages, cfg);

        assertThat(fused.get(0).score()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void clampsToOneWhenScoreExceedsFloor() {
        // text 5.0 with floor 1.0 → clamped to 1.0; visual 200 floor 50 → clamped to 1.0.
        // 0.5*1.0 + 0.5*1.0 = 1.0.
        List<SearchHit> text = List.of(chunk("doc-1", 0, 1, 5.0));
        List<PageHit> pages = List.of(page("doc-1", 1, 200.0));
        FusionConfig cfg = new FusionConfig(5, 60, 0.5, 0.5, 1.0, 50.0);

        List<SearchHit> fused = fusion.fuse(text, pages, cfg);

        assertThat(fused.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void orphanPage_isSurfaced() {
        List<SearchHit> text = List.of(chunk("doc-A", 0, 1, 0.5));
        List<PageHit> pages = List.of(page("doc-B", 1, 50.0));
        FusionConfig cfg = new FusionConfig(5, 60, 0.5, 0.5, 1.0, 50.0);

        List<SearchHit> fused = fusion.fuse(text, pages, cfg);

        assertThat(fused).extracting(SearchHit::docId)
                .containsExactlyInAnyOrder("doc-A", "doc-B");
    }

    @Test
    void topK_limitsResults() {
        List<SearchHit> text = List.of(
                chunk("doc-1", 0, 1, 0.9),
                chunk("doc-2", 0, 1, 0.8),
                chunk("doc-3", 0, 1, 0.7));
        FusionConfig cfg = new FusionConfig(2, 60, 0.5, 0.5, 1.0, 50.0);

        List<SearchHit> fused = fusion.fuse(text, List.of(), cfg);

        assertThat(fused).hasSize(2);
    }

    @Test
    void pageScoreIsRecordedOnHit() {
        List<SearchHit> text = List.of(chunk("doc-1", 0, 1, 0.5));
        List<PageHit> pages = List.of(page("doc-1", 1, 42.0));
        FusionConfig cfg = new FusionConfig(5, 60, 0.5, 0.5, 1.0, 50.0);

        List<SearchHit> fused = fusion.fuse(text, pages, cfg);

        assertThat(fused.get(0).pageScore()).isCloseTo(42.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    // ---- helpers ------------------------------------------------------------

    private static SearchHit chunk(String docId, int chunkIndex, int page, double score) {
        return new SearchHit(score, "text-" + chunkIndex, "src", "f.pdf",
                docId, chunkIndex, page, page, score, null, null, Map.of("text_quality", 2));
    }

    private static PageHit page(String docId, int pageNumber, double score) {
        return new PageHit(score, docId, pageNumber, "f.pdf", "src", 2,
                "key-" + docId + "-" + pageNumber, Map.of());
    }
}
