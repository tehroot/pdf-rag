package org.hayden.jobs;

/**
 * Lifecycle states for an async ingest job.
 *
 * <pre>
 *   ┌──────┐  submit  ┌────────┐  worker pick  ┌────────────┐  finish  ┌────────────┐
 *   │ new  │ ───────► │ queued │ ──────────────► │ in_progress │ ────────► │ completed  │
 *   └──────┘          └────────┘                 └─────┬──────┘           └────────────┘
 *                          ▲                           │ error
 *                          │ restart-recovery          ▼
 *                          │                     ┌────────────┐
 *                          └─────── retry ────── │   failed   │
 *                                                └────────────┘
 * </pre>
 *
 * <p>On worker startup, any job left in {@code IN_PROGRESS} (because the
 * process crashed mid-ingest) is reset to {@code QUEUED} with an incremented
 * retry counter — at-least-once semantics.
 */
public enum JobStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
