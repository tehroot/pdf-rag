package org.hayden;

import org.hayden.backend.qdrant.UuidV5;
import org.hayden.ingest.DirectoryFileOutcome;
import org.hayden.ingest.DirectoryIngestRequest;
import org.hayden.ingest.DirectoryIngestResponse;
import org.hayden.ingest.DirectoryIngestService;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestResult;
import org.hayden.ingest.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DirectoryIngestServiceTest {

    @TempDir
    Path root;

    private RecordingIngestService stub;
    private DirectoryIngestService service;

    @BeforeEach
    void setUp() throws Exception {
        stub = new RecordingIngestService();
        service = new DirectoryIngestService();
        setField(service, "ingestService", stub);
    }

    @Test
    void recursive_picksAllSupported_skipsHiddenAndUnsupported() throws Exception {
        write("a.pdf", "%PDF-1.4");
        write("b.txt", "hello");
        write("sub/c.md", "# heading");
        write(".secret.pdf", "hidden file");
        write("d.bin", "binary");          // unsupported extension
        write(".git/e.txt", "in hidden dir");

        DirectoryIngestResponse resp = service.ingestDirectory(req(null, null));

        assertThat(resp.filesFound()).isEqualTo(3);
        assertThat(resp.completed()).isEqualTo(3);
        assertThat(resp.queued()).isZero();
        assertThat(resp.failed()).isZero();
        assertThat(resp.files()).extracting(DirectoryFileOutcome::filename)
                .containsExactlyInAnyOrder("a.pdf", "b.txt", "c.md");
    }

    @Test
    void nonRecursive_skipsSubdirectories() throws Exception {
        write("a.pdf", "%PDF-1.4");
        write("sub/c.md", "# heading");

        DirectoryIngestResponse resp = service.ingestDirectory(req(false, null));

        assertThat(resp.files()).extracting(DirectoryFileOutcome::filename)
                .containsExactly("a.pdf");
    }

    @Test
    void extensionFilter_isHonored_andDotPrefixTolerated() throws Exception {
        write("a.pdf", "%PDF-1.4");
        write("b.txt", "hello");
        write("c.md", "# heading");

        DirectoryIngestResponse resp = service.ingestDirectory(req(null, List.of(".pdf", "md")));

        assertThat(resp.files()).extracting(DirectoryFileOutcome::filename)
                .containsExactlyInAnyOrder("a.pdf", "c.md");
    }

    @Test
    void docId_isDeterministicPerSourcePath() throws Exception {
        write("a.pdf", "%PDF-1.4");
        Path abs = root.resolve("a.pdf").toAbsolutePath().normalize();
        String expected = UuidV5.forSource("docs", abs.toString());

        DirectoryIngestResponse first = service.ingestDirectory(req(null, null));
        DirectoryIngestResponse second = service.ingestDirectory(req(null, null));

        assertThat(first.files().get(0).docId()).isEqualTo(expected);
        assertThat(second.files().get(0).docId()).isEqualTo(expected);
        // Same path → same id handed to the backend on every scan (idempotent).
        assertThat(stub.docIds).containsExactly(expected, expected);
    }

    @Test
    void perFileFailure_isCapturedAsErrorOutcome_andDoesNotAbort() throws Exception {
        write("good.pdf", "%PDF-1.4");
        write("bad.pdf", "%PDF-1.4");
        stub.failFor = "bad.pdf";

        DirectoryIngestResponse resp = service.ingestDirectory(req(null, null));

        assertThat(resp.filesFound()).isEqualTo(2);
        assertThat(resp.completed()).isEqualTo(1);
        assertThat(resp.failed()).isEqualTo(1);
        DirectoryFileOutcome bad = resp.files().stream()
                .filter(o -> o.filename().equals("bad.pdf")).findFirst().orElseThrow();
        assertThat(bad.status()).isEqualTo("error");
        assertThat(bad.message()).contains("boom");
        assertThat(bad.docId()).isNotNull(); // deterministic id still reported
    }

    @Test
    void queuedResult_isCountedAndReportsJobId() throws Exception {
        write("big.pdf", "%PDF-1.4");
        stub.queueFor = "big.pdf";

        DirectoryIngestResponse resp = service.ingestDirectory(req(null, null));

        assertThat(resp.queued()).isEqualTo(1);
        assertThat(resp.completed()).isZero();
        DirectoryFileOutcome o = resp.files().get(0);
        assertThat(o.status()).isEqualTo("queued");
        assertThat(o.jobId()).isEqualTo("job-big.pdf");
        assertThat(o.chunkCount()).isNull();
    }

    @Test
    void missingKbName_throws() {
        assertThatThrownBy(() -> service.ingestDirectory(new DirectoryIngestRequest(
                root.toString(), "  ", null, null, null, null, null, null)))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("kb_name");
    }

    @Test
    void relativeDirectory_throws() {
        assertThatThrownBy(() -> service.ingestDirectory(new DirectoryIngestRequest(
                "relative/path", "docs", null, null, null, null, null, null)))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void nonexistentDirectory_throws() {
        assertThatThrownBy(() -> service.ingestDirectory(new DirectoryIngestRequest(
                root.resolve("nope").toString(), "docs", null, null, null, null, null, null)))
                .isInstanceOf(IngestException.class)
                .hasMessageContaining("Not a directory");
    }

    // ---- helpers ------------------------------------------------------------

    private DirectoryIngestRequest req(Boolean recursive, List<String> extensions) {
        return new DirectoryIngestRequest(root.toString(), "docs", null,
                recursive, extensions, null, null, null);
    }

    private void write(String relative, String content) throws IOException {
        Path p = root.resolve(relative);
        Files.createDirectories(p.getParent() == null ? root : p.getParent());
        Files.writeString(p, content);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Stub IngestService that records the (request, docId) pairs it's handed. */
    private static final class RecordingIngestService extends IngestService {
        final List<String> docIds = new ArrayList<>();
        final List<IngestRequest> requests = new ArrayList<>();
        String failFor;
        String queueFor;

        @Override
        public IngestResult ingest(IngestRequest req, String explicitDocId) {
            requests.add(req);
            docIds.add(explicitDocId);
            String name = req.filename();
            if (name.equals(failFor)) {
                throw new IngestException("boom on " + name);
            }
            if (name.equals(queueFor)) {
                return IngestResult.queued("qdrant", req.kbName(), explicitDocId, "job-" + name);
            }
            return new IngestResult("qdrant", req.kbName(), req.kbName(), explicitDocId,
                    "completed", 3, 0, true, "ingested " + name, List.of(), null);
        }
    }
}
