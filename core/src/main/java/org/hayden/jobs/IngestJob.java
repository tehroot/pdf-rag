package org.hayden.jobs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A queued or completed ingest job. Persisted to disk so the worker can pick
 * up where it left off after a restart. Returned from {@code get_ingest_status}
 * so agents can poll for completion.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IngestJob(
        String jobId,
        JobStatus status,
        IngestRequest request,
        String docId,
        Instant submittedAt,
        Instant startedAt,
        Instant completedAt,
        IngestResult result,
        String error,
        List<String> warnings,
        int retryCount) {

    /** Build a fresh, queued job from an incoming request. */
    public static IngestJob queued(IngestRequest request, String docId) {
        return new IngestJob(
                UUID.randomUUID().toString(),
                JobStatus.QUEUED,
                request,
                docId,
                Instant.now(),
                null, null, null, null,
                List.of(),
                0);
    }

    public IngestJob withStatus(JobStatus newStatus) {
        return new IngestJob(jobId, newStatus, request, docId, submittedAt,
                startedAt, completedAt, result, error, warnings, retryCount);
    }

    public IngestJob withStarted(Instant now) {
        return new IngestJob(jobId, JobStatus.IN_PROGRESS, request, docId,
                submittedAt, now, completedAt, result, error, warnings, retryCount);
    }

    public IngestJob withCompleted(Instant now, IngestResult finalResult) {
        return new IngestJob(jobId, JobStatus.COMPLETED, request, docId,
                submittedAt, startedAt, now, finalResult, null,
                finalResult == null ? List.of() : finalResult.warnings(),
                retryCount);
    }

    public IngestJob withFailed(Instant now, String errorMessage) {
        return new IngestJob(jobId, JobStatus.FAILED, request, docId,
                submittedAt, startedAt, now, result, errorMessage, warnings, retryCount);
    }

    /**
     * Used at worker startup to recover jobs that were in-progress when the
     * process died. The retry counter increments so we can cap retries.
     */
    public IngestJob requeueAfterCrash() {
        return new IngestJob(jobId, JobStatus.QUEUED, request, docId,
                submittedAt, null, null, result, error, warnings,
                retryCount + 1);
    }
}
