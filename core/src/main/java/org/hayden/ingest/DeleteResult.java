package org.hayden.ingest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Result of deleting a document from a knowledge base — its text chunks and,
 * if present, its visual page index and stored page images.
 *
 * @param backend             backend that handled the delete
 * @param kbName              knowledge base
 * @param docId               document id deleted
 * @param textPointsDeleted   the {@code <kb>} chunk collection existed
 * @param visualPointsDeleted the {@code <kb>_pages} collection existed
 * @param imagesRemoved       number of stored page images removed
 * @param message             human-readable summary
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeleteResult(
        String backend,
        String kbName,
        String docId,
        boolean textPointsDeleted,
        boolean visualPointsDeleted,
        int imagesRemoved,
        String message) {
}
