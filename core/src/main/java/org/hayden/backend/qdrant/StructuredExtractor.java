package org.hayden.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.ingest.FetchedFile;
import org.hayden.ingest.IngestException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Structure-aware extraction: instead of one flat text blob, produces a list
 * of {@link Block}s (headings, paragraphs, lists, tables) each tagged with
 * its source page and heading ancestry.
 *
 * <p>Non-PDF formats go through Tika's XHTML SAX events, which carry real
 * structure ({@code h1..h6}, {@code p}, {@code li}, {@code table}). PDFs get
 * none of that from Tika, so they go through a PDFBox {@link PDFTextStripper}
 * that captures per-line font metrics and classifies headings by font size
 * relative to the document's body text (plus a bold-short-line heuristic).
 */
@ApplicationScoped
public class StructuredExtractor {

    @ConfigProperty(name = "ingest.extract.max-chars", defaultValue = "10000000")
    int maxChars;

    /** A line is a heading when its font size ≥ body-font size × this ratio. */
    @ConfigProperty(name = "ingest.chunk.heading-font-ratio", defaultValue = "1.15")
    double headingFontRatio;

    /** ≥3 cells separated by tabs or 2+ spaces ⇒ a table row (PDF heuristic). */
    private static final Pattern TABLE_CELL_SPLIT = Pattern.compile("\t|\\s{2,}");

    public List<Block> extractBlocks(FetchedFile file) {
        List<Block> blocks = isPdf(file) ? extractPdfBlocks(file) : extractXhtmlBlocks(file);
        if (blocks.isEmpty()) {
            throw new IngestException("Structured extraction produced no blocks for "
                    + file.filename());
        }
        return blocks;
    }

    private static boolean isPdf(FetchedFile file) {
        if (file.contentType() != null
                && file.contentType().toLowerCase().contains("pdf")) {
            return true;
        }
        return file.filename() != null
                && file.filename().toLowerCase().endsWith(".pdf");
    }

    // ---- non-PDF: Tika XHTML SAX events --------------------------------------

    private List<Block> extractXhtmlBlocks(FetchedFile file) {
        Metadata metadata = new Metadata();
        if (file.filename() != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.filename());
        }
        if (file.contentType() != null) {
            metadata.set(Metadata.CONTENT_TYPE, file.contentType());
        }
        XhtmlBlockHandler handler = new XhtmlBlockHandler(maxChars);
        try (ByteArrayInputStream in = new ByteArrayInputStream(file.content())) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        } catch (IOException | SAXException | TikaException e) {
            throw new IngestException("Failed structured extraction from " + file.filename(), e);
        }
        handler.finish();
        return handler.blocks;
    }

    /**
     * Maps Tika's XHTML SAX events to blocks. All blocks land on page 1 —
     * Tika's XHTML stream for non-paginated formats has no page concept
     * (matches {@code TextExtractor.extractPerPage} semantics).
     */
    private static final class XhtmlBlockHandler extends DefaultHandler {
        final List<Block> blocks = new ArrayList<>();
        final List<String> headingStack = new ArrayList<>();
        final StringBuilder text = new StringBuilder();      // paragraph / heading buffer
        final StringBuilder item = new StringBuilder();      // current <li>
        final StringBuilder list = new StringBuilder();      // whole list
        final StringBuilder cell = new StringBuilder();      // current <td>/<th>
        final List<String> rowCells = new ArrayList<>();
        final StringBuilder table = new StringBuilder();     // whole table
        final int maxChars;
        int headingLevel;
        int listDepth;
        int tableDepth;
        int totalChars;

        XhtmlBlockHandler(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes atts)
                throws SAXException {
            switch (local) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    flushParagraph();
                    headingLevel = local.charAt(1) - '0';
                }
                case "p" -> {
                    if (listDepth == 0 && tableDepth == 0) {
                        flushParagraph();
                    }
                }
                case "ul", "ol" -> {
                    if (tableDepth == 0) {
                        if (listDepth == 0) {
                            flushParagraph();
                        }
                        listDepth++;
                    }
                }
                case "table" -> {
                    flushParagraph();
                    tableDepth++;
                }
                case "br" -> current().append('\n');
                default -> {
                }
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) throws SAXException {
            switch (local) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    String heading = squash(text.toString());
                    text.setLength(0);
                    int level = local.charAt(1) - '0';
                    headingLevel = 0;
                    if (!heading.isEmpty()) {
                        while (headingStack.size() >= level) {
                            headingStack.remove(headingStack.size() - 1);
                        }
                        headingStack.add(heading);
                        emit(Block.BlockType.HEADING, heading);
                    }
                }
                case "p" -> {
                    if (listDepth == 0 && tableDepth == 0) {
                        flushParagraph();
                    }
                }
                case "li" -> {
                    if (tableDepth == 0 && listDepth > 0) {
                        String it = squash(item.toString());
                        item.setLength(0);
                        if (!it.isEmpty()) {
                            list.append("- ").append(it).append('\n');
                        }
                    }
                }
                case "ul", "ol" -> {
                    if (tableDepth == 0 && listDepth > 0) {
                        listDepth--;
                        if (listDepth == 0) {
                            String l = list.toString().strip();
                            list.setLength(0);
                            if (!l.isEmpty()) {
                                emit(Block.BlockType.LIST, l);
                            }
                        }
                    }
                }
                case "td", "th" -> {
                    if (tableDepth > 0) {
                        rowCells.add(squash(cell.toString()));
                        cell.setLength(0);
                    }
                }
                case "tr" -> {
                    if (tableDepth > 0) {
                        if (rowCells.stream().anyMatch(c -> !c.isEmpty())) {
                            table.append(String.join("\t", rowCells)).append('\n');
                        }
                        rowCells.clear();
                    }
                }
                case "table" -> {
                    if (tableDepth > 0) {
                        tableDepth--;
                        if (tableDepth == 0) {
                            String t = table.toString().strip();
                            table.setLength(0);
                            if (!t.isEmpty()) {
                                emit(Block.BlockType.TABLE, t);
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            totalChars += length;
            if (totalChars > maxChars) {
                throw new SAXException("extracted text exceeded ingest.extract.max-chars="
                        + maxChars);
            }
            current().append(ch, start, length);
        }

        private StringBuilder current() {
            if (tableDepth > 0) return cell;
            if (listDepth > 0) return item;
            return text;
        }

        private void flushParagraph() {
            String p = squash(text.toString());
            text.setLength(0);
            if (!p.isEmpty()) {
                emit(Block.BlockType.PARAGRAPH, p);
            }
        }

        void finish() {
            flushParagraph();
        }

        private void emit(Block.BlockType type, String blockText) {
            blocks.add(new Block(type, blockText, 1, List.copyOf(headingStack)));
        }

        private static String squash(String s) {
            return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                    .replaceAll(" ?\\n ?", "\n")
                    .strip();
        }
    }

    // ---- PDF: PDFBox line metrics + font-size heading heuristics -------------

    private record Line(String text, float fontSize, boolean bold, int page) {
    }

    private List<Block> extractPdfBlocks(FetchedFile file) {
        List<Line> lines = collectLines(file);
        if (lines.isEmpty()) {
            throw new IngestException("PDFBox extracted no text from " + file.filename()
                    + " (likely a scanned PDF without a text layer; "
                    + "ColPali / OCR is required to read it)");
        }
        float bodySize = bodyFontSize(lines);
        List<Float> headingSizes = headingSizeLevels(lines, bodySize);
        return groupLines(lines, bodySize, headingSizes);
    }

    private List<Line> collectLines(FetchedFile file) {
        try (PDDocument doc = Loader.loadPDF(file.content())) {
            if (doc.getNumberOfPages() == 0) {
                throw new IngestException("PDF " + file.filename() + " has 0 pages");
            }
            LineCollector collector = new LineCollector();
            collector.getText(doc);
            int total = collector.lines.stream().mapToInt(l -> l.text().length()).sum();
            if (total > maxChars) {
                throw new IngestException("PDF " + file.filename()
                        + " exceeded ingest.extract.max-chars=" + maxChars);
            }
            return collector.lines;
        } catch (IOException e) {
            throw new IngestException("Failed to read PDF " + file.filename(), e);
        }
    }

    private static final class LineCollector extends PDFTextStripper {
        final List<Line> lines = new ArrayList<>();
        private final StringBuilder cur = new StringBuilder();
        private float curSize;
        private boolean curBold;

        @Override
        protected void writeString(String string, List<TextPosition> positions) {
            cur.append(string);
            for (TextPosition tp : positions) {
                curSize = Math.max(curSize, tp.getFontSizeInPt());
                var font = tp.getFont();
                String name = font == null || font.getName() == null ? "" : font.getName();
                if (name.toLowerCase().contains("bold")) {
                    curBold = true;
                }
            }
        }

        @Override
        protected void writeLineSeparator() {
            flushLine();
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            flushLine();
            super.endPage(page);
        }

        private void flushLine() {
            // Keep blank lines: the grouper uses them as paragraph separators.
            lines.add(new Line(cur.toString().strip(), curSize, curBold, getCurrentPageNo()));
            cur.setLength(0);
            curSize = 0;
            curBold = false;
        }
    }

    /** Most common font size (rounded to 0.5pt), weighted by text length. */
    private static float bodyFontSize(List<Line> lines) {
        Map<Float, Integer> charsBySize = new HashMap<>();
        for (Line l : lines) {
            if (l.text().isEmpty() || l.fontSize() <= 0) {
                continue;
            }
            float key = Math.round(l.fontSize() * 2) / 2.0f;
            charsBySize.merge(key, l.text().length(), Integer::sum);
        }
        float best = 0;
        int bestChars = -1;
        for (Map.Entry<Float, Integer> e : charsBySize.entrySet()) {
            if (e.getValue() > bestChars) {
                bestChars = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /** Distinct heading font sizes, descending — index = heading level - 1. */
    private List<Float> headingSizeLevels(List<Line> lines, float bodySize) {
        List<Float> sizes = new ArrayList<>();
        for (Line l : lines) {
            if (isHeading(l, bodySize)) {
                float key = Math.round(l.fontSize() * 2) / 2.0f;
                if (!sizes.contains(key)) {
                    sizes.add(key);
                }
            }
        }
        sizes.sort((a, b) -> Float.compare(b, a));
        return sizes;
    }

    private boolean isHeading(Line l, float bodySize) {
        if (l.text().isEmpty() || l.text().length() > 120 || bodySize <= 0) {
            return false;
        }
        if (l.fontSize() >= bodySize * headingFontRatio) {
            return true;
        }
        return l.bold() && l.text().length() <= 60 && l.fontSize() >= bodySize * 0.95f;
    }

    private static boolean looksLikeTableRow(String text) {
        return !text.isEmpty() && TABLE_CELL_SPLIT.split(text).length >= 3;
    }

    private List<Block> groupLines(List<Line> lines, float bodySize, List<Float> headingSizes) {
        List<Block> blocks = new ArrayList<>();
        List<String> headingStack = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Block.BlockType bufType = null;
        int bufPage = -1;

        for (Line l : lines) {
            if (l.text().isEmpty()) {
                flushGroup(blocks, buf, bufType, bufPage, headingStack);
                bufType = null;
                continue;
            }
            if (isHeading(l, bodySize)) {
                flushGroup(blocks, buf, bufType, bufPage, headingStack);
                bufType = null;
                float key = Math.round(l.fontSize() * 2) / 2.0f;
                int level = Math.max(1, headingSizes.indexOf(key) + 1);
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                headingStack.add(l.text());
                blocks.add(new Block(Block.BlockType.HEADING, l.text(), l.page(),
                        List.copyOf(headingStack)));
                continue;
            }
            Block.BlockType lineType = looksLikeTableRow(l.text())
                    ? Block.BlockType.TABLE
                    : Block.BlockType.PARAGRAPH;
            // Start a new group on type change or page change (keeps page
            // tagging exact — fusion joins chunks to pages by this number).
            if (bufType != lineType || bufPage != l.page()) {
                flushGroup(blocks, buf, bufType, bufPage, headingStack);
                bufType = lineType;
                bufPage = l.page();
            }
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(l.text());
        }
        flushGroup(blocks, buf, bufType, bufPage, headingStack);
        return blocks;
    }

    private static void flushGroup(List<Block> blocks, StringBuilder buf,
                                   Block.BlockType type, int page,
                                   List<String> headingStack) {
        if (type == null || buf.length() == 0) {
            buf.setLength(0);
            return;
        }
        String text = buf.toString().strip();
        buf.setLength(0);
        if (!text.isEmpty()) {
            blocks.add(new Block(type, text, page, List.copyOf(headingStack)));
        }
    }
}
