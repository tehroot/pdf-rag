package org.hayden.ingest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.qdrant.UuidV5;
import org.hayden.jobs.IngestJob;
import org.hayden.jobs.IngestQueue;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates {@code POST /ingest/upload}: place every uploaded file in the
 * durable {@link UploadedDocumentStore}, then ingest each stored file exactly
 * as a directory scan would — same deterministic doc id
 * ({@link UuidV5#forSource} keyed on the stored path), so an upload and a
 * later {@code POST /ingest/directory} over {@code <root>/<kb>} describe the
 * same documents and neither duplicates the other's work.
 *
 * <p>The store phase runs first, single-threaded, in part order; only the
 * ingest fan-out is parallel ({@code ingest.upload.parallelism}, via the
 * shared {@link BatchIngestExecutor}). A failed ingest keeps the file: the
 * operator fixes the cause and re-runs the directory scan over the KB
 * directory rather than re-uploading gigabytes. With {@code ingest=false}
 * nothing is indexed at all — every outcome is {@code "stored"} and the
 * caller indexes later with one directory-scan call (which keeps the queue a
 * pure VLM lane instead of routing whole-file FULL jobs through it).
 */
@ApplicationScoped
public class UploadIngestService {

    private static final Logger LOG = Logger.getLogger(UploadIngestService.class);

    @Inject
    UploadedDocumentStore store;

    @Inject
    IngestService ingestService;

    @Inject
    IngestQueue queue;

    /** Files ingested concurrently per upload request (the store phase stays serial). */
    @ConfigProperty(name = "ingest.upload.parallelism", defaultValue = "4")
    int parallelism;

    @ConfigProperty(name = "ingest.upload.max_files", defaultValue = "200")
    int maxFiles;

    /** Keep equal to quarkus.http.limits.max-body-size — see PayloadTooLargeException. */
    @ConfigProperty(name = "ingest.upload.max_request_bytes", defaultValue = "2147483648")
    long maxRequestBytes;

    @ConfigProperty(name = "ingest.upload.zip.enabled", defaultValue = "true")
    boolean zipEnabled;

    /** One multipart file part, already materialized to a temp file on disk. */
    public record IncomingFile(String filename, Path tempFile) {
    }

    /** A stored (or store-failed) document awaiting the ingest fan-out. */
    private record Unit(Path storedPath, String filename, DirectoryFileOutcome error) {
        static Unit stored(UploadedDocumentStore.StoredFile sf) {
            return new Unit(sf.path(), sf.filename(), null);
        }

        static Unit failed(String filename, String message) {
            return new Unit(null, filename, new DirectoryFileOutcome(
                    null, filename, null, "error", null, null, null, message));
        }
    }

    public UploadIngestResponse ingestUploads(UploadIngestRequest req,
                                              List<IncomingFile> files) {
        if (req == null) {
            throw new IngestException("upload form fields are required");
        }
        UploadedDocumentStore.validateKbName(req.kbName());
        if (files == null || files.isEmpty()) {
            throw new IngestException("at least one 'files' part is required");
        }
        if (files.size() > maxFiles) {
            throw new IngestException("Request has " + files.size()
                    + " file parts; ingest.upload.max_files=" + maxFiles);
        }
        if (parallelism < 1) {
            throw new IngestException("ingest.upload.parallelism must be >= 1 (got "
                    + parallelism + ")");
        }
        UploadedDocumentStore.Conflict conflict =
                UploadedDocumentStore.Conflict.parse(req.onConflict());

        // Both size checks work from materialized temp-file sizes — actual
        // bytes on disk, never a client-declared Content-Length.
        long totalBytes = 0;
        for (IncomingFile f : files) {
            totalBytes += sizeOf(f);
        }
        if (totalBytes > maxRequestBytes) {
            throw new PayloadTooLargeException("Request materializes " + totalBytes
                    + " bytes; ingest.upload.max_request_bytes=" + maxRequestBytes);
        }
        store.ensureFreeSpace(totalBytes);

        boolean runIngest = req.ingest() == null || req.ingest();
        boolean expandZips = zipEnabled
                && (req.expandArchives() == null || req.expandArchives());

        try {
            List<Unit> units = storeAll(req, files, conflict, expandZips);
            List<DirectoryFileOutcome> outcomes = runIngest
                    ? BatchIngestExecutor.ingestAll("upload-ingest-", parallelism,
                            units, u -> ingestUnit(req, u))
                    : storedOutcomes(req.kbName(), units);

            int completed = 0;
            int queued = 0;
            int stored = 0;
            int failed = 0;
            for (DirectoryFileOutcome o : outcomes) {
                switch (o.status()) {
                    case "queued" -> queued++;
                    case "stored" -> stored++;
                    case "error" -> failed++;
                    default -> completed++;
                }
            }
            LOG.infof("Upload ingest kb=%s files=%d completed=%d queued=%d stored=%d failed=%d",
                    req.kbName(), outcomes.size(), completed, queued, stored, failed);
            return new UploadIngestResponse(store.rootDir().toString(), req.kbName(),
                    outcomes.size(), completed, queued, stored, failed, outcomes);
        } finally {
            // A client that disconnected mid-upload (or an aborted request)
            // must not leak partial bodies onto the tank forever.
            store.sweepStrays();
        }
    }

    /** Store phase: serial, in part order. Per-file store failures become error units. */
    private List<Unit> storeAll(UploadIngestRequest req, List<IncomingFile> files,
                                UploadedDocumentStore.Conflict conflict,
                                boolean expandZips) {
        UploadedDocumentStore.ReplaceGuard guard = this::refuseReplaceOfLiveJobSource;
        List<Unit> units = new ArrayList<>();
        int ordinal = 0;
        for (IncomingFile f : files) {
            ordinal++;
            boolean isZip = f.filename() != null
                    && f.filename().toLowerCase(Locale.ROOT).endsWith(".zip");
            try {
                if (isZip && expandZips) {
                    for (UploadedDocumentStore.ExpandedEntry e
                            : store.expandZip(req.kbName(), req.subdir(),
                                    f.tempFile(), conflict, guard)) {
                        units.add(e.error() == null
                                ? Unit.stored(e.stored())
                                : Unit.failed(e.entryName(), e.error()));
                    }
                } else {
                    units.add(Unit.stored(store.store(req.kbName(), req.subdir(),
                            f.filename(), f.tempFile(), conflict, guard, ordinal)));
                }
            } catch (SourceConflictException e) {
                throw e;   // request-level 409; already-stored files stay (recoverable)
            } catch (IngestException e) {
                LOG.warnf("Upload store failed for %s: %s", f.filename(), e.getMessage());
                units.add(Unit.failed(f.filename(), e.getMessage()));
            }
        }
        return units;
    }

    /**
     * The hardlink-snapshot fallback: when a queued job's persisted request
     * still points at this exact path (i.e. no snapshot could be taken at
     * submit), overwriting it would change the bytes the worker reads. Refuse
     * with a 409 naming the job. A snapshotted job points at its link, never
     * at the browsable path, so it doesn't match here and replace proceeds.
     */
    private void refuseReplaceOfLiveJobSource(Path target) {
        String canonical = DirectoryIngestService.canonicalSourcePath(target);
        for (IngestJob job : queue.listJobs()) {
            if (job.status().isTerminal()
                    || job.request().sourceType() != IngestRequest.SourceType.PATH) {
                continue;
            }
            if (canonical.equals(DirectoryIngestService
                    .canonicalSourcePath(job.request().sourceValue()))) {
                throw new SourceConflictException("Cannot replace " + canonical
                        + ": queued job " + job.jobId() + " still reads it and no "
                        + "byte snapshot exists. Wait for the job to finish, or "
                        + "use on_conflict=suffix.");
            }
        }
    }

    private DirectoryFileOutcome ingestUnit(UploadIngestRequest req, Unit u) {
        if (u.error() != null) {
            return u.error();
        }
        String abs = DirectoryIngestService.canonicalSourcePath(u.storedPath());
        String docId = UuidV5.forSource(req.kbName(), abs);
        IngestRequest ir = new IngestRequest(
                IngestRequest.SourceType.PATH, abs, u.filename(),
                req.kbName(), req.kbDescription(), 0L,
                req.backend(), req.metadata(), req.enableVisualIndex());
        try {
            IngestResult r = ingestService.ingest(ir, docId);
            boolean isQueued = "queued".equals(r.processingStatus());
            return new DirectoryFileOutcome(abs, u.filename(), r.fileId(),
                    r.processingStatus(), r.jobId(),
                    isQueued ? null : r.chunkCount(),
                    isQueued ? null : r.pageCount(),
                    r.message());
        } catch (RuntimeException e) {
            // The stored file stays — re-index via POST /ingest/directory
            // once the cause is fixed, no re-upload needed.
            LOG.warnf("Upload ingest failed for %s: %s", abs, e.getMessage());
            return new DirectoryFileOutcome(abs, u.filename(), docId,
                    "error", null, null, null, e.getMessage());
        }
    }

    /** ingest=false: bytes on the tank, nothing indexed, zero IngestService calls. */
    private List<DirectoryFileOutcome> storedOutcomes(String kbName, List<Unit> units) {
        List<DirectoryFileOutcome> out = new ArrayList<>(units.size());
        for (Unit u : units) {
            if (u.error() != null) {
                out.add(u.error());
                continue;
            }
            String abs = DirectoryIngestService.canonicalSourcePath(u.storedPath());
            out.add(new DirectoryFileOutcome(abs, u.filename(),
                    UuidV5.forSource(kbName, abs), "stored", null, null, null,
                    "Stored, not indexed (ingest=false); index later with "
                            + "POST /ingest/directory over the KB directory"));
        }
        return out;
    }

    private static long sizeOf(IncomingFile f) {
        try {
            return Files.size(f.tempFile());
        } catch (IOException e) {
            throw new IngestException("Cannot stat uploaded temp file for "
                    + f.filename(), e);
        }
    }
}
