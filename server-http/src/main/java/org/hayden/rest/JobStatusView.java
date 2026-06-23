package org.hayden.rest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.hayden.ingest.IngestResult;
import org.hayden.jobs.IngestJob;

import java.util.Locale;

/**
 * Compact, serialization-friendly view of an {@link IngestJob} for the
 * {@code GET /ingest/status/{jobId}} endpoint — the queue's persisted job
 * carries the full request, which we don't echo back.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JobStatusView(
        String jobId,
        String status,
        String docId,
        Integer chunkCount,
        Integer pageCount,
        String message,
        String error,
        int retryCount) {

    static JobStatusView of(IngestJob job) {
        IngestResult r = job.result();
        return new JobStatusView(
                job.jobId(),
                job.status().name().toLowerCase(Locale.ROOT),
                job.docId(),
                r == null ? null : r.chunkCount(),
                r == null ? null : r.pageCount(),
                r == null ? null : r.message(),
                job.error(),
                job.retryCount());
    }
}
