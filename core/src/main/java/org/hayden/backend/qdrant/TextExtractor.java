package org.hayden.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.ingest.FetchedFile;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.PageText;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class TextExtractor {

    @ConfigProperty(name = "ingest.extract.max-chars", defaultValue = "10000000")
    int maxChars;

    /**
     * Extract plain text from a fetched file as a single concatenated string.
     * Uses Tika's {@link AutoDetectParser}, so PDF / DOCX / HTML / RTF / EPUB /
     * etc. all work without per-format branches.
     */
    public String extract(FetchedFile file) {
        BodyContentHandler handler = new BodyContentHandler(maxChars);
        Metadata metadata = new Metadata();
        if (file.filename() != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.filename());
        }
        if (file.contentType() != null) {
            metadata.set(Metadata.CONTENT_TYPE, file.contentType());
        }

        try (ByteArrayInputStream in = new ByteArrayInputStream(file.content())) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        } catch (IOException | SAXException | TikaException e) {
            throw new IngestException("Failed to extract text from " + file.filename(), e);
        }

        String text = handler.toString();
        if (text == null || text.isBlank()) {
            throw new IngestException("Tika extracted no text from " + file.filename()
                    + " (content-type=" + file.contentType() + ")");
        }
        return text;
    }

    /**
     * Extract text per-page so chunks can be tagged with their source page(s).
     *
     * <p>For PDFs, uses PDFBox directly to extract one page at a time. For all
     * other content types, falls back to {@link #extract(FetchedFile)} and
     * returns a single {@link PageText} at page 1 — i.e., non-PDF documents
     * are treated as conceptually single-page for the purpose of chunk tagging.
     *
     * <p>Throws {@link IngestException} if no text was extractable from any page.
     */
    public List<PageText> extractPerPage(FetchedFile file) {
        if (isPdf(file)) {
            return extractPdfPerPage(file);
        }
        // Non-PDF: single conceptual page.
        return List.of(new PageText(1, extract(file)));
    }

    private static boolean isPdf(FetchedFile file) {
        if (file.contentType() != null
                && file.contentType().toLowerCase().contains("pdf")) {
            return true;
        }
        return file.filename() != null
                && file.filename().toLowerCase().endsWith(".pdf");
    }

    private List<PageText> extractPdfPerPage(FetchedFile file) {
        try (PDDocument doc = Loader.loadPDF(file.content())) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount == 0) {
                throw new IngestException("PDF " + file.filename() + " has 0 pages");
            }
            List<PageText> out = new ArrayList<>(pageCount);
            int extractedBytes = 0;
            for (int i = 1; i <= pageCount; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String pageText = stripper.getText(doc);
                extractedBytes += pageText == null ? 0 : pageText.length();
                if (extractedBytes > maxChars) {
                    // Hard cap matches Tika's BodyContentHandler behavior.
                    throw new IngestException("PDF " + file.filename()
                            + " exceeded ingest.extract.max-chars=" + maxChars
                            + " by page " + i);
                }
                out.add(new PageText(i, pageText == null ? "" : pageText));
            }
            boolean anyContent = out.stream().anyMatch(p -> !p.text().isBlank());
            if (!anyContent) {
                throw new IngestException("PDFBox extracted no text from "
                        + file.filename() + " (likely a scanned PDF without a text layer; "
                        + "ColPali / OCR is required to read it)");
            }
            return out;
        } catch (IOException e) {
            throw new IngestException("Failed to read PDF " + file.filename(), e);
        }
    }
}
