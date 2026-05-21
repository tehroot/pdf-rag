package org.hayden.backend.qdrant;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.ingest.IngestException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Filesystem-backed {@link PageImageStore}. v1 implementation; the interface
 * exists so an S3-compatible adapter (RustFS / MinIO / AWS S3) can be dropped
 * in later without touching callers.
 *
 * <p>Layout under {@code ingest.page_store.root}:
 *
 * <pre>
 * ${root}/
 *   &lt;kb-name&gt;/
 *     &lt;doc-id&gt;/
 *       000001.png
 *       000002.png
 *       ...
 * </pre>
 *
 * <p>Page numbers are zero-padded to six digits for filesystem sort sanity
 * (so a 1000-page doc lists in numeric order under {@code ls -1}).
 *
 * <p>Writes are atomic via tmp-file-and-rename to avoid half-written PNGs being
 * observed by a concurrent reader (rare in practice but cheap to guarantee).
 *
 * <p>This bean also acts as a guard for the {@code ingest.page_store.impl}
 * config: if anything other than {@code "filesystem"} is selected, startup
 * fails fast with a clear message.
 */
@ApplicationScoped
public class FilesystemPageImageStore implements PageImageStore {

    private static final String IMPL_NAME = "filesystem";

    @ConfigProperty(name = "ingest.page_store.impl", defaultValue = IMPL_NAME)
    String configuredImpl;

    @ConfigProperty(name = "ingest.page_store.root")
    String rootPath;

    private Path root;

    @PostConstruct
    void init() {
        if (configuredImpl != null && !IMPL_NAME.equalsIgnoreCase(configuredImpl)) {
            throw new IngestException(
                    "ingest.page_store.impl='" + configuredImpl + "' is not supported in v1. "
                            + "Only 'filesystem' is available; vNext will add S3-compatible.");
        }
        if (rootPath == null || rootPath.isBlank()) {
            throw new IngestException("ingest.page_store.root must be configured");
        }
        this.root = Path.of(rootPath).toAbsolutePath();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IngestException("Failed to create page store root " + root, e);
        }
    }

    @Override
    public String backendName() {
        return IMPL_NAME;
    }

    @Override
    public String store(String kbName, String docId, int pageNumber, byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            throw new IngestException("Cannot store empty PNG for "
                    + kbName + "/" + docId + "/" + pageNumber);
        }
        String key = keyFor(kbName, docId, pageNumber);
        Path target = root.resolve(key);
        try {
            Files.createDirectories(target.getParent());
            // Atomic rename to avoid half-written files visible to readers.
            Path tmp = Files.createTempFile(target.getParent(),
                    target.getFileName().toString() + ".", ".tmp");
            try {
                Files.write(tmp, pngBytes);
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new IngestException("Failed to write page image to " + target, e);
        }
        return key;
    }

    @Override
    public byte[] retrieve(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Path resolved = resolveSafe(key);
        if (resolved == null || !Files.isRegularFile(resolved)) {
            return null;
        }
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new IngestException("Failed to read page image " + resolved, e);
        }
    }

    @Override
    public int deleteForKb(String kbName) {
        Path kbDir = root.resolve(sanitizeKb(kbName));
        return deleteTree(kbDir);
    }

    @Override
    public int deleteForDoc(String kbName, String docId) {
        Path docDir = root.resolve(sanitizeKb(kbName)).resolve(sanitizeDoc(docId));
        return deleteTree(docDir);
    }

    static String keyFor(String kbName, String docId, int pageNumber) {
        if (pageNumber < 1) {
            throw new IngestException("page_number must be >= 1, got " + pageNumber);
        }
        return sanitizeKb(kbName)
                + "/" + sanitizeDoc(docId)
                + "/" + String.format(Locale.ROOT, "%06d.png", pageNumber);
    }

    /**
     * Resolves a key against {@code root}, refusing anything that would escape
     * the root directory (defense against path traversal in case a malicious
     * key sneaks in from outside). Returns null on suspicious input.
     */
    Path resolveSafe(String key) {
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            return null;
        }
        return resolved;
    }

    /**
     * Restrict kb names to a safe character set. Slashes and traversal markers
     * are rejected outright; the rest pass through unchanged.
     */
    static String sanitizeKb(String kbName) {
        if (kbName == null || kbName.isBlank()) {
            throw new IngestException("kb name must be non-empty");
        }
        if (kbName.contains("/") || kbName.contains("\\")
                || kbName.equals(".") || kbName.equals("..")
                || kbName.startsWith(".")) {
            throw new IngestException("kb name contains unsafe characters: " + kbName);
        }
        return kbName;
    }

    static String sanitizeDoc(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IngestException("doc_id must be non-empty");
        }
        if (docId.contains("/") || docId.contains("\\")
                || docId.equals(".") || docId.equals("..")) {
            throw new IngestException("doc_id contains unsafe characters: " + docId);
        }
        return docId;
    }

    private static int deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        int[] count = {0};
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            if (Files.isRegularFile(p)) {
                                count[0]++;
                            }
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new IngestException("Failed to delete " + p, e);
                        }
                    });
        } catch (IOException e) {
            throw new IngestException("Failed to walk " + dir, e);
        }
        return count[0];
    }
}
