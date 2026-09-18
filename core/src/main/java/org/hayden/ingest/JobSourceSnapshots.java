package org.hayden.ingest;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Pins the bytes of a queued job's source file. A queued visual job persists a
 * path and re-reads it minutes (or a restart) later; an
 * {@code on_conflict=replace} upload can overwrite that path in between, and
 * the worker would then render pages from bytes its chunks never came from —
 * both under one doc id, both scoring in the same fused result, with nothing
 * downstream to catch it.
 *
 * <p>The fix: at queue time, hardlink the source into
 * {@code <root>/.jobs/<jobId>/<filename>} and point the persisted request at
 * the link. A later {@code Files.move(..., REPLACE_EXISTING)} replaces the
 * <em>directory entry</em>, not the inode, so the job keeps reading exactly
 * the bytes it was queued for. One inode, no data blocks.
 *
 * <p>When the link can't be made (cross-device source, a filesystem without
 * hardlinks), the caller falls back to refusing replace-overwrites of paths a
 * live job still reads ({@link SourceConflictException}). Links are released
 * when the job reaches a terminal status; a startup sweep removes links whose
 * job id the queue no longer tracks as live.
 */
@ApplicationScoped
public class JobSourceSnapshots {

    private static final Logger LOG = Logger.getLogger(JobSourceSnapshots.class);

    /** Same root as the document store: links must be same-dataset to work. */
    @ConfigProperty(name = "ingest.upload.root",
            defaultValue = "${user.home}/.pdf-rag-ingest/documents")
    String rootPath;

    private Path jobsDir() {
        return Path.of(rootPath).toAbsolutePath().normalize().resolve(".jobs");
    }

    /**
     * Hardlink {@code source} for {@code jobId}. Empty on any failure — the
     * caller then persists the original path and relies on the replace-refusal
     * fallback instead.
     */
    public Optional<Path> link(String jobId, Path source) {
        Path dir = jobsDir().resolve(jobId);
        try {
            Files.createDirectories(dir);
            Path link = dir.resolve(source.getFileName().toString());
            Files.deleteIfExists(link);
            Files.createLink(link, source);
            return Optional.of(link);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            LOG.debugf("No source snapshot for job %s (%s): %s — falling back to "
                    + "replace-refusal for this path", jobId, source, e.toString());
            deleteTreeQuietly(dir);
            return Optional.empty();
        }
    }

    /** Release the snapshot when a job reaches COMPLETED or terminal FAILED. */
    public void release(String jobId) {
        deleteTreeQuietly(jobsDir().resolve(jobId));
    }

    /**
     * Startup sweep: drop snapshot dirs whose job id is not in
     * {@code liveJobIds} (unknown to the queue, or already terminal — either
     * way nothing will read the link again).
     */
    public void sweep(Set<String> liveJobIds) {
        Path dir = jobsDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    .filter(p -> !liveJobIds.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        LOG.infof("Sweeping orphaned job snapshot %s", p.getFileName());
                        deleteTreeQuietly(p);
                    });
        } catch (IOException e) {
            LOG.warnf("Job-snapshot sweep failed: %s", e.getMessage());
        }
    }

    private static void deleteTreeQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
