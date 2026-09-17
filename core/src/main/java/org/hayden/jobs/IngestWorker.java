package org.hayden.jobs;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.qdrant.QdrantBackend;
import org.hayden.ingest.JobSourceSnapshots;
import org.hayden.ingest.SidecarUnavailableException;
import org.hayden.ingest.IngestResult;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Background worker pool that drains the {@link IngestQueue}. Each thread
 * polls the queue, calls {@link QdrantBackend#ingestForWorker} for the next
 * job, and records the result (or error) back into the queue.
 *
 * <p>One thread by default ({@code ingest.queue.worker_threads}). More are
 * useful on multi-GPU sidecars or when the sidecar handles concurrent
 * batches well; on a single-GPU host the sidecar typically serializes anyway
 * and extra workers don't help.
 *
 * <p>Shutdown semantics: on {@link PreDestroy} we flip the running flag and
 * interrupt each worker thread, then wait up to 5 seconds for graceful exit.
 * A job that's mid-ingest when shutdown hits will be left in IN_PROGRESS on
 * disk; the next startup will recover it via {@link IngestJob#requeueAfterCrash}.
 */
@ApplicationScoped
@Startup
public class IngestWorker {

    private static final Logger LOG = Logger.getLogger(IngestWorker.class);

    @Inject
    IngestQueue queue;

    @Inject
    QdrantBackend backend;

    @Inject
    JobSourceSnapshots snapshots;

    @ConfigProperty(name = "ingest.queue.worker_threads", defaultValue = "1")
    int workerThreads;

    @ConfigProperty(name = "ingest.queue.poll_timeout_ms", defaultValue = "1000")
    long pollTimeoutMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private List<Thread> threads = List.of();

    @PostConstruct
    void start() {
        sweepSnapshots();
        if (workerThreads <= 0) {
            LOG.info("ingest.queue.worker_threads <= 0; ingest queue will not be drained");
            return;
        }
        running.set(true);
        List<Thread> spawned = new ArrayList<>(workerThreads);
        for (int i = 0; i < workerThreads; i++) {
            Thread t = new Thread(this::workerLoop, "ingest-worker-" + i);
            t.setDaemon(true);
            t.start();
            spawned.add(t);
        }
        threads = spawned;
        LOG.infof("Ingest worker started with %d thread(s)", workerThreads);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        for (Thread t : threads) {
            t.interrupt();
        }
        for (Thread t : threads) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOG.info("Ingest worker stopped");
    }

    /**
     * Drop hardlink snapshots whose job the queue no longer tracks as live
     * (unknown, or already terminal). Live QUEUED/IN_PROGRESS jobs keep
     * theirs — crash recovery re-reads through the link.
     */
    private void sweepSnapshots() {
        if (snapshots == null) {   // hand-wired tests may omit the collaborator
            return;
        }
        snapshots.sweep(queue.listJobs().stream()
                .filter(j -> !j.status().isTerminal())
                .map(IngestJob::jobId)
                .collect(Collectors.toSet()));
    }

    /** Backoff window after a transient dependency failure: doubles from
     *  MIN to MAX, resets on the first non-transient outcome. Without this,
     *  a down sidecar lets a worker fast-fail the whole queue in seconds. */
    static final long TRANSIENT_BACKOFF_MIN_MS = 5_000;
    static final long TRANSIENT_BACKOFF_MAX_MS = 60_000;

    private void workerLoop() {
        long backoffMs = 0;
        while (running.get()) {
            try {
                Optional<IngestJob> next = queue.take(pollTimeoutMs, TimeUnit.MILLISECONDS);
                if (next.isEmpty()) {
                    continue;
                }
                boolean transientFailure = processOne(next.get());
                if (transientFailure) {
                    backoffMs = backoffMs == 0
                            ? TRANSIENT_BACKOFF_MIN_MS
                            : Math.min(backoffMs * 2, TRANSIENT_BACKOFF_MAX_MS);
                    LOG.warnf("Transient dependency failure; worker backing off %dms "
                            + "(job requeued without retry penalty)", backoffMs);
                    Thread.sleep(backoffMs);
                } else {
                    backoffMs = 0;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // Defensive: a single job's RuntimeException shouldn't kill
                // the worker thread.
                LOG.error("Worker loop hit unexpected exception", t);
            }
        }
    }

    /**
     * Process a single dequeued job. Public so tests can drive one iteration
     * synchronously without thread-timing dances. Returns true when the job
     * hit a transient dependency failure (requeued, caller should back off);
     * false on completion or permanent failure.
     */
    public boolean processOne(IngestJob job) {
        try {
            LOG.infof("Worker picking up job=%s kb=%s doc=%s (retry=%d)",
                    job.jobId(), job.request().kbName(), job.docId(), job.retryCount());
            IngestResult result = backend.ingestForWorker(job);
            queue.markCompleted(job.jobId(), result);
            releaseSnapshot(job.jobId());
            LOG.infof("Worker completed job=%s chunks=%d pages=%d",
                    job.jobId(), result.chunkCount(), result.pageCount());
            return false;
        } catch (SidecarUnavailableException e) {
            // Environment problem, not a job problem: back in line, no
            // retry penalty, and the worker slows down until it clears.
            // The snapshot stays — the requeued job will read through it.
            queue.requeueTransient(job.jobId());
            return true;
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (shuttingDown(e)) {
                // stop() interrupted this thread mid-ingest. That is not a
                // job defect: put it back in line so the next start picks it
                // up. Before this, every graceful restart FAILED the jobs in
                // flight ("Interrupted calling ColPali sidecar/Qdrant") — 65
                // jobs lost across a day of restarts on the R530, 2026-09-17.
                LOG.warnf("Worker shutting down; job=%s requeued (%s)", job.jobId(), message);
                queue.requeueTransient(job.jobId());
                return true;
            }
            LOG.warnf("Worker failed job=%s: %s", job.jobId(), message);
            queue.markFailed(job.jobId(), message);
            releaseSnapshot(job.jobId());   // FAILED is terminal here
            return false;
        }
    }

    /**
     * True when the failure is the worker being stopped, not the job: the
     * thread carries the interrupt flag (clients re-set it after catching
     * InterruptedException) or an InterruptedException sits in the cause
     * chain. Deliberately NOT keyed on the running flag: tests drive
     * processOne without start(), and a job that fails for its own reasons
     * during the stop window should still be recorded as failed.
     */
    private boolean shuttingDown(Throwable e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private void releaseSnapshot(String jobId) {
        if (snapshots != null) {
            snapshots.release(jobId);
        }
    }
}
