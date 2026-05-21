package org.hayden;

import org.hayden.backend.qdrant.PageHit;
import org.hayden.backend.qdrant.fusion.ConfidenceCalculator;
import org.hayden.ingest.SearchHit;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceCalculatorTest {

    @Test
    void bucket_thresholds() throws Exception {
        ConfidenceCalculator cc = newCalculator();
        // Plan defaults: high>0.7, medium>0.4, else low.
        assertThat(cc.bucket(0.9)).isEqualTo("high");
        assertThat(cc.bucket(0.71)).isEqualTo("high");
        assertThat(cc.bucket(0.7)).isEqualTo("medium");   // strict >, not >=
        assertThat(cc.bucket(0.5)).isEqualTo("medium");
        assertThat(cc.bucket(0.41)).isEqualTo("medium");
        assertThat(cc.bucket(0.4)).isEqualTo("low");
        assertThat(cc.bucket(0.1)).isEqualTo("low");
    }

    @Test
    void cleanTextHitWithAgreement_scoresHigh() throws Exception {
        ConfidenceCalculator cc = newCalculator();
        SearchHit hit = hit("doc-1", 0.85, 60.0, 2);  // text_quality=2 → trust=1.0

        // 0.4 * 0.85 * 1.0 + 0.4 * (60/50 clamped to 1.0) + 0.2 * 1.0 (agree)
        // = 0.34 + 0.4 + 0.2 = 0.94 → high.
        double s = cc.score(hit, Set.of("doc-1"), Set.of("doc-1"));
        assertThat(s).isCloseTo(0.94, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(cc.bucket(s)).isEqualTo("high");
    }

    @Test
    void ocrGarbageHitButVisualAgrees_scoresMedium() throws Exception {
        ConfidenceCalculator cc = newCalculator();
        // text_quality=0 → text_trust=0 → text_signal collapses to 0.
        // Strong visual rescues it: 0.4 * 0 + 0.4 * 1.0 + 0.2 * 1.0 = 0.6 → medium.
        SearchHit hit = hit("doc-1", 0.5, 60.0, 0);

        double s = cc.score(hit, Set.of("doc-1"), Set.of("doc-1"));
        assertThat(s).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(cc.bucket(s)).isEqualTo("medium");
    }

    @Test
    void bothUncertain_scoresLow() throws Exception {
        ConfidenceCalculator cc = newCalculator();
        // text 0.4 text_quality=0 → 0. visual 5/50=0.1. agree=1.
        // 0 + 0.4*0.1 + 0.2*1 = 0.04 + 0.2 = 0.24 → low.
        SearchHit hit = hit("doc-1", 0.4, 5.0, 0);

        double s = cc.score(hit, Set.of("doc-1"), Set.of("doc-1"));
        assertThat(s).isCloseTo(0.24, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(cc.bucket(s)).isEqualTo("low");
    }

    @Test
    void textOnlyMatch_noAgreement_lowerScore() throws Exception {
        ConfidenceCalculator cc = newCalculator();
        // Hit is in text-pipeline only — agreement=0.5 (penalized).
        // text 0.8 * 1.0 trust = 0.8 * 0.4 = 0.32 text term;
        // visual signal 0 (no page match); agreement 0.5 → 0.1;
        // total = 0.42.
        SearchHit hit = hitNoVisual("doc-1", 0.8, 2);

        double s = cc.score(hit, Set.of("doc-1"), Set.of("doc-other"));
        assertThat(s).isCloseTo(0.42, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(cc.bucket(s)).isEqualTo("medium");
    }

    @Test
    void annotate_attachesPerHitAndResponseLevelConfidence() throws Exception {
        ConfidenceCalculator cc = newCalculator();
        List<SearchHit> hits = List.of(
                hit("doc-1", 0.85, 60.0, 2),     // high
                hit("doc-2", 0.5, 5.0, 0));       // low
        List<SearchHit> textHits = hits;
        List<PageHit> pageHits = List.of(
                new PageHit(60.0, "doc-1", 1, "f", "s", 2, "k", Map.of()),
                new PageHit(5.0, "doc-2", 1, "f", "s", 0, "k", Map.of()));

        ConfidenceCalculator.Result result = cc.annotate(hits, textHits, pageHits);

        assertThat(result.hits()).hasSize(2);
        assertThat(result.hits().get(0).confidence()).isEqualTo("high");
        assertThat(result.hits().get(1).confidence()).isEqualTo("low");
        // Response-level = max of per-hit confidence.
        assertThat(result.responseConfidence()).isEqualTo("high");
    }

    @Test
    void annotate_emptyHits_returnsNullResponseConfidence() throws Exception {
        ConfidenceCalculator cc = newCalculator();
        ConfidenceCalculator.Result result = cc.annotate(List.of(), List.of(), List.of());
        assertThat(result.hits()).isEmpty();
        assertThat(result.responseConfidence()).isNull();
    }

    @Test
    void textTrust_scales_proportionallyWithQuality() throws Exception {
        ConfidenceCalculator cc = newCalculator();
        // text 0.8, visual 0 (no page), agree=0.5 (text-only).
        // text_quality 2 (trust 1.0): 0.4 * 0.8 + 0 + 0.1 = 0.42
        // text_quality 1 (trust 0.5): 0.4 * 0.4 + 0 + 0.1 = 0.26
        // text_quality 0 (trust 0):   0.4 * 0   + 0 + 0.1 = 0.10
        SearchHit h2 = hitNoVisual("doc", 0.8, 2);
        SearchHit h1 = hitNoVisual("doc", 0.8, 1);
        SearchHit h0 = hitNoVisual("doc", 0.8, 0);
        Set<String> textDocs = Set.of("doc");
        Set<String> pageDocs = Set.of("other");

        assertThat(cc.score(h2, textDocs, pageDocs)).isCloseTo(0.42, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(cc.score(h1, textDocs, pageDocs)).isCloseTo(0.26, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(cc.score(h0, textDocs, pageDocs)).isCloseTo(0.10, org.assertj.core.data.Offset.offset(1e-6));
    }

    // ---- helpers ------------------------------------------------------------

    private static SearchHit hit(String docId, double textScore, double pageScore, int textQuality) {
        return new SearchHit(textScore, "t", "s", "f.pdf", docId, 0, 1, 1,
                textScore, pageScore, null, Map.of("text_quality", textQuality));
    }

    private static SearchHit hitNoVisual(String docId, double textScore, int textQuality) {
        return new SearchHit(textScore, "t", "s", "f.pdf", docId, 0, 1, 1,
                textScore, null, null, Map.of("text_quality", textQuality));
    }

    private static ConfidenceCalculator newCalculator() throws Exception {
        ConfidenceCalculator cc = new ConfidenceCalculator();
        set(cc, "weightText", 0.4);
        set(cc, "weightVisual", 0.4);
        set(cc, "weightAgreement", 0.2);
        set(cc, "thresholdHigh", 0.7);
        set(cc, "thresholdMedium", 0.4);
        set(cc, "textScoreFloor", 1.0);
        set(cc, "visualScoreFloor", 50.0);
        return cc;
    }

    private static void set(Object t, String name, Object v) throws Exception {
        Field f = t.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(t, v);
    }
}
