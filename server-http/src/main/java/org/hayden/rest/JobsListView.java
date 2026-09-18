package org.hayden.rest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Response body for {@code GET /ingest/jobs}. {@code total} / {@code pending}
 * describe the whole queue regardless of any status filter; {@code returned}
 * is the size of the (possibly filtered) {@code jobs} list.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JobsListView(
        int total,
        int pending,
        int returned,
        List<JobStatusView> jobs) {
}
