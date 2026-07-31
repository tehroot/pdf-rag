package org.hayden.ingest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Result of deleting an entire knowledge base: its text collection, its
 * visual page collection + stored page images (if present), and any queued
 * ingest jobs that would otherwise resurrect a stub collection mid-teardown.
 *
 * @param backend                backend that handled the delete
 * @param kbName                 knowledge base deleted
 * @param textCollectionDropped  the {@code <kb>} chunk collection existed
 * @param visualCollectionDropped the {@code <kb>_pages} collection existed
 * @param imagesRemoved          stored page images removed
 * @param jobsCancelled          pending queue jobs cancelled for this KB
 * @param message                human-readable summary
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KbDeleteResult(
        String backend,
        String kbName,
        boolean textCollectionDropped,
        boolean visualCollectionDropped,
        int imagesRemoved,
        int jobsCancelled,
        String message) {
}
