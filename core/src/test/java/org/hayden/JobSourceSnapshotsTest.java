package org.hayden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hayden.backend.qdrant.QdrantBackend;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestRequest.SourceType;
import org.hayden.ingest.IngestResult;
import org.hayden.ingest.JobSourceSnapshots;
import org.hayden.ingest.SidecarUnavailableException;
import org.hayden.jobs.IngestJob;
import org.hayden.jobs.IngestQueue;
import org.hayden.jobs.IngestWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JobSourceSnapshotsTest {

    @TempDir
    Path base;

    private Path root;
    private JobSourceSnapshots snapshots;

    @BeforeEach
    void setUp() throws Exception {
        root = base.resolve("documents");
        Files.createDirectories(root);
        snapshots = new JobSourceSnapshots();
        setField(snapshots, "rootPath", root.toString());
    }

    /**
     * The regression test for the headline finding: a queued job must keep
     * reading the bytes it was queued for, even after an on_conflict=replace
     * upload does Files.move(..., REPLACE_EXISTING) over the original path.
     * The move replaces the directory entry, not the inode the link shares.
     */
    @Test
    void hardlink_survivesReplaceOverwriteOfTheOriginalPath() throws Exception {
        Path original = root.resolve("docs").resolve("big.pdf");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "the original bytes");

        Path link = snapshots.link("job-1", original).orElseThrow();

        // The overwrite an upload's durable write performs.
        Path replacement = Files.createTempFile(root, ".new", ".part");
        Files.writeString(replacement, "completely different bytes");
        Files.move(replacement, original, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);

        assertThat(Files.readString(original)).isEqualTo("completely different bytes");
        assertThat(Files.readString(link)).isEqualTo("the original bytes");
    }

    @Test
    void link_failsCleanly_whenSnapshotDirCannotBeCreated() throws Exception {
        // A regular file squatting on .jobs stands in for "hardlinks/dirs
        // unavailable" — link() must return empty, not throw.
        Files.writeString(root.resolve(".jobs"), "not a directory");
        Path source = root.resolve("a.pdf");
        Files.writeString(source, "x");

        Optional<Path> link = snapshots.link("job-1", source);

        assertThat(link).isEmpty();
    }

    @Test
    void release_removesTheJobsSnapshotDir() throws Exception {
        Path original = root.resolve("a.pdf");
        Files.writeString(original, "x");
        Path link = snapshots.link("job-1", original).orElseThrow();

        snapshots.release("job-1");

        assertThat(Files.exists(link)).isFalse();
        assertThat(Files.exists(root.resolve(".jobs").resolve("job-1"))).isFalse();
        assertThat(Files.exists(original)).isTrue();   // the store copy stays
    }

    @Test
    void sweep_dropsSnapshotsForUnknownJobs_keepsLiveOnes() throws Exception {
        Path original = root.resolve("a.pdf");
        Files.writeString(original, "x");
        snapshots.link("live-job", original);
        snapshots.link("dead-job", original);

        snapshots.sweep(Set.of("live-job"));

        assertThat(Files.exists(root.resolve(".jobs").resolve("live-job"))).isTrue();
        assertThat(Files.exists(root.resolve(".jobs").resolve("dead-job"))).isFalse();
    }

    // ---- worker integration: release on terminal status ---------------------

    @Test
    void worker_releasesSnapshot_onCompleted_andOnFailed_keepsItOnTransient()
            throws Exception {
        IngestQueue queue = newQueue(base.resolve("queue"));
        FakeBackend backend = new FakeBackend();
        IngestWorker worker = new IngestWorker();
        setField(worker, "queue", queue);
        setField(worker, "backend", backend);
        setField(worker, "snapshots", snapshots);
        setField(worker, "workerThreads", 0);
        setField(worker, "pollTimeoutMs", 50L);
        Path original = root.resolve("a.pdf");
        Files.writeString(original, "x");

        // COMPLETED → released.
        IngestJob done = submitLinked(queue, "d-1", original);
        backend.nextResult = result("d-1");
        worker.processOne(take(queue));
        assertThat(Files.exists(root.resolve(".jobs").resolve(done.jobId()))).isFalse();

        // Transient sidecar failure → requeued, snapshot kept for the retry.
        IngestJob transient1 = submitLinked(queue, "d-2", original);
        backend.nextException = new SidecarUnavailableException("loading");
        worker.processOne(take(queue));
        assertThat(Files.exists(root.resolve(".jobs").resolve(transient1.jobId()))).isTrue();

        // Terminal FAILED → released.
        backend.nextException = new RuntimeException("bad pdf");
        worker.processOne(take(queue));
        assertThat(Files.exists(root.resolve(".jobs").resolve(transient1.jobId()))).isFalse();
    }

    // ---- helpers ------------------------------------------------------------

    private IngestJob submitLinked(IngestQueue queue, String docId, Path source) {
        IngestJob job = IngestJob.queuedVisual(new IngestRequest(SourceType.PATH,
                source.toString(), null, "kb", null, 0L, "qdrant", null, true), docId);
        Path link = snapshots.link(job.jobId(), source).orElseThrow();
        return queue.submit(job.withRequest(new IngestRequest(SourceType.PATH,
                link.toString(), null, "kb", null, 0L, "qdrant", null, true)));
    }

    private static IngestJob take(IngestQueue queue) throws InterruptedException {
        return queue.take(100, TimeUnit.MILLISECONDS).orElseThrow();
    }

    private static IngestResult result(String docId) {
        return new IngestResult("qdrant", "kb", "kb", docId,
                "completed", 0, 3, true, "done", List.of(), null);
    }

    private static IngestQueue newQueue(Path dir) throws Exception {
        IngestQueue q = new IngestQueue();
        setField(q, "persistencePath", dir.toString());
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

    /** Worker collaborator: hand back a canned result or exception. */
    private static final class FakeBackend extends QdrantBackend {
        IngestResult nextResult;
        RuntimeException nextException;

        @Override
        public IngestResult ingestForWorker(IngestJob job) {
            if (nextException != null) {
                RuntimeException e = nextException;
                nextException = null;
                throw e;
            }
            return nextResult;
        }
    }
}
