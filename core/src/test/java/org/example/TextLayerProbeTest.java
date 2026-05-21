package org.example;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.example.backend.qdrant.TextLayerProbe;
import org.example.ingest.FetchedFile;
import org.example.ingest.IngestException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextLayerProbeTest {

    @Test
    void probe_pdfWithFullTextLayer_classifiesAsTwo() throws Exception {
        TextLayerProbe probe = newProbe(50, 500);
        FetchedFile pdf = textPdf(1, page -> "Lorem ipsum ".repeat(60));   // ~720 chars

        List<TextLayerProbe.PageQuality> out = probe.probe(pdf);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).pageNumber()).isEqualTo(1);
        assertThat(out.get(0).textQuality()).isEqualTo(2);
        assertThat(out.get(0).charCount()).isGreaterThanOrEqualTo(500);
    }

    @Test
    void probe_pdfWithSparseText_classifiesAsOne() throws Exception {
        TextLayerProbe probe = newProbe(50, 500);
        FetchedFile pdf = textPdf(1, page -> "short heading");   // ~13 chars... but PDFBox
        // returns char count + a newline, etc. Build a deliberately-medium page.

        // Re-build to land squarely in the 50..500 range.
        FetchedFile medium = textPdf(1, p -> "medium length content. ".repeat(8));   // ~184 chars

        List<TextLayerProbe.PageQuality> out = probe.probe(medium);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).textQuality()).isEqualTo(1);
        assertThat(out.get(0).charCount()).isBetween(50, 499);
    }

    @Test
    void probe_pdfWithMinimalText_classifiesAsZero() throws Exception {
        TextLayerProbe probe = newProbe(50, 500);
        FetchedFile pdf = textPdf(1, page -> "a");

        List<TextLayerProbe.PageQuality> out = probe.probe(pdf);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).textQuality()).isEqualTo(0);
        assertThat(out.get(0).charCount()).isLessThan(50);
    }

    @Test
    void probe_multiPagePdf_returnsPerPageClassification() throws Exception {
        TextLayerProbe probe = newProbe(50, 500);
        // 3 pages: full / sparse / minimal.
        FetchedFile pdf = textPdf(3, page -> switch (page) {
            case 1 -> "Lorem ipsum dolor sit amet. ".repeat(50);   // full
            case 2 -> "medium content here. ".repeat(7);            // sparse
            default -> "x";                                         // minimal
        });

        List<TextLayerProbe.PageQuality> out = probe.probe(pdf);

        assertThat(out).hasSize(3);
        assertThat(out.get(0).pageNumber()).isEqualTo(1);
        assertThat(out.get(0).textQuality()).isEqualTo(2);
        assertThat(out.get(1).textQuality()).isEqualTo(1);
        assertThat(out.get(2).textQuality()).isEqualTo(0);
    }

    @Test
    void probe_thresholdsAreConfigurable() throws Exception {
        // Same PDF, different thresholds → different classification.
        FetchedFile pdf = textPdf(1, p -> "content content content. ".repeat(10));   // ~250 chars

        TextLayerProbe permissive = newProbe(10, 100);   // lower bar → full
        TextLayerProbe strict = newProbe(500, 2000);     // higher bar → not full

        assertThat(permissive.probe(pdf).get(0).textQuality()).isEqualTo(2);
        assertThat(strict.probe(pdf).get(0).textQuality()).isEqualTo(0);
    }

    @Test
    void probe_nonPdf_singlePageQualityTwo() throws Exception {
        TextLayerProbe probe = newProbe(50, 500);
        FetchedFile txt = new FetchedFile("doc.txt", "text/plain",
                "any content".getBytes());

        List<TextLayerProbe.PageQuality> out = probe.probe(txt);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).pageNumber()).isEqualTo(1);
        assertThat(out.get(0).textQuality()).isEqualTo(2);
    }

    @Test
    void probe_invalidThresholds_rejected() throws Exception {
        TextLayerProbe inverted = newProbe(500, 50);   // low > full
        FetchedFile pdf = textPdf(1, p -> "anything");
        assertThatThrownBy(() -> inverted.probe(pdf))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("threshold_low");
    }

    @Test
    void classify_boundaryValues() throws Exception {
        TextLayerProbe probe = newProbe(50, 500);
        assertThat(probe.classify(0)).isEqualTo(0);
        assertThat(probe.classify(49)).isEqualTo(0);
        assertThat(probe.classify(50)).isEqualTo(1);
        assertThat(probe.classify(499)).isEqualTo(1);
        assertThat(probe.classify(500)).isEqualTo(2);
        assertThat(probe.classify(99999)).isEqualTo(2);
    }

    private static TextLayerProbe newProbe(int low, int full) throws Exception {
        TextLayerProbe p = new TextLayerProbe();
        Field l = TextLayerProbe.class.getDeclaredField("thresholdLow");
        l.setAccessible(true);
        l.setInt(p, low);
        Field f = TextLayerProbe.class.getDeclaredField("thresholdFull");
        f.setAccessible(true);
        f.setInt(p, full);
        return p;
    }

    /**
     * Generate an N-page PDF where each page's content is produced by {@code pageContent}
     * given the 1-indexed page number.
     */
    private static FetchedFile textPdf(int numPages, IntFunction<String> pageContent) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= numPages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                String content = pageContent.apply(i);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.setLeading(12f);
                    cs.newLineAtOffset(40, 740);
                    // Naive word wrap: split content into lines of ~80 chars
                    int width = 80;
                    for (int j = 0; j < content.length(); j += width) {
                        String line = content.substring(j, Math.min(j + width, content.length()));
                        cs.showText(line);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return new FetchedFile("test.pdf", "application/pdf", baos.toByteArray());
        }
    }
}
