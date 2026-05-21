package org.example;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.example.backend.qdrant.PageRasterizer;
import org.example.ingest.FetchedFile;
import org.example.ingest.IngestException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRasterizerTest {

    @Test
    void renderAll_singlePagePdf_returnsOnePng() throws Exception {
        PageRasterizer r = rasterizer(150, "RGB");
        FetchedFile pdf = pdfFixture(1);

        List<PageRasterizer.RenderedPage> pages = r.renderAll(pdf);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).pageNumber()).isEqualTo(1);
        assertThat(pages.get(0).pngBytes()).isNotEmpty();
        assertThat(isPng(pages.get(0).pngBytes())).isTrue();
        assertThat(pages.get(0).width()).isGreaterThan(0);
        assertThat(pages.get(0).height()).isGreaterThan(0);
    }

    @Test
    void renderAll_multiPagePdf_returnsAllPagesInOrder() throws Exception {
        PageRasterizer r = rasterizer(72, "RGB");
        FetchedFile pdf = pdfFixture(3);

        List<PageRasterizer.RenderedPage> pages = r.renderAll(pdf);

        assertThat(pages).hasSize(3);
        assertThat(pages).extracting(PageRasterizer.RenderedPage::pageNumber)
                .containsExactly(1, 2, 3);
        for (PageRasterizer.RenderedPage page : pages) {
            assertThat(isPng(page.pngBytes())).isTrue();
        }
    }

    @Test
    void renderAll_dpiAffectsDimensions() throws Exception {
        FetchedFile pdf = pdfFixture(1);
        PageRasterizer low = rasterizer(72, "RGB");
        PageRasterizer high = rasterizer(300, "RGB");

        PageRasterizer.RenderedPage lo = low.renderAll(pdf).get(0);
        PageRasterizer.RenderedPage hi = high.renderAll(pdf).get(0);

        assertThat(hi.width()).isGreaterThan(lo.width());
        assertThat(hi.height()).isGreaterThan(lo.height());
    }

    @Test
    void renderAll_grayImageType_works() throws Exception {
        PageRasterizer r = rasterizer(72, "GRAY");
        FetchedFile pdf = pdfFixture(1);

        List<PageRasterizer.RenderedPage> pages = r.renderAll(pdf);

        assertThat(pages).hasSize(1);
        assertThat(isPng(pages.get(0).pngBytes())).isTrue();
    }

    @Test
    void renderPage_specificPage_returnsThatPage() throws Exception {
        PageRasterizer r = rasterizer(72, "RGB");
        FetchedFile pdf = pdfFixture(5);

        PageRasterizer.RenderedPage page2 = r.renderPage(pdf, 2);

        assertThat(page2.pageNumber()).isEqualTo(2);
        assertThat(isPng(page2.pngBytes())).isTrue();
    }

    @Test
    void renderPage_outOfRange_throws() throws Exception {
        PageRasterizer r = rasterizer(72, "RGB");
        FetchedFile pdf = pdfFixture(3);

        assertThatThrownBy(() -> r.renderPage(pdf, 5))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("3 pages");
    }

    @Test
    void renderPage_zeroOrNegative_throws() throws Exception {
        PageRasterizer r = rasterizer(72, "RGB");
        FetchedFile pdf = pdfFixture(3);

        assertThatThrownBy(() -> r.renderPage(pdf, 0))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining(">= 1");
        assertThatThrownBy(() -> r.renderPage(pdf, -1))
                .isInstanceOf(IngestException.class);
    }

    @Test
    void renderAll_nonPdf_throws() throws Exception {
        PageRasterizer r = rasterizer(72, "RGB");
        FetchedFile notPdf = new FetchedFile("notes.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> r.renderAll(notPdf))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("only supports PDFs");
    }

    @Test
    void invalidImageType_throws() throws Exception {
        PageRasterizer r = rasterizer(72, "PURPLE");
        FetchedFile pdf = pdfFixture(1);

        assertThatThrownBy(() -> r.renderAll(pdf))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("PURPLE");
    }

    private static PageRasterizer rasterizer(int dpi, String imageType) throws Exception {
        PageRasterizer r = new PageRasterizer();
        Field d = PageRasterizer.class.getDeclaredField("dpi");
        d.setAccessible(true);
        d.setInt(r, dpi);
        Field t = PageRasterizer.class.getDeclaredField("imageType");
        t.setAccessible(true);
        t.set(r, imageType);
        return r;
    }

    /**
     * Build an in-memory PDF with the given page count. Each page carries a tiny
     * "Page N content" string so PDFBox produces real PDF byte streams (vs. an
     * empty doc, which can confuse some renderers).
     */
    private static FetchedFile pdfFixture(int numPages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= numPages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Page " + i + " content");
                    cs.endText();
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return new FetchedFile("test-" + numPages + "p.pdf",
                    "application/pdf", baos.toByteArray());
        }
    }

    /** PNG magic number: 89 50 4E 47 0D 0A 1A 0A */
    private static boolean isPng(byte[] bytes) {
        return bytes.length > 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A;
    }
}
