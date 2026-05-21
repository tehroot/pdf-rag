package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.example.backend.qdrant.QdrantBackend;
import org.example.ingest.IngestException;
import org.example.ingest.IngestRequest;
import org.example.ingest.IngestRequest.SourceType;
import org.example.ingest.IngestResult;
import org.example.jobs.IngestJob;
import org.example.jobs.IngestQueue;
import org.example.jobs.IngestWorker;
import org.example.jobs.JobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class IngestWorkerTest {

    private Path tmpRoot;
    private IngestQueue queue;
    private FakeBackend fake;
    private IngestWorker worker;

    @BeforeEach
    void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("worker-test-");
        queue = newQueue(tmpRoot.toString());
        fake = new FakeBackend();
        worker = new IngestWorker();
        setField(worker, "queue", queue);
        setField(worker, "backend", fake);
        setField(worker, "workerThreads", 0);   // disable thread startup; tests use processOne directly
        setField(worker, "pollTimeoutMs", 50L);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpRoot != null && Files.exists(tmpRoot)) {
            try (Stream<Path> walk = Files.walk(tmpRoot)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        }
    }

    @Test
    void processOne_success_marksCompleted() {
        IngestJob job = queue.submit(IngestJob.queued(req("kb"), "doc-1"));
        // Manually take so the job's state is IN_PROGRESS before processOne.
        IngestJob taken = takeOrFail();

        fake.nextResult = new IngestResult("qdrant", "kb", "kb", "doc-1",
                "completed", 12, 5, true, "done", List.of(), null);
        worker.processOne(taken);

        IngestJob done = queue.getJob(job.jobId()).orElseThrow();
        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.result()).isNotNull();
        assertThat(done.result().chunkCount()).isEqualTo(12);
        assertThat(done.result().pageCount()).isEqualTo(5);
        assertThat(fake.calls.get()).isEqualTo(1);
    }

    @Test
    void processOne_throws_marksFailed() {
        IngestJob job = queue.submit(IngestJob.queued(req("kb"), "doc-1"));
        IngestJob taken = takeOrFail();

        fake.nextException = new IngestException("sidecar exploded");
        worker.processOne(taken);

        IngestJob failed = queue.getJob(job.jobId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.error()).contains("sidecar exploded");
    }

    @Test
    void processOne_runtimeException_marksFailed() {
        IngestJob job = queue.submit(IngestJob.queued(req("kb"), "doc-1"));
        IngestJob taken = takeOrFail();

        fake.nextException = new RuntimeException("disk full");
        worker.processOne(taken);

        IngestJob failed = queue.getJob(job.jobId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.error()).contains("disk full");
    }

    @Test
    void processOne_nullErrorMessage_recordsClassName() {
        IngestJob job = queue.submit(IngestJob.queued(req("kb"), "doc-1"));
        IngestJob taken = takeOrFail();

        // Exception with null message — worker should record the class name.
        fake.nextException = new RuntimeException((String) null);
        worker.processOne(taken);

        IngestJob failed = queue.getJob(job.jobId()).orElseThrow();
        assertThat(failed.error()).isEqualTo("RuntimeException");
    }

    @Test
    void workerLoop_pollAndExit_observesShutdownFlag() throws Exception {
        // Bring up an actual worker thread with workerThreads=1; verify it
        // exits cleanly when stop() is called.
        setField(worker, "workerThreads", 1);
        invokeMethod(worker, "start");

        // Give it a moment to enter the poll loop.
        Thread.sleep(100);

        invokeMethod(worker, "stop");

        // After stop(), no more processing should happen on submitted jobs.
        queue.submit(IngestJob.queued(req("kb"), "doc-after-stop"));
        Thread.sleep(200);   // worker is stopped; nothing should pick this up
        IngestJob still = queue.listJobs().stream()
                .filter(j -> "doc-after-stop".equals(j.docId()))
                .findFirst().orElseThrow();
        assertThat(still.status()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void workerLoop_processesQueuedJobs_endToEnd() throws Exception {
        setField(worker, "workerThreads", 1);
        invokeMethod(worker, "start");
        try {
            fake.nextResult = new IngestResult("qdrant", "kb", "kb", "doc-async",
                    "completed", 7, 0, true, "done", List.of(), null);
            IngestJob submitted = queue.submit(IngestJob.queued(req("kb"), "doc-async"));

            // Poll for completion — up to 2 seconds.
            long deadline = System.currentTimeMillis() + 2000;
            IngestJob seen = null;
            while (System.currentTimeMillis() < deadline) {
                seen = queue.getJob(submitted.jobId()).orElse(null);
                if (seen != null && seen.status().isTerminal()) break;
                Thread.sleep(25);
            }
            assertThat(seen).isNotNull();
            assertThat(seen.status()).isEqualTo(JobStatus.COMPLETED);
            assertThat(seen.result().chunkCount()).isEqualTo(7);
        } finally {
            invokeMethod(worker, "stop");
        }
    }

    // ---- helpers ------------------------------------------------------------

    private IngestJob takeOrFail() {
        try {
            return queue.take(100, TimeUnit.MILLISECONDS).orElseThrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static IngestRequest req(String kb) {
        return new IngestRequest(SourceType.PATH, "/tmp/x.pdf", "x.pdf",
                kb, null, 0L, "qdrant", null, true);
    }

    private static IngestQueue newQueue(String root) throws Exception {
        IngestQueue q = new IngestQueue();
        setField(q, "persistencePath", root);
        setField(q, "maxRetries", 3);
        setField(q, "objectMapper", new ObjectMapper().registerModule(new JavaTimeModule()));
        var init = IngestQueue.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(q);
        return q;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void invokeMethod(Object target, String name) throws Exception {
        var m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(target);
    }

    /** QdrantBackend stand-in for worker tests. */
    static class FakeBackend extends QdrantBackend {
        IngestResult nextResult;
        RuntimeException nextException;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<IngestJob> lastJob = new AtomicReference<>();

        @Override
        public IngestResult ingestForWorker(IngestJob job) {
            calls.incrementAndGet();
            lastJob.set(job);
            if (nextException != null) throw nextException;
            return nextResult;
        }
    }
}
