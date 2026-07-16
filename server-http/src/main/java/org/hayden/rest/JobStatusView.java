package org.hayden.rest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.hayden.ingest.IngestResult;
import org.hayden.jobs.IngestJob;

import java.time.Instant;
import java.util.Locale;

/**
 * Compact, serialization-friendly view of an {@link IngestJob} for the
 * {@code GET /ingest/status/{jobId}} and {@code GET /ingest/jobs} endpoints —
 * the queue's persisted job carries the full request, which we don't echo
 * back (kb_name and filename are the useful operator-facing bits).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JobStatusView(
        String jobId,
        String status,
        String kind,
        String kbName,
        String filename,
        String docId,
        Integer chunkCount,
        Integer pageCount,
        String message,
        String error,
        int retryCount,
        Instant submittedAt) {

    static JobStatusView of(IngestJob job) {
        IngestResult r = job.result();
        return new JobStatusView(
                job.jobId(),
                job.status().name().toLowerCase(Locale.ROOT),
                job.effectiveKind().name().toLowerCase(Locale.ROOT),
                job.request() == null ? null : job.request().kbName(),
                job.request() == null ? null : job.request().filename(),
                job.docId(),
                r == null ? null : r.chunkCount(),
                r == null ? null : r.pageCount(),
                r == null ? null : r.message(),
                job.error(),
                job.retryCount(),
                job.submittedAt());
    }
}
