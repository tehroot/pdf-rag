package org.example.ingest;

/**
 * Result of the {@code drop_visual_index} admin tool.
 *
 * @param kbName                    the KB whose visual index was dropped
 * @param qdrantCollectionDropped   true iff a {@code <kb>_pages} Qdrant collection existed and was deleted
 * @param imageFilesRemoved         count of page-image PNGs removed from {@link org.example.backend.qdrant.PageImageStore}
 * @param message                   human-readable summary
 */
public record DropVisualIndexResult(
        String kbName,
        boolean qdrantCollectionDropped,
        int imageFilesRemoved,
        String message) {
}
