package org.hayden;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hayden.backend.qdrant.UuidV5;
import org.hayden.ingest.DirectoryFileOutcome;
import org.hayden.ingest.DirectoryIngestService;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestRequest.SourceType;
import org.hayden.ingest.IngestResult;
import org.hayden.ingest.IngestService;
import org.hayden.ingest.PayloadTooLargeException;
import org.hayden.ingest.SourceConflictException;
import org.hayden.ingest.UploadIngestRequest;
import org.hayden.ingest.UploadIngestResponse;
import org.hayden.ingest.UploadIngestService;
import org.hayden.ingest.UploadIngestService.IncomingFile;
import org.hayden.ingest.UploadedDocumentStore;
import org.hayden.jobs.IngestJob;
import org.hayden.jobs.IngestQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadIngestServiceTest {

    @TempDir
    Path base;

    private Path root;
    private Path tmp;
    private UploadedDocumentStore store;
    private RecordingIngestService stub;
    private IngestQueue queue;
    private UploadIngestService service;

    @BeforeEach
    void setUp() throws Exception {
        root = base.resolve("documents");
        tmp = root.resolve(".tmp");
        store = UploadedDocumentStoreTest.newStore(root, tmp);
        stub = new RecordingIngestService();
        queue = newQueue(base.resolve("queue"));

        service = new UploadIngestService();
        setField(service, "store", store);
        setField(service, "ingestService", stub);
        setField(service, "queue", queue);
        setField(service, "parallelism", 1);
        setField(service, "maxFiles", 200);
        setField(service, "maxRequestBytes", 2147483648L);
        setField(service, "zipEnabled", true);
    }

    @Test
    void outcomes_comeBackInSubmitOrder_withDirectoryScanDocIds() throws Exception {
        UploadIngestResponse resp = service.ingestUploads(req(null),
                List.of(incoming("a.txt", "alpha"), incoming("b.txt", "beta")));

        assertThat(resp.filesFound()).isEqualTo(2);
        assertThat(resp.completed()).isEqualTo(2);
        assertThat(resp.files()).extracting(DirectoryFileOutcome::filename)
                .containsExactly("a.txt", "b.txt");
        // The doc id is exactly what a directory scan of the stored path
        // computes — the two bulk paths describe the same documents.
        for (DirectoryFileOutcome o : resp.files()) {
            String expected = UuidV5.forSource("docs",
                    DirectoryIngestService.canonicalSourcePath(o.path()));
            assertThat(o.docId()).isEqualTo(expected);
        }
        assertThat(stub.requests).extracting(IngestRequest::sourceType)
                .containsOnly(SourceType.PATH);
    }

    @Test
    void oneFilesFailure_doesNotAbortBatch_andLeavesTheFileOnDisk() throws Exception {
        stub.failFor = "bad.txt";

        UploadIngestResponse resp = service.ingestUploads(req(null),
                List.of(incoming("good.txt", "g"), incoming("bad.txt", "b")));

        assertThat(resp.completed()).isEqualTo(1);
        assertThat(resp.failed()).isEqualTo(1);
        DirectoryFileOutcome bad = resp.files().get(1);
        assertThat(bad.status()).isEqualTo("error");
        assertThat(bad.message()).contains("boom");
        // The bytes stay: the operator fixes the cause and re-scans the KB
        // directory instead of re-uploading.
        assertThat(Files.readString(root.resolve("docs").resolve("bad.txt"))).isEqualTo("b");
    }

    @Test
    void maxFiles_isEnforced() throws Exception {
        setField(service, "maxFiles", 1);

        assertThatThrownBy(() -> service.ingestUploads(req(null),
                List.of(incoming("a.txt", "1"), incoming("b.txt", "2"))))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("max_files");
    }

    @Test
    void maxRequestBytes_isEnforcedFromMaterializedSizes() throws Exception {
        setField(service, "maxRequestBytes", 4L);

        assertThatThrownBy(() -> service.ingestUploads(req(null),
                List.of(incoming("a.txt", "longer than four bytes"))))
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void storeOnly_storesEverything_callsIngestZeroTimes_emitsStoredOutcomes()
            throws Exception {
        UploadIngestRequest r = new UploadIngestRequest("docs", null, null, null,
                null, null, null, false, null);

        UploadIngestResponse resp = service.ingestUploads(r,
                List.of(incoming("a.txt", "alpha"), incoming("b.txt", "beta")));

        assertThat(stub.requests).isEmpty();   // zero IngestService calls
        assertThat(resp.stored()).isEqualTo(2);
        assertThat(resp.completed()).isZero();
        for (DirectoryFileOutcome o : resp.files()) {
            assertThat(o.status()).isEqualTo("stored");
            assertThat(o.jobId()).isNull();
            // Same id a later POST /ingest/directory over the KB dir computes.
            assertThat(o.docId()).isEqualTo(UuidV5.forSource("docs",
                    DirectoryIngestService.canonicalSourcePath(o.path())));
            assertThat(Files.exists(Path.of(o.path()))).isTrue();
        }
    }

    @Test
    void replaceOverLiveJobSource_withoutSnapshot_isRejectedWith409NamingTheJob()
            throws Exception {
        // First upload stores the file; a queued visual job then points at
        // that exact path (i.e. no hardlink snapshot could be taken).
        service.ingestUploads(req(null), List.of(incoming("big.pdf", "%PDF-1.4 v1")));
        Path storedPath = root.resolve("docs").resolve("big.pdf");
        IngestRequest jobReq = new IngestRequest(SourceType.PATH,
                storedPath.toString(), "big.pdf", "docs", null, 0L, "qdrant", null, true);
        IngestJob job = queue.submit(IngestJob.queuedVisual(jobReq, "doc-1"));

        assertThatThrownBy(() -> service.ingestUploads(req("replace"),
                List.of(incoming("big.pdf", "%PDF-1.4 v2"))))
                .isInstanceOf(SourceConflictException.class)
                .hasMessageContaining(job.jobId());
        // The original bytes are untouched.
        assertThat(Files.readString(storedPath)).isEqualTo("%PDF-1.4 v1");
    }

    @Test
    void replaceOverSnapshottedJobSource_proceeds() throws Exception {
        // A snapshotted job's persisted request points at the LINK, not the
        // browsable path — so the path is free to replace.
        service.ingestUploads(req(null), List.of(incoming("big.pdf", "%PDF-1.4 v1")));
        Path link = root.resolve(".jobs").resolve("j-1").resolve("big.pdf");
        Files.createDirectories(link.getParent());
        Files.createLink(link, root.resolve("docs").resolve("big.pdf"));
        IngestRequest jobReq = new IngestRequest(SourceType.PATH,
                link.toString(), "big.pdf", "docs", null, 0L, "qdrant", null, true);
        queue.submit(IngestJob.queuedVisual(jobReq, "doc-1"));

        UploadIngestResponse resp = service.ingestUploads(req("replace"),
                List.of(incoming("big.pdf", "%PDF-1.4 v2")));

        assertThat(resp.completed()).isEqualTo(1);
        assertThat(Files.readString(root.resolve("docs").resolve("big.pdf")))
                .isEqualTo("%PDF-1.4 v2");
        // The job still reads the bytes it was queued for, through the link.
        assertThat(Files.readString(link)).isEqualTo("%PDF-1.4 v1");
    }

    @Test
    void zipPart_expandsIntoPerEntryOutcomes() throws Exception {
        Path zip = Files.createTempFile(tmp, "batch-", ".zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("manuals/a.txt"));
            out.write("alpha".getBytes());
            out.closeEntry();
            out.putNextEntry(new java.util.zip.ZipEntry("manuals/b.txt"));
            out.write("beta".getBytes());
            out.closeEntry();
        }

        UploadIngestResponse resp = service.ingestUploads(req(null),
                List.of(new IncomingFile("batch.zip", zip)));

        assertThat(resp.filesFound()).isEqualTo(2);
        assertThat(resp.completed()).isEqualTo(2);
        assertThat(resp.files()).extracting(DirectoryFileOutcome::filename)
                .containsExactly("a.txt", "b.txt");
        assertThat(Files.exists(root.resolve("docs/manuals/a.txt"))).isTrue();
        // The archive itself was not stored.
        assertThat(Files.exists(root.resolve("docs").resolve("batch.zip"))).isFalse();
    }

    // ---- helpers ------------------------------------------------------------

    private UploadIngestRequest req(String onConflict) {
        return new UploadIngestRequest("docs", null, null, null, null, null,
                onConflict, null, null);
    }

    private IncomingFile incoming(String filename, String content) throws IOException {
        Path p = Files.createTempFile(tmp, "upload-", ".body");
        Files.writeString(p, content);
        return new IncomingFile(filename, p);
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

    /** Stub IngestService that records the (request, docId) pairs it's handed. */
    private static final class RecordingIngestService extends IngestService {
        final List<String> docIds = Collections.synchronizedList(new ArrayList<>());
        final List<IngestRequest> requests = Collections.synchronizedList(new ArrayList<>());
        String failFor;

        @Override
        public IngestResult ingest(IngestRequest req, String explicitDocId) {
            requests.add(req);
            docIds.add(explicitDocId);
            if (req.filename().equals(failFor)) {
                throw new IngestException("boom on " + req.filename());
            }
            return new IngestResult("qdrant", req.kbName(), req.kbName(), explicitDocId,
                    "completed", 3, 0, true, "ingested " + req.filename(), List.of(), null);
        }
    }
}
