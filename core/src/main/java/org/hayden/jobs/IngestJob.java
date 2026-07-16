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
 *
 * <p>{@code kind} distinguishes visual-only jobs (the normal case since the
 * visual split — text runs synchronously at submit) from legacy full jobs.
 * Jobs persisted before the field existed deserialize with {@code kind == null};
 * always read it through {@link #effectiveKind()}.
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
        int retryCount,
        JobKind kind) {

    /** Build a fresh, queued legacy (text + visual) job from an incoming request. */
    public static IngestJob queued(IngestRequest request, String docId) {
        return queued(request, docId, JobKind.FULL);
    }

    /** Build a fresh, queued visual-only job (text side already ingested at submit). */
    public static IngestJob queuedVisual(IngestRequest request, String docId) {
        return queued(request, docId, JobKind.VISUAL);
    }

    private static IngestJob queued(IngestRequest request, String docId, JobKind kind) {
        return new IngestJob(
                UUID.randomUUID().toString(),
                JobStatus.QUEUED,
                request,
                docId,
                Instant.now(),
                null, null, null, null,
                List.of(),
                0,
                kind);
    }

    /** Null-safe kind: jobs persisted before the field existed are FULL. */
    public JobKind effectiveKind() {
        return kind == null ? JobKind.FULL : kind;
    }

    public IngestJob withStatus(JobStatus newStatus) {
        return new IngestJob(jobId, newStatus, request, docId, submittedAt,
                startedAt, completedAt, result, error, warnings, retryCount, kind);
    }

    public IngestJob withStarted(Instant now) {
        return new IngestJob(jobId, JobStatus.IN_PROGRESS, request, docId,
                submittedAt, now, completedAt, result, error, warnings, retryCount, kind);
    }

    public IngestJob withCompleted(Instant now, IngestResult finalResult) {
        return new IngestJob(jobId, JobStatus.COMPLETED, request, docId,
                submittedAt, startedAt, now, finalResult, null,
                finalResult == null ? List.of() : finalResult.warnings(),
                retryCount, kind);
    }

    public IngestJob withFailed(Instant now, String errorMessage) {
        return new IngestJob(jobId, JobStatus.FAILED, request, docId,
                submittedAt, startedAt, now, result, errorMessage, warnings,
                retryCount, kind);
    }

    /**
     * Used at worker startup to recover jobs that were in-progress when the
     * process died. The retry counter increments so we can cap retries.
     */
    public IngestJob requeueAfterCrash() {
        return new IngestJob(jobId, JobStatus.QUEUED, request, docId,
                submittedAt, null, null, result, error, warnings,
                retryCount + 1, kind);
    }
}
