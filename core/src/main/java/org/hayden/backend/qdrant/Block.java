package org.hayden.backend.qdrant;

import java.util.List;

/**
 * One structural unit of an extracted document: a heading, a paragraph, a
 * whole list, or a whole table. Produced by {@link StructuredExtractor},
 * consumed by {@link StructuralChunker}, which packs blocks into chunks
 * without ever splitting a LIST or TABLE block across chunk boundaries.
 *
 * @param type        structural role of this block
 * @param text        the block's text (lists/tables are flattened: one item
 *                    or row per line)
 * @param pageNumber  1-based source page (always 1 for non-PDF formats)
 * @param headingPath section ancestry at this point in the document,
 *                    outermost heading first; empty when no heading has been
 *                    seen yet. For HEADING blocks, includes the heading itself.
 */
public record Block(BlockType type, String text, int pageNumber, List<String> headingPath) {

    public enum BlockType { HEADING, PARAGRAPH, LIST, TABLE, OTHER }
}
