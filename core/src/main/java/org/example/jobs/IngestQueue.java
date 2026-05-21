package org.example.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.ingest.IngestException;
import org.example.ingest.IngestResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Concurrent in-memory queue of ingest jobs with a file-backed shadow for
 * restart recovery. Submit adds to the queue and writes to disk; the worker
 * polls {@link #take(long, TimeUnit)} for the next QUEUED job, atomically
 * marking it IN_PROGRESS. Status transitions persist on every change so a
 * restart can pick up where we left off.
 *
 * <p>At-least-once semantics: at startup, any job left in IN_PROGRESS gets
 * requeued (with retryCount incremented). If a job exceeds
 * {@code ingest.queue.max_retries} it stays FAILED with a clear message.
 *
 * <p>Persistence layout:
 * <pre>
 *   ${ingest.queue.persistence_path}/
 *     &lt;jobId&gt;.json
 *     &lt;jobId&gt;.json
 *     ...
 * </pre>
 *
 * <p>Writes are atomic via tmp file + rename, same pattern as
 * {@code FilesystemPageImageStore}.
 */
@ApplicationScoped
public class IngestQueue {

    @ConfigProperty(name = "ingest.queue.persistence_path")
    String persistencePath;

    @ConfigProperty(name = "ingest.queue.max_retries", defaultValue = "3")
    int maxRetries;

    @Inject
    ObjectMapper objectMapper;

    private Path root;
    private final Map<String, IngestJob> jobs = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<String> pending = new LinkedBlockingQueue<>();

    @PostConstruct
    void init() {
        if (persistencePath == null || persistencePath.isBlank()) {
            throw new IngestException("ingest.queue.persistence_path must be configured");
        }
        this.root = Path.of(persistencePath).toAbsolutePath();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IngestException("Failed to create queue persistence dir " + root, e);
        }
        recoverFromDisk();
    }

    /** Add a fresh job to the queue. Returns the same job for chaining. */
    public IngestJob submit(IngestJob job) {
        if (job == null || job.jobId() == null) {
            throw new IngestException("submit requires a job with a jobId");
        }
        jobs.put(job.jobId(), job);
        persist(job);
        pending.offer(job.jobId());
        return job;
    }

    /**
     * Block (up to {@code timeoutMs}) for the next QUEUED job; atomically mark
     * it IN_PROGRESS before returning. Returns {@code Optional.empty()} on
     * timeout so workers can periodically check shutdown signals.
     */
    public Optional<IngestJob> take(long timeoutMs, TimeUnit unit) throws InterruptedException {
        String jobId = pending.poll(timeoutMs, unit);
        if (jobId == null) {
            return Optional.empty();
        }
        IngestJob current = jobs.get(jobId);
        if (current == null) {
            return Optional.empty();   // dropped out from under us
        }
        IngestJob started = current.withStarted(Instant.now());
        jobs.put(jobId, started);
        persist(started);
        return Optional.of(started);
    }

    public Optional<IngestJob> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public List<IngestJob> listJobs() {
        return List.copyOf(jobs.values());
    }

    public void markCompleted(String jobId, IngestResult result) {
        update(jobId, job -> job.withCompleted(Instant.now(), result));
    }

    public void markFailed(String jobId, String errorMessage) {
        update(jobId, job -> {
            if (job.retryCount() >= maxRetries) {
                // Cap reached — leave it FAILED with a clear marker.
                return job.withFailed(Instant.now(),
                        "Retry cap (" + maxRetries + ") reached. Last error: " + errorMessage);
            }
            return job.withFailed(Instant.now(), errorMessage);
        });
    }

    /** Number of jobs awaiting a worker. Useful for backpressure and tests. */
    public int pendingCount() {
        return pending.size();
    }

    /** Total jobs known to the queue (queued + in-progress + completed + failed). */
    public int totalCount() {
        return jobs.size();
    }

    private void update(String jobId, java.util.function.UnaryOperator<IngestJob> fn) {
        IngestJob current = jobs.get(jobId);
        if (current == null) {
            throw new IngestException("Unknown jobId: " + jobId);
        }
        IngestJob updated = fn.apply(current);
        jobs.put(jobId, updated);
        persist(updated);
    }

    private void recoverFromDisk() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> files = Files.list(root)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(this::loadOne);
        } catch (IOException e) {
            throw new IngestException("Failed to scan queue persistence dir " + root, e);
        }
    }

    private void loadOne(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            IngestJob job = objectMapper.readValue(bytes, IngestJob.class);
            // Recovery rule: any job that was IN_PROGRESS when the process died
            // is treated as crashed; requeue with retryCount++.
            if (job.status() == JobStatus.IN_PROGRESS) {
                if (job.retryCount() >= maxRetries) {
                    job = job.withFailed(Instant.now(),
                            "Worker crashed mid-ingest; retry cap (" + maxRetries + ") reached");
                    persist(job);
                } else {
                    job = job.requeueAfterCrash();
                    persist(job);
                    pending.offer(job.jobId());
                }
            } else if (job.status() == JobStatus.QUEUED) {
                pending.offer(job.jobId());
            }
            jobs.put(job.jobId(), job);
        } catch (IOException e) {
            // Don't blow up the entire queue if one file is corrupt; log a marker
            // by writing a sibling .err file so an operator can investigate.
            try {
                Files.writeString(
                        file.resolveSibling(file.getFileName() + ".err"),
                        "Failed to parse: " + e.getMessage());
            } catch (IOException ignored) {}
        }
    }

    private void persist(IngestJob job) {
        Path target = root.resolve(job.jobId() + ".json");
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(),
                    target.getFileName().toString() + ".", ".tmp");
            try {
                Files.write(tmp, objectMapper.writeValueAsBytes(job));
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IngestException("Failed to persist job " + job.jobId(), e);
        }
    }
}
