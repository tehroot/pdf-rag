package org.hayden.ingest;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.qdrant.UuidV5;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The durable document store behind {@code POST /ingest/upload}. Uploaded
 * bytes land at {@code <root>/<kb>/<subdir>/<filename>} — a plain, browsable
 * corpus tree (rsync/ZFS-send friendly) that is the corpus of record: the
 * queue re-reads files from it, and a re-index re-ingests from it. There is
 * no reaper and no retention window; a stored document stays until an
 * operator deletes it.
 *
 * <p>Writes are durable and atomic: bytes go to a hidden {@code .<name>.part}
 * in the final directory (invisible to directory scans, which skip
 * dot-prefixed entries), are fsynced, and are renamed into place with
 * {@code ATOMIC_MOVE}. The multipart temp dir is expected to sit on the same
 * {@link FileStore} as the root ({@code UPLOAD_TMP_DIR}); when it doesn't,
 * moves degrade to copy + fsync + delete — never a non-atomic cross-device
 * {@code Files.move}.
 *
 * <p>Confinement: {@code kb_name} must be a single path segment
 * ({@code [A-Za-z0-9._-]{1,64}}, no {@code ..}, no leading dot) and is
 * rejected outright otherwise — it can never be the thing that escapes the
 * root. Sanitized subdir/filename paths are then re-checked to be descendants
 * of the literal root, not of {@code <root>/<kb>}: confining against a
 * baseline that is itself caller-derived is no confinement.
 */
@ApplicationScoped
public class UploadedDocumentStore {

    private static final Logger LOG = Logger.getLogger(UploadedDocumentStore.class);

    private static final Pattern KB_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_SEGMENT_CHARS = 200;
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    /** What to do when the target path already exists. */
    public enum Conflict {
        /** Overwrite in place; same path → same doc id → replaced document. */
        REPLACE,
        /** Store as {@code name-2.ext}, {@code name-3.ext}, … — a new document. */
        SUFFIX,
        /** Fail this file (the rest of the batch proceeds). */
        REJECT;

        public static Conflict parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return REPLACE;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "replace" -> REPLACE;
                case "suffix" -> SUFFIX;
                case "reject" -> REJECT;
                default -> throw new IngestException(
                        "Unknown on_conflict='" + raw + "' (expected replace|suffix|reject)");
            };
        }
    }

    /**
     * Hook invoked with the canonical target path before an existing file is
     * overwritten ({@code Conflict.REPLACE}). The upload service uses it to
     * refuse a replace whose bytes a non-terminal queued job still reads (see
     * {@link SourceConflictException}); throw from it to abort the overwrite.
     */
    @FunctionalInterface
    public interface ReplaceGuard {
        ReplaceGuard NONE = target -> { };

        void check(Path target);
    }

    /** A file successfully placed in the store. */
    public record StoredFile(Path path, String filename) {
    }

    /** One ZIP entry's fate: stored, or failed with a message. */
    public record ExpandedEntry(String entryName, StoredFile stored, String error) {
    }

    @ConfigProperty(name = "ingest.upload.root",
            defaultValue = "${user.home}/.pdf-rag-ingest/documents")
    String rootPath;

    /** Must live on the same FileStore as the root, or every move degrades to a copy. */
    @ConfigProperty(name = "ingest.upload.tmp_dir",
            defaultValue = "${user.home}/.pdf-rag-ingest/documents/.tmp")
    String tmpDirPath;

    @ConfigProperty(name = "ingest.upload.fsync", defaultValue = "true")
    boolean fsync;

    @ConfigProperty(name = "ingest.upload.min_free_bytes", defaultValue = "10737418240")
    long minFreeBytes;

    @ConfigProperty(name = "ingest.upload.tmp_sweep_minutes", defaultValue = "60")
    long tmpSweepMinutes;

    /**
     * Refuse to operate when the root is not a mount point. Inside the
     * container this catches a missing {@code /documents} bind mount, which
     * would otherwise silently store the corpus on the container's own
     * filesystem — durable-looking, gone on the next {@code compose down}.
     * Set false for bare-metal/dev runs where the root is a plain directory.
     */
    @ConfigProperty(name = "ingest.upload.require_mount", defaultValue = "true")
    boolean requireMount;

    @ConfigProperty(name = "ingest.upload.zip.max_entries", defaultValue = "500")
    int zipMaxEntries;

    @ConfigProperty(name = "ingest.upload.zip.max_uncompressed_bytes",
            defaultValue = "2147483648")
    long zipMaxUncompressedBytes;

    private Path root;
    private Path tmpDir;
    /** False when root and tmp dir sit on different FileStores → copy fallback. */
    private boolean sameStore;

    @PostConstruct
    void init() {
        root = Path.of(rootPath).toAbsolutePath().normalize();
        tmpDir = Path.of(tmpDirPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            Files.createDirectories(tmpDir);
        } catch (IOException e) {
            throw new IngestException("Failed to create upload store dirs under " + root, e);
        }
        if (requireMount && !isMountPoint(root)) {
            throw new IngestException("Upload root " + root + " is not a mount point. "
                    + "In the container this means the /documents bind mount is missing — "
                    + "uploads would land on the container filesystem and vanish with it. "
                    + "Fix the mount, or set INGEST_UPLOAD_REQUIRE_MOUNT=false for a "
                    + "bare-metal deployment.");
        }
        try {
            sameStore = Files.getFileStore(root).equals(Files.getFileStore(tmpDir));
        } catch (IOException e) {
            sameStore = false;
        }
        if (!sameStore) {
            LOG.warnf("Upload tmp dir %s is on a different FileStore than the root %s; "
                    + "stores fall back to copy + fsync + delete instead of an atomic "
                    + "same-device move. Point UPLOAD_TMP_DIR at the store's dataset.",
                    tmpDir, root);
        }
        sweepStrays();
    }

    public Path rootDir() {
        return root;
    }

    public Path tmpDir() {
        return tmpDir;
    }

    /**
     * Validate {@code kb_name} as a single path segment BEFORE any path is
     * built with it. {@code ..} matches the character class, so it (and a
     * leading dot, which directory scans would treat as hidden) is rejected
     * explicitly.
     */
    public static String validateKbName(String kbName) {
        if (kbName == null || !KB_NAME.matcher(kbName).matches()
                || kbName.startsWith(".")) {
            throw new IngestException("kb_name must match [A-Za-z0-9._-]{1,64} with no "
                    + "leading dot (a single path segment); got: " + kbName);
        }
        return kbName;
    }

    /**
     * Reserve free space for {@code bytes} of incoming data: the store must
     * keep {@code ingest.upload.min_free_bytes} usable after the write. Sized
     * from materialized temp files, never a client-declared Content-Length.
     */
    public void ensureFreeSpace(long bytes) {
        long usable;
        try {
            usable = Files.getFileStore(root).getUsableSpace();
        } catch (IOException e) {
            throw new IngestException("Cannot stat free space on " + root, e);
        }
        if (usable - bytes < minFreeBytes) {
            throw new InsufficientStorageException("Storing " + bytes + " bytes would leave "
                    + (usable - bytes) + " free on " + root + "; ingest.upload.min_free_bytes="
                    + minFreeBytes + " must remain");
        }
    }

    /**
     * Place one materialized temp file into the store. The source file is
     * consumed (moved, or copied and deleted on a cross-device tmp dir).
     *
     * @param ordinal position in the batch, for the {@code upload-<n>}
     *                fallback when the filename sanitizes to nothing
     */
    public StoredFile store(String kbName, String subdir, String rawFilename,
                            Path source, Conflict onConflict, ReplaceGuard guard,
                            int ordinal) {
        validateKbName(kbName);
        Path dir = resolveDir(kbName, subdir);
        String name = sanitizeFilename(rawFilename, ordinal);
        checkPdfMagic(name, source);
        long size;
        try {
            size = Files.size(source);
        } catch (IOException e) {
            throw new IngestException("Cannot stat uploaded temp file " + source, e);
        }
        ensureFreeSpace(size);

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IngestException("Failed to create store directory " + dir, e);
        }
        Path target = dir.resolve(name);
        confine(target);
        if (Files.exists(target)) {
            switch (onConflict) {
                case REJECT -> throw new IngestException(
                        "Target already exists (on_conflict=reject): " + target);
                case SUFFIX -> {
                    target = nextFreeSuffix(dir, name);
                    name = target.getFileName().toString();
                }
                case REPLACE -> guard.check(target);
            }
        }
        durableWrite(source, target);
        return new StoredFile(target, name);
    }

    /**
     * Expand a {@code .zip} temp file into the store, preserving the archive's
     * internal directory structure under {@code <kb>/<subdir>/}. Directories,
     * hidden entries (any dot-prefixed segment, matching the directory scan's
     * rule), and entries outside the accepted extension set are skipped; a
     * zip-slip entry or a busted size cap fails the whole archive. The archive
     * itself is never stored, and nested archives are not expanded ({@code zip}
     * is not in the accepted set).
     */
    public List<ExpandedEntry> expandZip(String kbName, String subdir, Path zipFile,
                                         Conflict onConflict, ReplaceGuard guard) {
        validateKbName(kbName);
        Path targetDir = resolveDir(kbName, subdir);
        List<ExpandedEntry> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            List<ZipEntry> accepted = acceptedEntries(zip, targetDir);
            // The declared sizes are re-checked against actual bytes below —
            // a zip bomb lies about them, but an honest oversized archive
            // fails cheaply here before anything is written.
            long declared = 0;
            for (ZipEntry e : accepted) {
                if (e.getSize() > 0) {
                    declared += e.getSize();
                }
            }
            if (declared > zipMaxUncompressedBytes) {
                throw new IngestException("Archive declares " + declared
                        + " uncompressed bytes; ingest.upload.zip.max_uncompressed_bytes="
                        + zipMaxUncompressedBytes);
            }
            ensureFreeSpace(declared);

            long actualTotal = 0;
            int i = 0;
            for (ZipEntry entry : accepted) {
                i++;
                Path entryTmp = Files.createTempFile(tmpDir, ".zip-entry-", ".part");
                try {
                    actualTotal += extractCapped(zip, entry, entryTmp,
                            zipMaxUncompressedBytes - actualTotal);
                    String entrySubdir = joinSubdir(subdir, parentOf(entry.getName()));
                    String entryName = lastSegment(entry.getName());
                    try {
                        StoredFile sf = store(kbName, entrySubdir, entryName,
                                entryTmp, onConflict, guard, i);
                        out.add(new ExpandedEntry(entry.getName(), sf, null));
                    } catch (SourceConflictException e) {
                        throw e;   // request-level 409, not a per-entry outcome
                    } catch (IngestException e) {
                        out.add(new ExpandedEntry(entry.getName(), null, e.getMessage()));
                    }
                } finally {
                    Files.deleteIfExists(entryTmp);
                }
            }
            return out;
        } catch (IOException e) {
            throw new IngestException("Failed to expand archive "
                    + zipFile.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Find the stored file whose deterministic doc id
     * ({@link UuidV5#forSource}) matches, by walking the KB's tree — never by
     * trusting a caller-supplied path. Empty when the doc was not ingested
     * from this store (e.g. a {@code /docs} or {@code /host} path).
     */
    public Optional<Path> findByDocId(String kbName, String docId) {
        validateKbName(kbName);
        Path kbDir = root.resolve(kbName);
        if (!Files.isDirectory(kbDir)) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(kbDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !isHidden(root, p))
                    .filter(p -> docId.equals(UuidV5.forSource(kbName,
                            DirectoryIngestService.canonicalSourcePath(p))))
                    .findFirst();
        } catch (IOException e) {
            throw new IngestException("Failed to scan store for doc " + docId, e);
        }
    }

    /**
     * Delete a stored file. Refuses any path outside the store root — the
     * read-only {@code /docs} and {@code /host} mounts are not ours to manage.
     * Returns false (deleting nothing) on refusal or when already gone.
     */
    public boolean deleteStored(Path path) {
        Path p = path.toAbsolutePath().normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            LOG.warnf("Refusing to delete %s: outside the upload store %s", p, root);
            return false;
        }
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new IngestException("Failed to delete stored file " + p, e);
        }
    }

    /**
     * Remove aged debris: multipart temp bodies a client abandoned
     * mid-upload, and {@code .<name>.part} files an interrupted store left
     * behind. Anything younger than {@code ingest.upload.tmp_sweep_minutes}
     * is left alone (it may belong to an in-flight request). Called at init
     * and after every upload request; failures are logged, never thrown.
     */
    public void sweepStrays() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(tmpSweepMinutes));
        sweepDir(tmpDir, cutoff, p -> true);
        sweepDir(root, cutoff, p -> {
            String n = p.getFileName().toString();
            return n.startsWith(".") && n.endsWith(".part");
        });
    }

    // ---- internals ----------------------------------------------------------

    /** Resolve <root>/<kb>/<sanitized subdir>, confined against the literal root. */
    private Path resolveDir(String kbName, String subdir) {
        Path dir = root.resolve(kbName);
        for (String segment : sanitizeSubdirSegments(subdir)) {
            dir = dir.resolve(segment);
        }
        confine(dir);
        return dir;
    }

    /**
     * The resolved path must be a strict descendant of the literal store root.
     * The sanitizers should make this unreachable; it's the backstop, not the
     * defence.
     */
    private void confine(Path resolved) {
        Path p = resolved.toAbsolutePath().normalize();
        if (!p.startsWith(root) || p.equals(root)) {
            throw new IngestException("Resolved path escapes the upload root: " + p);
        }
    }

    private List<String> sanitizeSubdirSegments(String subdir) {
        if (subdir == null || subdir.isBlank()) {
            return List.of();
        }
        String s = subdir.trim();
        if (s.startsWith("/") || s.startsWith("\\") || s.matches("^[A-Za-z]:.*")) {
            throw new IngestException("subdir must be relative: " + subdir);
        }
        List<String> out = new ArrayList<>();
        for (String rawSegment : s.split("[/\\\\]+")) {
            String segment = stripControlAndDots(rawSegment);
            if (segment.isEmpty()) {
                continue;   // "", ".", ".." and pure-junk segments vanish
            }
            if (segment.length() > MAX_SEGMENT_CHARS) {
                segment = segment.substring(0, MAX_SEGMENT_CHARS);
            }
            out.add(segment);
        }
        return out;
    }

    /**
     * Reduce a client-supplied filename to a single safe segment: last path
     * component only, control characters and leading dots stripped, capped at
     * {@value #MAX_SEGMENT_CHARS} chars with the extension preserved (the
     * extension drives content-type detection and the PDF routing).
     */
    public static String sanitizeFilename(String raw, int ordinal) {
        String name = raw == null ? "" : lastSegment(raw);
        name = stripControlAndDots(name);
        if (name.isEmpty()) {
            return "upload-" + ordinal;
        }
        if (name.length() > MAX_SEGMENT_CHARS) {
            int dot = name.lastIndexOf('.');
            String ext = (dot > 0 && name.length() - dot <= 16) ? name.substring(dot) : "";
            name = name.substring(0, MAX_SEGMENT_CHARS - ext.length()) + ext;
        }
        return name;
    }

    /** Last path component of a possibly slash-y name (browsers send paths). */
    private static String lastSegment(String raw) {
        String s = raw.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        return slash < 0 ? s : s.substring(slash + 1);
    }

    private static String parentOf(String entryName) {
        String s = entryName.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        return slash < 0 ? "" : s.substring(0, slash);
    }

    private static String joinSubdir(String subdir, String extra) {
        if (subdir == null || subdir.isBlank()) {
            return extra;
        }
        return extra == null || extra.isBlank() ? subdir : subdir + "/" + extra;
    }

    /** Drop control characters and any leading dots (dot-prefixed = hidden to scans). */
    private static String stripControlAndDots(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        int start = 0;
        while (start < sb.length() && sb.charAt(start) == '.') {
            start++;
        }
        return sb.substring(start).trim();
    }

    /**
     * A {@code .pdf} that doesn't start with {@code %PDF-} would fall through
     * {@code countPdfPages}'s catch → 0 pages into the sync text path and
     * index as garbage; reject it while the bytes are already in hand.
     */
    private static void checkPdfMagic(String name, Path source) {
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return;
        }
        byte[] head = new byte[PDF_MAGIC.length];
        try (InputStream in = Files.newInputStream(source)) {
            int read = in.readNBytes(head, 0, head.length);
            if (read < head.length || !java.util.Arrays.equals(head, PDF_MAGIC)) {
                throw new IngestException("File " + name + " has a .pdf extension but no "
                        + "%PDF- header; refusing to store a mislabeled PDF");
            }
        } catch (IOException e) {
            throw new IngestException("Cannot read uploaded temp file for " + name, e);
        }
    }

    private static Path nextFreeSuffix(Path dir, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            Path candidate = dir.resolve(base + "-" + n + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * The durable write: hidden {@code .part} in the final directory, fsync,
     * atomic rename, best-effort directory fsync. The {@code .part} lives in
     * the final directory precisely so the rename is same-dataset and atomic.
     */
    private void durableWrite(Path source, Path target) {
        Path part = target.resolveSibling("." + target.getFileName() + ".part");
        try {
            if (sameStore) {
                Files.move(source, part, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(source, part, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(source);
            }
            if (fsync) {
                try (FileChannel ch = FileChannel.open(part, StandardOpenOption.WRITE)) {
                    ch.force(true);
                }
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            fsyncDirQuietly(target.getParent());
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
            }
            throw new IngestException("Failed to store " + target.getFileName()
                    + ": " + e.getMessage(), e);
        }
    }

    /** fsync the directory so the rename itself survives power loss. Some
     *  platforms (notably macOS/Windows JDKs) refuse to open a directory
     *  channel; that's fine — skip. */
    private static void fsyncDirQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }

    /** Heuristic mount-point check: the root sits on a different FileStore
     *  than its parent (or IS the filesystem root). */
    private static boolean isMountPoint(Path p) {
        Path parent = p.getParent();
        if (parent == null) {
            return true;
        }
        try {
            return !Files.getFileStore(p).equals(Files.getFileStore(parent));
        } catch (IOException e) {
            return false;
        }
    }

    /** ZIP entries that will actually be extracted; zip-slip fails the archive. */
    private List<ZipEntry> acceptedEntries(ZipFile zip, Path targetDir) {
        List<ZipEntry> accepted = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName().replace('\\', '/');
            // Zip-slip: an entry whose normalized path escapes the target
            // directory is hostile — fail the whole archive, don't skip.
            Path resolved = targetDir.resolve(name).normalize();
            if (!resolved.startsWith(targetDir)) {
                throw new IngestException("Archive entry escapes the target directory "
                        + "(zip-slip): " + entry.getName());
            }
            if (entry.isDirectory() || hasHiddenSegment(name)
                    || !DirectoryIngestService.DEFAULT_EXTENSIONS.contains(extensionOf(name))) {
                continue;
            }
            accepted.add(entry);
            if (accepted.size() > zipMaxEntries) {
                throw new IngestException("Archive has more than "
                        + zipMaxEntries + " acceptable entries "
                        + "(ingest.upload.zip.max_entries)");
            }
        }
        return accepted;
    }

    /** Stream one entry to disk, hard-capped: declared sizes can lie. */
    private long extractCapped(ZipFile zip, ZipEntry entry, Path dest, long remaining)
            throws IOException {
        long written = 0;
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            try (var out = Files.newOutputStream(dest)) {
                while ((n = in.read(buf)) >= 0) {
                    written += n;
                    if (written > remaining) {
                        throw new IngestException("Archive exceeds "
                                + "ingest.upload.zip.max_uncompressed_bytes="
                                + zipMaxUncompressedBytes + " while extracting "
                                + entry.getName() + " (zip bomb?)");
                    }
                    out.write(buf, 0, n);
                }
            }
        }
        return written;
    }

    private static boolean hasHiddenSegment(String entryName) {
        for (String segment : entryName.split("/")) {
            if (segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 || dot == name.length() - 1)
                ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isHidden(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path part : rel) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private void sweepDir(Path dir, Instant cutoff, java.util.function.Predicate<Path> match) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(match)
                    .filter(p -> {
                        try {
                            FileTime t = Files.getLastModifiedTime(p);
                            return t.toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                            LOG.infof("Swept stray upload debris: %s", p);
                        } catch (IOException e) {
                            LOG.warnf("Failed to sweep %s: %s", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            LOG.warnf("Stray sweep of %s failed: %s", dir, e.getMessage());
        }
    }
}
