package org.hayden.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.PageText;

import java.util.ArrayList;
import java.util.List;

/**
 * Sliding-window chunker with overlap and structural break preference. For each
 * window of length {@code sizeChars}, the actual break point is pulled back to
 * the nearest paragraph / sentence / word boundary within the latter half of
 * the window, so chunks don't end mid-word when the text allows.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #chunk(String)} — chunks a single blob of text; each output chunk
 *       carries {@code pageStart = pageEnd = 1}.
 *   <li>{@link #chunkPerPage(List)} — chunks across a list of per-page text
 *       blobs and tags each output chunk with the source page range it spans.
 *       Chunks that straddle a page boundary will have {@code pageEnd > pageStart}.
 * </ul>
 */
@ApplicationScoped
public class Chunker {

    /** Inserted between adjacent pages when concatenating. Mirrors a paragraph break. */
    private static final String PAGE_SEPARATOR = "\n\n";

    @ConfigProperty(name = "ingest.chunk.size-chars", defaultValue = "1500")
    int sizeChars;

    @ConfigProperty(name = "ingest.chunk.overlap-chars", defaultValue = "200")
    int overlapChars;

    public List<Chunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            throw new IngestException("Cannot chunk empty text");
        }
        validateConfig();
        String norm = normalize(text);
        return chunkNormalized(norm, List.of());
    }

    /**
     * Chunk text spread across multiple pages, tagging each chunk with the source
     * page(s) it spans. Pages with empty/blank text are silently skipped (they
     * produce no chunks). If all pages are empty, throws {@link IngestException}.
     */
    public List<Chunk> chunkPerPage(List<PageText> pages) {
        if (pages == null || pages.isEmpty()) {
            throw new IngestException("Cannot chunk empty page list");
        }
        validateConfig();

        StringBuilder concat = new StringBuilder();
        List<PageRange> pageRanges = new ArrayList<>(pages.size());
        boolean first = true;
        for (PageText page : pages) {
            if (page == null || page.text() == null || page.text().isBlank()) {
                continue;
            }
            String norm = normalize(page.text());
            if (norm.isEmpty()) {
                continue;
            }
            if (!first) {
                concat.append(PAGE_SEPARATOR);
            }
            int start = concat.length();
            concat.append(norm);
            int end = concat.length();
            pageRanges.add(new PageRange(page.pageNumber(), start, end));
            first = false;
        }
        if (pageRanges.isEmpty()) {
            throw new IngestException("All pages were empty; nothing to chunk");
        }
        return chunkNormalized(concat.toString(), pageRanges);
    }

    /**
     * The shared chunking core. Operates on already-normalized text. If
     * {@code pageRanges} is non-empty, each emitted chunk is tagged with the
     * page range it overlaps; otherwise chunks get the (1, 1) default.
     */
    private List<Chunk> chunkNormalized(String norm, List<PageRange> pageRanges) {
        if (norm.length() <= sizeChars) {
            return List.of(buildChunk(0, 0, norm.length(), norm, pageRanges));
        }

        List<Chunk> out = new ArrayList<>();
        int idx = 0;
        int start = 0;
        while (start < norm.length()) {
            int windowEnd = Math.min(start + sizeChars, norm.length());
            int end = windowEnd == norm.length()
                    ? windowEnd
                    : preferredBreak(norm, start, windowEnd);
            out.add(buildChunk(idx++, start, end, norm.substring(start, end), pageRanges));
            if (end >= norm.length()) {
                break;
            }
            int next = end - overlapChars;
            // forward-progress guard: if a tiny chunk + big overlap would loop, skip ahead.
            start = next <= start ? end : next;
        }
        return out;
    }

    private static Chunk buildChunk(int index, int startOffset, int endOffset,
                                    String text, List<PageRange> pageRanges) {
        if (pageRanges.isEmpty()) {
            return new Chunk(index, startOffset, endOffset, text);
        }
        int pageStart = -1;
        int pageEnd = -1;
        for (PageRange pr : pageRanges) {
            // Half-open intervals on both sides: page range is [pr.charStart, pr.charEnd),
            // chunk is [startOffset, endOffset). They overlap iff the ranges intersect.
            if (pr.charEnd() > startOffset && pr.charStart() < endOffset) {
                if (pageStart == -1) {
                    pageStart = pr.pageNumber();
                }
                pageEnd = pr.pageNumber();
            }
        }
        // Defensive: if a chunk somehow falls entirely in a page-separator region
        // (shouldn't happen given the chunker's break preferences), inherit the
        // nearest preceding page.
        if (pageStart == -1) {
            PageRange before = null;
            for (PageRange pr : pageRanges) {
                if (pr.charEnd() <= startOffset) {
                    before = pr;
                }
            }
            int fallback = before != null ? before.pageNumber() : pageRanges.get(0).pageNumber();
            pageStart = fallback;
            pageEnd = fallback;
        }
        return new Chunk(index, startOffset, endOffset, text, pageStart, pageEnd);
    }

    private void validateConfig() {
        if (sizeChars <= 0) {
            throw new IngestException("ingest.chunk.size-chars must be > 0");
        }
        if (overlapChars < 0 || overlapChars >= sizeChars) {
            throw new IngestException(
                    "ingest.chunk.overlap-chars must satisfy 0 <= overlap < size (was "
                            + overlapChars + " vs " + sizeChars + ")");
        }
    }

    private static String normalize(String text) {
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * Pull {@code end} back to the latest paragraph / sentence / word boundary in
     * the second half of the window. Never shrinks the chunk by more than half.
     */
    private static int preferredBreak(String s, int start, int end) {
        int floor = start + (end - start) / 2;
        int rel = lastIndexOf(s, "\n\n", floor, end);
        if (rel >= 0) return rel + 2;
        rel = lastIndexOf(s, ". ", floor, end);
        if (rel >= 0) return rel + 2;
        rel = s.lastIndexOf('\n', end - 1);
        if (rel >= floor) return rel + 1;
        rel = s.lastIndexOf(' ', end - 1);
        if (rel >= floor) return rel + 1;
        return end;
    }

    private static int lastIndexOf(String s, String needle, int floor, int end) {
        int from = end - needle.length();
        while (from >= floor) {
            if (s.regionMatches(from, needle, 0, needle.length())) {
                return from;
            }
            from--;
        }
        return -1;
    }

    /** Closed-open character range in the concatenated text for one source page. */
    private record PageRange(int pageNumber, int charStart, int charEnd) {
    }
}
