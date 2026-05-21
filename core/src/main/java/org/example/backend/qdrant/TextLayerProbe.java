package org.example.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.ingest.FetchedFile;
import org.example.ingest.IngestException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-page text-layer classifier. Runs a fast PDFBox text-stripping pass (no
 * OCR, no rendering) and classifies each page's text quality on a 0-2 scale:
 *
 * <ul>
 *   <li>{@code 0} — no usable text layer (e.g. scanned image). Char count is
 *       below {@code ingest.text_quality.threshold_low}.
 *   <li>{@code 1} — partial text layer (likely image-heavy with sparse
 *       captions, or a hybrid scan with OCR layer of variable quality). Char
 *       count between {@code threshold_low} and {@code threshold_full}.
 *   <li>{@code 2} — full text layer (born-digital page). Char count meets or
 *       exceeds {@code ingest.text_quality.threshold_full}.
 * </ul>
 *
 * <p>The probe is intentionally separate from {@link TextExtractor}. Extraction
 * is a heavier operation that retains the text for chunking; the probe is a
 * cheap classification step that drives gating decisions (skip the visual
 * pipeline on full-text-layer pages; weight fusion confidence by per-page
 * text quality at query time).
 *
 * <p>Non-PDF inputs are reported as a single page with {@code textQuality=2}
 * (text-bearing formats like DOCX/HTML inherently have a text layer).
 */
@ApplicationScoped
public class TextLayerProbe {

    @ConfigProperty(name = "ingest.text_quality.threshold_low", defaultValue = "50")
    int thresholdLow;

    @ConfigProperty(name = "ingest.text_quality.threshold_full", defaultValue = "500")
    int thresholdFull;

    public List<PageQuality> probe(FetchedFile file) {
        validateThresholds();
        if (!isPdf(file)) {
            // Non-PDF formats are inherently text-bearing once Tika extracts them.
            return List.of(new PageQuality(1, file.content() == null ? 0 : file.content().length, 2));
        }
        try (PDDocument doc = Loader.loadPDF(file.content())) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount == 0) {
                throw new IngestException("PDF " + file.filename() + " has 0 pages");
            }
            List<PageQuality> out = new ArrayList<>(pageCount);
            for (int i = 1; i <= pageCount; i++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                String text = stripper.getText(doc);
                int charCount = text == null ? 0 : text.length();
                out.add(new PageQuality(i, charCount, classify(charCount)));
            }
            return out;
        } catch (IOException e) {
            throw new IngestException("Failed to probe text layer of " + file.filename(), e);
        }
    }

    /** Classify a single char-count value using the configured thresholds. */
    public int classify(int charCount) {
        validateThresholds();
        if (charCount < thresholdLow) return 0;
        if (charCount < thresholdFull) return 1;
        return 2;
    }

    private void validateThresholds() {
        if (thresholdLow < 0 || thresholdFull < 0) {
            throw new IngestException("text_quality thresholds must be non-negative");
        }
        if (thresholdLow > thresholdFull) {
            throw new IngestException(
                    "ingest.text_quality.threshold_low (" + thresholdLow
                            + ") must be <= threshold_full (" + thresholdFull + ")");
        }
    }

    private static boolean isPdf(FetchedFile file) {
        if (file.contentType() != null
                && file.contentType().toLowerCase().contains("pdf")) {
            return true;
        }
        return file.filename() != null
                && file.filename().toLowerCase().endsWith(".pdf");
    }

    /**
     * Per-page quality classification result. {@code charCount} is the raw
     * extracted character count from PDFBox; {@code textQuality} is the
     * bucket (0/1/2). Together they let downstream code apply additional
     * heuristics (e.g. weight by charCount as well as bucket).
     */
    public record PageQuality(int pageNumber, int charCount, int textQuality) {
    }
}
