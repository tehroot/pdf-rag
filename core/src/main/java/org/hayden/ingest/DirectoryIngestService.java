package org.hayden.ingest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.qdrant.UuidV5;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scans a directory on disk and ingests every matching file into a knowledge
 * base, one {@link IngestRequest} per file dispatched through
 * {@link IngestService}. Each file is given a deterministic document id
 * ({@link UuidV5#forSource}) keyed on its absolute path, so re-scanning a
 * directory overwrites unchanged files in place rather than duplicating them.
 *
 * <p>Per-file routing is the backend's normal sync/queue decision: small or
 * text-only files ingest synchronously and come back {@code "completed"};
 * large visual PDFs are queued and come back {@code "queued"} with a job id.
 * A failure on one file is captured as an {@code "error"} outcome and does not
 * abort the rest of the scan.
 */
@ApplicationScoped
public class DirectoryIngestService {

    private static final Logger LOG = Logger.getLogger(DirectoryIngestService.class);

    /** Extensions accepted when the request doesn't specify its own filter.
     *  Package-visible: the upload path's ZIP expansion accepts the same set. */
    static final Set<String> DEFAULT_EXTENSIONS = Set.of(
            "pdf", "txt", "md", "html", "htm", "json", "csv", "docx", "xlsx", "pptx");

    @Inject
    IngestService ingestService;

    /**
     * Files ingested concurrently per directory scan. Each worker runs the
     * whole per-file text pipeline (extract, chunk, embed, upsert), so this is
     * the knob that overlaps Tika/PDFBox CPU work with embedding-server slots.
     * Pair with llama-server {@code --parallel}/{@code --cont-batching} —
     * with a single server slot, concurrent embed requests just queue.
     */
    @ConfigProperty(name = "ingest.directory.parallelism", defaultValue = "4")
    int parallelism;

    public DirectoryIngestResponse ingestDirectory(DirectoryIngestRequest req) {
        if (req == null) {
            throw new IngestException("request body is required");
        }
        if (req.kbName() == null || req.kbName().isBlank()) {
            throw new IngestException("kb_name is required");
        }
        if (req.directory() == null || req.directory().isBlank()) {
            throw new IngestException("directory is required");
        }

        Path dir = Path.of(req.directory());
        if (!dir.isAbsolute()) {
            throw new IngestException("directory must be an absolute path: " + req.directory());
        }
        if (!Files.isDirectory(dir)) {
            throw new IngestException("Not a directory (or does not exist): " + req.directory());
        }

        boolean recursive = req.recursive() == null || req.recursive();
        Set<String> exts = normalizeExtensions(req.extensions());
        List<Path> files = scan(dir, recursive, exts);
        if (parallelism < 1) {
            throw new IngestException("ingest.directory.parallelism must be >= 1 (got "
                    + parallelism + ")");
        }

        List<DirectoryFileOutcome> outcomes = ingestAll(req, files);

        int completed = 0;
        int queued = 0;
        int failed = 0;
        for (DirectoryFileOutcome o : outcomes) {
            switch (o.status()) {
                case "queued" -> queued++;
                case "error" -> failed++;
                default -> completed++;
            }
        }

        LOG.infof("Directory ingest kb=%s dir=%s found=%d completed=%d queued=%d failed=%d",
                req.kbName(), dir, files.size(), completed, queued, failed);
        return new DirectoryIngestResponse(dir.toString(), req.kbName(),
                files.size(), completed, queued, failed, outcomes);
    }

    /**
     * Ingest every file via the shared {@link BatchIngestExecutor},
     * {@code parallelism} files in flight at once. Outcomes come back in scan
     * order regardless of completion order; per-file failure is captured as an
     * "error" outcome by {@link #ingestOne} and never aborts the batch.
     */
    private List<DirectoryFileOutcome> ingestAll(DirectoryIngestRequest req, List<Path> files) {
        return BatchIngestExecutor.ingestAll("dir-ingest-", parallelism, files,
                f -> ingestOne(req, f));
    }

    private DirectoryFileOutcome ingestOne(DirectoryIngestRequest req, Path f) {
        String abs = canonicalSourcePath(f);
        String filename = f.getFileName().toString();
        String docId = UuidV5.forSource(req.kbName(), abs);
        IngestRequest ir = new IngestRequest(
                IngestRequest.SourceType.PATH, abs, filename,
                req.kbName(), req.kbDescription(), 0L,
                req.backend(), req.metadata(), req.enableVisualIndex());
        try {
            IngestResult r = ingestService.ingest(ir, docId);
            boolean isQueued = "queued".equals(r.processingStatus());
            return new DirectoryFileOutcome(abs, filename, r.fileId(),
                    r.processingStatus(), r.jobId(),
                    isQueued ? null : r.chunkCount(),
                    isQueued ? null : r.pageCount(),
                    r.message());
        } catch (RuntimeException e) {
            LOG.warnf("Directory ingest failed for %s: %s", abs, e.getMessage());
            return new DirectoryFileOutcome(abs, filename, docId,
                    "error", null, null, null, e.getMessage());
        }
    }

    /**
     * Delete a directory-ingested document. Identify it by {@code docId}
     * directly, or by {@code sourcePath} — the absolute path it was ingested
     * from — which is resolved to the same deterministic id
     * ({@link UuidV5#forSource}) the scan assigned it. Useful when a file has
     * been removed from disk and should be dropped from the index.
     */
    public DeleteResult deleteDocument(String kbName, String docId,
                                       String sourcePath, String backend) {
        return deleteDocument(kbName, docId, sourcePath, backend, false);
    }

    /**
     * As above, optionally also deleting the stored source file. File deletion
     * is only allowed via {@code doc_id}: a {@code source_path} is caller input,
     * and letting it drive the deletion would put the caller on both sides of
     * the containment check ({@link IngestService#deleteDocument(String,
     * String, String, boolean)} re-locates the file from the store itself).
     */
    public DeleteResult deleteDocument(String kbName, String docId,
                                       String sourcePath, String backend,
                                       boolean deleteSource) {
        if (kbName == null || kbName.isBlank()) {
            throw new IngestException("kb_name is required");
        }
        boolean hasDocId = docId != null && !docId.isBlank();
        boolean hasSourcePath = sourcePath != null && !sourcePath.isBlank();
        if (deleteSource && hasSourcePath) {
            throw new IngestException(
                    "delete_source=true requires doc_id and cannot be combined with "
                            + "source_path (the file to delete is located from the store, "
                            + "never from a caller-supplied path)");
        }
        if (deleteSource && !hasDocId) {
            throw new IngestException("delete_source=true requires doc_id");
        }
        String id;
        if (hasDocId) {
            id = docId;
        } else if (hasSourcePath) {
            id = UuidV5.forSource(kbName, canonicalSourcePath(sourcePath));
        } else {
            throw new IngestException("doc_id or source_path is required");
        }
        return ingestService.deleteDocument(kbName, id, backend, deleteSource);
    }

    /**
     * Canonical form of a source path, used both when assigning a file's
     * deterministic doc id during a scan and when resolving a {@code source_path}
     * back to that id for deletion — so the two always agree.
     */
    public static String canonicalSourcePath(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    public static String canonicalSourcePath(String path) {
        return canonicalSourcePath(Path.of(path));
    }

    private List<Path> scan(Path root, boolean recursive, Set<String> exts) {
        try (Stream<Path> walk = recursive ? Files.walk(root) : Files.list(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isHidden(root, p))
                    .filter(p -> exts.isEmpty() || exts.contains(extensionOf(p)))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new IngestException("Failed to scan directory " + root, e);
        }
    }

    /** Hidden if the file — or any directory between the scan root and it — is dot-prefixed. */
    private static boolean isHidden(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path part : rel) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizeExtensions(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return DEFAULT_EXTENSIONS;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String e : requested) {
            if (e == null || e.isBlank()) {
                continue;
            }
            String norm = e.trim().toLowerCase(Locale.ROOT);
            if (norm.startsWith(".")) {
                norm = norm.substring(1);
            }
            if (!norm.isEmpty()) {
                out.add(norm);
            }
        }
        return out;
    }

    private static String extensionOf(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot < 0 || dot == name.length() - 1)
                ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
