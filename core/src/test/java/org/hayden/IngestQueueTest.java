package org.hayden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestRequest.SourceType;
import org.hayden.ingest.IngestResult;
import org.hayden.jobs.IngestJob;
import org.hayden.jobs.IngestQueue;
import org.hayden.jobs.JobStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestQueueTest {

    private Path tmpRoot;
    private IngestQueue queue;

    @BeforeEach
    void setUp() throws Exception {
        tmpRoot = Files.createTempDirectory("queue-test-");
        queue = newQueue(tmpRoot.toString(), 3);
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
    void submit_persistsToDisk() throws Exception {
        IngestJob job = IngestJob.queued(sampleRequest("kb-1"), "doc-1");
        queue.submit(job);

        Path persistedFile = tmpRoot.resolve(job.jobId() + ".json");
        assertThat(Files.exists(persistedFile)).isTrue();
        assertThat(queue.totalCount()).isEqualTo(1);
        assertThat(queue.pendingCount()).isEqualTo(1);
    }

    @Test
    void take_returnsSubmittedJob_andMarksInProgress() throws Exception {
        IngestJob job = IngestJob.queued(sampleRequest("kb-1"), "doc-1");
        queue.submit(job);

        Optional<IngestJob> taken = queue.take(100, TimeUnit.MILLISECONDS);

        assertThat(taken).isPresent();
        assertThat(taken.get().jobId()).isEqualTo(job.jobId());
        assertThat(taken.get().status()).isEqualTo(JobStatus.IN_PROGRESS);
        assertThat(taken.get().startedAt()).isNotNull();

        // State on disk reflects IN_PROGRESS too.
        IngestJob fromQueue = queue.getJob(job.jobId()).orElseThrow();
        assertThat(fromQueue.status()).isEqualTo(JobStatus.IN_PROGRESS);
    }

    @Test
    void take_emptyQueue_returnsEmptyAfterTimeout() throws Exception {
        Optional<IngestJob> taken = queue.take(50, TimeUnit.MILLISECONDS);
        assertThat(taken).isEmpty();
    }

    @Test
    void take_returnsJobsInFifoOrder() throws Exception {
        IngestJob a = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-a"));
        IngestJob b = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-b"));
        IngestJob c = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-c"));

        IngestJob first = queue.take(100, TimeUnit.MILLISECONDS).orElseThrow();
        IngestJob second = queue.take(100, TimeUnit.MILLISECONDS).orElseThrow();
        IngestJob third = queue.take(100, TimeUnit.MILLISECONDS).orElseThrow();

        assertThat(first.jobId()).isEqualTo(a.jobId());
        assertThat(second.jobId()).isEqualTo(b.jobId());
        assertThat(third.jobId()).isEqualTo(c.jobId());
    }

    @Test
    void markCompleted_transitionsAndAttachesResult() throws Exception {
        IngestJob job = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-1"));
        queue.take(100, TimeUnit.MILLISECONDS);

        IngestResult result = new IngestResult("qdrant", "kb", "kb", "doc-1",
                "completed", 5, 3, true, "done", List.of(), null);
        queue.markCompleted(job.jobId(), result);

        IngestJob done = queue.getJob(job.jobId()).orElseThrow();
        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.completedAt()).isNotNull();
        assertThat(done.result()).isNotNull();
        assertThat(done.result().chunkCount()).isEqualTo(5);
        assertThat(done.result().pageCount()).isEqualTo(3);
    }

    @Test
    void markFailed_transitionsAndAttachesError() throws Exception {
        IngestJob job = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-1"));
        queue.take(100, TimeUnit.MILLISECONDS);

        queue.markFailed(job.jobId(), "sidecar exploded");

        IngestJob failed = queue.getJob(job.jobId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.error()).isEqualTo("sidecar exploded");
    }

    @Test
    void markFailed_atRetryCap_recordsCapMarker() throws Exception {
        IngestQueue strict = newQueue(tmpRoot.toString(), 0);  // cap = 0; first failure is final
        IngestJob job = strict.submit(IngestJob.queued(sampleRequest("kb"), "doc-1"));
        strict.take(100, TimeUnit.MILLISECONDS);

        strict.markFailed(job.jobId(), "transient blip");

        IngestJob failed = strict.getJob(job.jobId()).orElseThrow();
        assertThat(failed.error()).contains("Retry cap (0)");
        assertThat(failed.error()).contains("transient blip");
    }

    @Test
    void markCompleted_unknownJobId_throws() {
        assertThatThrownBy(() -> queue.markCompleted("nope", null))
                .hasMessageContaining("Unknown jobId");
    }

    @Test
    void getJob_unknownJobId_returnsEmpty() {
        assertThat(queue.getJob("nope")).isEmpty();
    }

    @Test
    void listJobs_returnsAllStates() throws Exception {
        IngestJob a = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-a"));
        IngestJob b = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-b"));
        queue.take(100, TimeUnit.MILLISECONDS);   // a → in_progress
        queue.markCompleted(a.jobId(), new IngestResult("qdrant", "kb", "kb",
                "doc-a", "completed", 1, 0, true, "ok", List.of(), null));

        List<IngestJob> all = queue.listJobs();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(IngestJob::status)
                .containsExactlyInAnyOrder(JobStatus.COMPLETED, JobStatus.QUEUED);
    }

    @Test
    void restart_recoversQueuedJobs() throws Exception {
        queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-1"));
        queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-2"));

        // Simulate restart by constructing a new queue against the same dir.
        IngestQueue restarted = newQueue(tmpRoot.toString(), 3);
        assertThat(restarted.totalCount()).isEqualTo(2);
        assertThat(restarted.pendingCount()).isEqualTo(2);
    }

    @Test
    void restart_requeuesInProgressJobs() throws Exception {
        IngestJob job = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-1"));
        queue.take(100, TimeUnit.MILLISECONDS);    // → IN_PROGRESS

        // Simulate the worker dying mid-ingest. A new IngestQueue picks up
        // and should see this as a queued job with retryCount=1.
        IngestQueue restarted = newQueue(tmpRoot.toString(), 3);
        IngestJob recovered = restarted.getJob(job.jobId()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(recovered.retryCount()).isEqualTo(1);
        assertThat(restarted.pendingCount()).isEqualTo(1);
    }

    @Test
    void restart_doesNotRequeueCompletedOrFailedJobs() throws Exception {
        IngestJob a = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-a"));
        IngestJob b = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-b"));
        queue.take(100, TimeUnit.MILLISECONDS);
        queue.take(100, TimeUnit.MILLISECONDS);
        queue.markCompleted(a.jobId(), new IngestResult("qdrant", "kb", "kb",
                "doc-a", "completed", 1, 0, true, "ok", List.of(), null));
        queue.markFailed(b.jobId(), "oh no");

        IngestQueue restarted = newQueue(tmpRoot.toString(), 3);
        assertThat(restarted.totalCount()).isEqualTo(2);
        // Neither should be re-queued — both are terminal.
        assertThat(restarted.pendingCount()).isZero();
    }

    @Test
    void restart_inProgressBeyondRetryCap_movesToFailed() throws Exception {
        // Submit, take, then mutate the persisted record to have retryCount=3.
        IngestJob job = queue.submit(IngestJob.queued(sampleRequest("kb"), "doc-1"));
        queue.take(100, TimeUnit.MILLISECONDS);

        // Manually write a high-retry-count IN_PROGRESS record.
        IngestJob hot = queue.getJob(job.jobId()).orElseThrow();
        IngestJob bumped = new IngestJob(hot.jobId(), JobStatus.IN_PROGRESS,
                hot.request(), hot.docId(), hot.submittedAt(), hot.startedAt(),
                hot.completedAt(), hot.result(), hot.error(), hot.warnings(), 3);
        Files.write(tmpRoot.resolve(bumped.jobId() + ".json"),
                jacksonMapper().writeValueAsBytes(bumped));

        IngestQueue restarted = newQueue(tmpRoot.toString(), 3);
        IngestJob recovered = restarted.getJob(job.jobId()).orElseThrow();
        assertThat(recovered.status()).isEqualTo(JobStatus.FAILED);
        assertThat(recovered.error()).contains("retry cap (3)");
        assertThat(restarted.pendingCount()).isZero();
    }

    // ---- helpers ------------------------------------------------------------

    private static IngestRequest sampleRequest(String kb) {
        return new IngestRequest(SourceType.PATH, "/tmp/x.pdf", "x.pdf",
                kb, null, 0L, "qdrant", null, false);
    }

    private static ObjectMapper jacksonMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        return m;
    }

    private static IngestQueue newQueue(String root, int maxRetries) throws Exception {
        IngestQueue q = new IngestQueue();
        setField(q, "persistencePath", root);
        setField(q, "maxRetries", maxRetries);
        setField(q, "objectMapper", jacksonMapper());
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
}
