package org.example.jobs;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.backend.qdrant.QdrantBackend;
import org.example.ingest.IngestResult;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @ConfigProperty(name = "ingest.queue.worker_threads", defaultValue = "1")
    int workerThreads;

    @ConfigProperty(name = "ingest.queue.poll_timeout_ms", defaultValue = "1000")
    long pollTimeoutMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private List<Thread> threads = List.of();

    @PostConstruct
    void start() {
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

    private void workerLoop() {
        while (running.get()) {
            try {
                Optional<IngestJob> next = queue.take(pollTimeoutMs, TimeUnit.MILLISECONDS);
                next.ifPresent(this::processOne);
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
     * synchronously without thread-timing dances.
     */
    public void processOne(IngestJob job) {
        try {
            LOG.infof("Worker picking up job=%s kb=%s doc=%s (retry=%d)",
                    job.jobId(), job.request().kbName(), job.docId(), job.retryCount());
            IngestResult result = backend.ingestForWorker(job);
            queue.markCompleted(job.jobId(), result);
            LOG.infof("Worker completed job=%s chunks=%d pages=%d",
                    job.jobId(), result.chunkCount(), result.pageCount());
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            LOG.warnf("Worker failed job=%s: %s", job.jobId(), message);
            queue.markFailed(job.jobId(), message);
        }
    }
}
