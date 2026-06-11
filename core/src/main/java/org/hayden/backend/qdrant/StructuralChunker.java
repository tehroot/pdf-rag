package org.hayden.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.ingest.IngestException;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs {@link Block}s into chunks along structural boundaries:
 *
 * <ul>
 *   <li>Consecutive blocks accumulate into a chunk up to
 *       {@code ingest.chunk.size-chars}; a HEADING boundary flushes early once
 *       the buffer is non-trivially full, so chunks tend not to straddle
 *       sections.
 *   <li>LIST and TABLE blocks are never split across chunks — unless a single
 *       block alone exceeds the chunk size, in which case that block falls
 *       back to the sliding-window {@link Chunker}.
 *   <li>Each chunk's embedded text gets a heading-breadcrumb prefix
 *       ("Install &gt; Cooling &gt; Fan curves") capped at
 *       {@code ingest.chunk.breadcrumb-max-chars}; the stored payload text
 *       stays clean (see {@link Chunk#embeddingText()}).
 * </ul>
 *
 * <p>Contract notes: {@code chunk_index} stays a sequential 0-based counter
 * (UUIDv5 point ids and dedup depend on it), page ranges come from the blocks'
 * source pages (fusion's chunk↔page join depends on them), and
 * {@code char_start}/{@code char_end} are monotonic, non-overlapping offsets
 * into the emitted chunk stream — structural chunks share no text, and the
 * result deduper reads these offsets to know that.
 */
@ApplicationScoped
public class StructuralChunker {

    /** Flush at a heading boundary once the buffer is this full. */
    private static final double HEADING_FLUSH_FILL = 0.4;

    private static final String BLOCK_SEPARATOR = "\n\n";

    @ConfigProperty(name = "ingest.chunk.size-chars", defaultValue = "1500")
    int sizeChars;

    @ConfigProperty(name = "ingest.chunk.breadcrumb-max-chars", defaultValue = "120")
    int breadcrumbMaxChars;

    @Inject
    Chunker slidingFallback;

    public List<Chunk> chunkBlocks(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IngestException("Cannot chunk empty block list");
        }
        if (sizeChars <= 0) {
            throw new IngestException("ingest.chunk.size-chars must be > 0");
        }
        Packer packer = new Packer();
        for (Block b : blocks) {
            if (b == null || b.text() == null || b.text().isBlank()) {
                continue;
            }
            if (b.type() == Block.BlockType.HEADING) {
                // Heading text travels in the breadcrumbs of the blocks under
                // it; the heading itself just nudges the packer to flush.
                if (packer.body.length() >= sizeChars * HEADING_FLUSH_FILL) {
                    packer.flush();
                }
                continue;
            }
            String text = b.text().strip();
            if (text.length() > sizeChars) {
                packer.flush();
                packer.emitOversize(b, text);
                continue;
            }
            if (packer.body.length() > 0
                    && packer.body.length() + BLOCK_SEPARATOR.length() + text.length() > sizeChars) {
                packer.flush();
            }
            packer.add(b, text);
        }
        packer.flush();
        if (packer.out.isEmpty()) {
            throw new IngestException("All blocks were empty; nothing to chunk");
        }
        return packer.out;
    }

    /** Accumulates blocks into one pending chunk and emits completed chunks. */
    private final class Packer {
        final List<Chunk> out = new ArrayList<>();
        final StringBuilder body = new StringBuilder();
        List<String> headingPath = List.of();
        int pageStart = 1;
        int pageEnd = 1;
        int nextIndex = 0;
        int offset = 0;

        void add(Block b, String text) {
            if (body.length() == 0) {
                headingPath = b.headingPath();
                pageStart = b.pageNumber();
            } else {
                body.append(BLOCK_SEPARATOR);
            }
            body.append(text);
            pageEnd = b.pageNumber();
        }

        void flush() {
            if (body.length() == 0) {
                return;
            }
            emit(body.toString(), pageStart, pageEnd, headingPath);
            body.setLength(0);
        }

        /** A single block too big for one chunk: sliding-window just that block. */
        void emitOversize(Block b, String text) {
            for (Chunk c : slidingFallback.chunk(text)) {
                emit(c.text(), b.pageNumber(), b.pageNumber(), b.headingPath());
            }
        }

        private void emit(String text, int pStart, int pEnd, List<String> path) {
            out.add(new Chunk(nextIndex++, offset, offset + text.length(), text,
                    pStart, pEnd, embeddingText(path, text),
                    path == null || path.isEmpty() ? null : List.copyOf(path)));
            // +1 keeps consecutive chunks' char ranges disjoint.
            offset += text.length() + 1;
        }
    }

    /**
     * Embedded text = breadcrumb prefix + body; null (no override) when there
     * is no heading context. Truncation drops the outermost (least specific)
     * segments first.
     */
    private String embeddingText(List<String> path, String body) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String crumb = String.join(" > ", path);
        int max = Math.max(breadcrumbMaxChars, 8);
        for (int from = 1; crumb.length() > max && from < path.size(); from++) {
            crumb = "… > " + String.join(" > ", path.subList(from, path.size()));
        }
        if (crumb.length() > max) {
            crumb = "…" + crumb.substring(crumb.length() - max + 1);
        }
        return crumb + "\n" + body;
    }
}
