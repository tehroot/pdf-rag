package org.hayden;

import org.hayden.sizing.CorpusSizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusSizerTest {

    @Test
    void estimatePageChunks_matchesSlidingChunkerShape() {
        // Empty page → no chunks; anything non-empty → at least one.
        assertThat(CorpusSizer.estimatePageChunks(0, 1500, 200)).isZero();
        assertThat(CorpusSizer.estimatePageChunks(1, 1500, 200)).isEqualTo(1);
        assertThat(CorpusSizer.estimatePageChunks(1500, 1500, 200)).isEqualTo(1);
        // One char past the window → a second chunk (stride = size - overlap).
        assertThat(CorpusSizer.estimatePageChunks(1501, 1500, 200)).isEqualTo(2);
        // 1500 + 2×1300 = 4100 fits exactly in 3 chunks; 4101 needs a 4th.
        assertThat(CorpusSizer.estimatePageChunks(4100, 1500, 200)).isEqualTo(3);
        assertThat(CorpusSizer.estimatePageChunks(4101, 1500, 200)).isEqualTo(4);
    }

    @Test
    void classify_usesProdThresholds() {
        assertThat(CorpusSizer.classify(0)).isZero();
        assertThat(CorpusSizer.classify(49)).isZero();
        assertThat(CorpusSizer.classify(50)).isEqualTo(1);
        assertThat(CorpusSizer.classify(499)).isEqualTo(1);
        assertThat(CorpusSizer.classify(500)).isEqualTo(2);
    }

    @Test
    void analyze_countsPagesAndQualityOnRealPdf() throws Exception {
        // 2-page PDF with a short line per page → q0 (below 50 chars) both pages.
        Path tmp = Files.createTempFile("sizer-", ".pdf");
        try {
            Files.write(tmp, makeTinyPdf(2));
            CorpusSizer.DocStats s = CorpusSizer.analyze(tmp.toFile(), 1500, 200);
            assertThat(s.pages).isEqualTo(2);
            assertThat(s.pagesByQuality[0]).isEqualTo(2);
            assertThat(s.chunks).isEqualTo(2);   // one chunk per non-empty page
            assertThat(s.bytes).isGreaterThan(0);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void humanAndDuration_formatSanely() {
        assertThat(CorpusSizer.human(500)).isEqualTo("500 B");
        assertThat(CorpusSizer.human(2L * 1024 * 1024 * 1024)).isEqualTo("2.0 GB");
        assertThat(CorpusSizer.duration(30)).isEqualTo("30 s");
        assertThat(CorpusSizer.duration(3 * 24 * 3600)).isEqualTo("3.0 days");
    }

    private static byte[] makeTinyPdf(int numPages) throws java.io.IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 1; i <= numPages; i++) {
                org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage(
                        org.apache.pdfbox.pdmodel.common.PDRectangle.LETTER);
                doc.addPage(page);
                try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                             new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("page " + i);
                    cs.endText();
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }
}
