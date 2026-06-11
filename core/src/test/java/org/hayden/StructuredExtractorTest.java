package org.hayden;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.hayden.backend.qdrant.Block;
import org.hayden.backend.qdrant.StructuredExtractor;
import org.hayden.ingest.FetchedFile;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredExtractorTest {

    // ---- non-PDF (Tika XHTML) ------------------------------------------------

    @Test
    void html_mapsHeadingsParagraphsListsAndTables() throws Exception {
        String html = """
                <html><body>
                <h1>Install</h1>
                <p>Intro paragraph.</p>
                <h2>Cooling</h2>
                <p>Fans go here.</p>
                <ul><li>first item</li><li>second item</li></ul>
                <table><tr><th>name</th><th>value</th></tr>
                       <tr><td>rpm</td><td>1200</td></tr></table>
                </body></html>""";
        StructuredExtractor x = extractor();

        List<Block> blocks = x.extractBlocks(file("guide.html", "text/html", html));

        assertThat(blocks).extracting(Block::type).containsExactly(
                Block.BlockType.HEADING,
                Block.BlockType.PARAGRAPH,
                Block.BlockType.HEADING,
                Block.BlockType.PARAGRAPH,
                Block.BlockType.LIST,
                Block.BlockType.TABLE);

        Block intro = blocks.get(1);
        assertThat(intro.text()).isEqualTo("Intro paragraph.");
        assertThat(intro.headingPath()).containsExactly("Install");

        Block fans = blocks.get(3);
        assertThat(fans.headingPath()).containsExactly("Install", "Cooling");

        Block list = blocks.get(4);
        assertThat(list.text()).isEqualTo("- first item\n- second item");

        Block table = blocks.get(5);
        assertThat(table.text()).isEqualTo("name\tvalue\nrpm\t1200");
        assertThat(table.pageNumber()).isEqualTo(1);
    }

    @Test
    void html_siblingHeadingReplacesStackAtSameLevel() throws Exception {
        String html = """
                <html><body>
                <h1>Install</h1><h2>Cooling</h2><p>a</p>
                <h2>Power</h2><p>b</p>
                </body></html>""";
        StructuredExtractor x = extractor();

        List<Block> blocks = x.extractBlocks(file("guide.html", "text/html", html));

        Block b = blocks.get(blocks.size() - 1);
        assertThat(b.text()).isEqualTo("b");
        assertThat(b.headingPath()).containsExactly("Install", "Power");
    }

    // ---- PDF (PDFBox font heuristics) ----------------------------------------

    @Test
    void pdf_classifiesLargeFontLinesAsHeadings_andTracksPages() throws Exception {
        byte[] pdf = twoPagePdfWithHeadings();
        StructuredExtractor x = extractor();

        List<Block> blocks = x.extractBlocks(
                new FetchedFile("manual.pdf", "application/pdf", pdf));

        List<Block> headings = blocks.stream()
                .filter(b -> b.type() == Block.BlockType.HEADING).toList();
        assertThat(headings).extracting(Block::text)
                .containsExactly("Install Guide", "Cooling");

        // Body paragraph on page 1 sits under "Install Guide".
        Block page1Body = blocks.stream()
                .filter(b -> b.type() == Block.BlockType.PARAGRAPH && b.pageNumber() == 1)
                .findFirst().orElseThrow();
        assertThat(page1Body.headingPath()).containsExactly("Install Guide");

        // Page 2's paragraph sits under the "Cooling" heading (stack carries
        // across pages) and is tagged with its own page number.
        Block page2Body = blocks.stream()
                .filter(b -> b.type() == Block.BlockType.PARAGRAPH && b.pageNumber() == 2)
                .findFirst().orElseThrow();
        assertThat(page2Body.headingPath()).contains("Cooling");
    }

    private static byte[] twoPagePdfWithHeadings() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            PDPage p1 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p1)) {
                cs.beginText();
                cs.setFont(regular, 24);
                cs.newLineAtOffset(50, 720);
                cs.showText("Install Guide");
                cs.endText();
                // Enough 12pt text that it wins the body-font vote.
                for (int i = 0; i < 4; i++) {
                    cs.beginText();
                    cs.setFont(regular, 12);
                    cs.newLineAtOffset(50, 680 - i * 20);
                    cs.showText("Body text line about installation procedures number " + i + ".");
                    cs.endText();
                }
            }

            PDPage p2 = new PDPage(PDRectangle.LETTER);
            doc.addPage(p2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p2)) {
                cs.beginText();
                cs.setFont(regular, 18);
                cs.newLineAtOffset(50, 720);
                cs.showText("Cooling");
                cs.endText();
                for (int i = 0; i < 3; i++) {
                    cs.beginText();
                    cs.setFont(regular, 12);
                    cs.newLineAtOffset(50, 680 - i * 20);
                    cs.showText("Fan configuration body text line number " + i + ".");
                    cs.endText();
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static FetchedFile file(String name, String contentType, String body) {
        return new FetchedFile(name, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    private static StructuredExtractor extractor() throws Exception {
        StructuredExtractor x = new StructuredExtractor();
        setField(x, "maxChars", 10_000_000);
        setField(x, "headingFontRatio", 1.15);
        return x;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
