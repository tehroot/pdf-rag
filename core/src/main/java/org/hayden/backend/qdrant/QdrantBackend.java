package org.hayden.backend.qdrant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hayden.backend.Backend;
import org.hayden.backend.KnowledgeBaseSummary;
import org.hayden.backend.qdrant.fusion.FusionEngine;
import org.hayden.ingest.DeleteResult;
import org.hayden.ingest.KbDeleteResult;
import org.hayden.ingest.FetchedFile;
import org.hayden.ingest.FileFetcher;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestResult;
import org.hayden.ingest.JobSourceSnapshots;
import org.hayden.ingest.SearchRequest;
import org.hayden.ingest.SearchResponse;
import org.hayden.ingest.SidecarUnavailableException;
import org.hayden.jobs.IngestJob;
import org.hayden.jobs.IngestQueue;
import org.hayden.jobs.JobKind;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrator for the Qdrant backend. Delegates text-side work to
 * {@link ChunkPipeline} and (when visual indexing is enabled for the KB)
 * delegates the page-side work to {@link ColPaliPipeline}. Both pipelines
 * share a doc id so chunks and pages join cleanly at fusion time.
 *
 * <p>Sync vs queued routing: the text side always runs synchronously (the
 * caller gets a chunk count and the doc is text-searchable immediately). For
 * large visual PDFs (page count at or above
 * {@code ingest.queue.sync_threshold_pages}, default 20) only the GPU-bound
 * visual side is queued, as a {@link JobKind#VISUAL} job; the
 * {@link org.hayden.jobs.IngestWorker} picks it up and calls back into
 * {@link #ingestForWorker(IngestJob)}. Chunks and pages need no ingest-time
 * link — they join at search time via the shared docId.
 */
@ApplicationScoped
public class QdrantBackend implements Backend {

    public static final String NAME = "qdrant";

    @Inject
    FileFetcher fetcher;

    @Inject
    ChunkPipeline chunks;

    @Inject
    ColPaliPipeline pages;

    @Inject
    FusionEngine fusion;

    @Inject
    IngestQueue queue;

    @Inject
    JobSourceSnapshots snapshots;

    /**
     * Serializes writers on one doc id. Deterministic ids mean an upload batch
     * and a directory scan (or two uploads of one filename) can target the
     * same doc id at once; doIngest does deleteDoc-then-write, and two of
     * those interleaved leave one writer's points deleted by the other. The
     * lock is held across a document's whole delete + write critical section
     * by every entry point (sync ingest, split-queue submit, worker).
     *
     * <p>A FIXED stripe array, not a map keyed on doc id: a map would retain
     * one lock per distinct id for the process lifetime, and a bulk corpus
     * (94k docs on the R530) makes that an unbounded, caller-driven leak.
     * Striping bounds the lock set at {@value #DOC_LOCK_STRIPES}; the cost is
     * that two unrelated doc ids can share a stripe and serialize
     * needlessly, which is harmless — ingest is I/O-bound on Qdrant and the
     * embedder, and collisions are rare at this width. Single-process is the
     * deployment, so no distributed lock.
     */
    private static final int DOC_LOCK_STRIPES = 64;
    private final ReentrantLock[] docLocks = createStripes();

    private static ReentrantLock[] createStripes() {
        ReentrantLock[] stripes = new ReentrantLock[DOC_LOCK_STRIPES];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ReentrantLock();
        }
        return stripes;
    }

    @ConfigProperty(name = "ingest.visual_index.default_enabled", defaultValue = "true")
    boolean defaultVisualIndexEnabled;

    @ConfigProperty(name = "ingest.queue.sync_threshold_pages", defaultValue = "20")
    int syncThresholdPages;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public IngestResult ingest(IngestRequest req) {
        return ingest(req, null);
    }

    @Override
    public IngestResult ingest(IngestRequest req, String explicitDocId) {
        FetchedFile file = fetch(req);
        // Caller-supplied id (directory scans use a deterministic one keyed on
        // the source path, for idempotent re-ingest); otherwise a fresh random.
        String docId = (explicitDocId == null || explicitDocId.isBlank())
                ? UUID.randomUUID().toString()
                : explicitDocId;

        boolean visualRequested = resolveVisualIndexEnabled(req);
        validateModeConsistency(req.kbName(), visualRequested);

        // Pre-flight sidecar check when visual is requested — hard-fail rather
        // than silently degrading to text-only for an explicit visual request.
        if (visualRequested && !pages.sidecarHealthy()) {
            throw new IngestException(
                    "Visual index requested for KB '" + req.kbName()
                            + "' but the ColPali sidecar is unreachable. "
                            + "Retry when the sidecar is available, or call again with "
                            + "enable_visual_index=false to skip the visual side.");
        }

        // Async routing: big PDFs whose VLM embedding will take minutes get
        // their visual side queued. The text side still runs synchronously —
        // it's seconds of work, the caller gets a real chunk count, and the
        // doc is text-searchable immediately. The queue is thereby a pure
        // VLM lane: back-to-back GPU work, no Tika/bge gaps between jobs.
        if (shouldQueue(file, visualRequested)) {
            // Create <kb>_pages BEFORE anything lands: its existence is the
            // KB's visual-capability flag, and mode validation on the next
            // ingest into this KB would otherwise reject "chunks exist but
            // no visual index" while the job drains.
            pages.ensureCollectionFor(req.kbName());

            IngestResult chunkResult;
            ReentrantLock lock = lockFor(docId);
            lock.lock();
            try {
                chunks.deleteDoc(req.kbName(), docId);
                chunkResult = chunks.ingestChunks(req, file, docId);
            } finally {
                lock.unlock();
            }

            // Pin the bytes the queued job will read: the persisted request
            // points at a hardlink snapshot, so an on_conflict=replace upload
            // over the original path can't change what the worker renders.
            // INLINE requests carry their bytes; URL re-fetches are out of
            // scope (nothing local to pin).
            IngestJob job = IngestJob.queuedVisual(req, docId);
            job = job.withRequest(snapshotRequest(req, job.jobId()));
            queue.submit(job);
            return IngestResult.queuedVisual(NAME, req.kbName(), docId,
                    job.jobId(), chunkResult.chunkCount());
        }

        return doIngest(req, file, docId, visualRequested);
    }

    /**
     * Worker entry point: re-fetch the file from the persisted request and run
     * the job's work. VISUAL jobs (the normal case since the split — text ran
     * at submit) run only the page pipeline; legacy FULL jobs (persisted
     * before an upgrade) re-validate mode consistency and run both pipelines
     * as before. Used by {@link org.hayden.jobs.IngestWorker}.
     */
    public IngestResult ingestForWorker(IngestJob job) {
        IngestRequest req = job.request();
        FetchedFile file = fetch(req);
        if (job.effectiveKind() == JobKind.VISUAL) {
            return doVisualIngest(req, file, job.docId());
        }
        boolean visualRequested = resolveVisualIndexEnabled(req);
        validateModeConsistency(req.kbName(), visualRequested);
        if (visualRequested && !pages.sidecarHealthy()) {
            throw new SidecarUnavailableException(
                    "Visual index requested for KB '" + req.kbName()
                            + "' but the ColPali sidecar is unreachable.");
        }
        return doIngest(req, file, job.docId(), visualRequested);
    }

    /**
     * Visual side only — the text side already ran synchronously at submit.
     * Mode consistency was validated at submit too, and <kb>_pages was created
     * eagerly there, so no re-validation: this KB being visual is a given.
     */
    private IngestResult doVisualIngest(IngestRequest req, FetchedFile file, String docId) {
        if (!pages.sidecarHealthy()) {
            throw new SidecarUnavailableException(
                    "Visual job for KB '" + req.kbName()
                            + "' but the ColPali sidecar is unreachable.");
        }
        ColPaliPipeline.PagesIngestResult pagesResult;
        ReentrantLock lock = lockFor(docId);
        lock.lock();
        try {
            // Replace semantics for retries: discard a previous partial attempt.
            pages.deleteDoc(req.kbName(), docId);
            pagesResult = pages.ingestPages(req, file, docId);
        } finally {
            lock.unlock();
        }
        return new IngestResult(
                NAME,
                req.kbName(),
                req.kbName(),
                docId,
                "completed",
                0,
                pagesResult.pageCount(),
                true,
                pagesResult.pageCount() + " pages visual-indexed "
                        + "(text chunks were ingested at submit time)",
                List.of(),
                null);
    }

    /** The actual ingest work. Shared by sync path and worker path. */
    IngestResult doIngest(IngestRequest req, FetchedFile file, String docId,
                          boolean visualRequested) {
        IngestResult chunkResult;
        List<String> warnings = new ArrayList<>();
        int pageCount = 0;
        // The whole delete + write is one critical section per doc id: a
        // concurrent writer on the same deterministic id (upload + directory
        // scan over one tree) must not delete points this call just wrote.
        ReentrantLock lock = lockFor(docId);
        lock.lock();
        try {
            // Replace semantics: clear any prior copy of this docId before writing.
            // For directory re-scans (deterministic ids) this overwrites a changed
            // file cleanly instead of leaving a stale tail of orphaned chunks; for
            // worker retries it discards a previous partial attempt. A random docId
            // (MCP ingest_document) matches nothing, so this is a cheap no-op there.
            chunks.deleteDoc(req.kbName(), docId);
            if (visualRequested) {
                pages.deleteDoc(req.kbName(), docId);
            }

            // Text ingest always runs.
            chunkResult = chunks.ingestChunks(req, file, docId);

            if (visualRequested) {
                if (isPdf(file)) {
                    ColPaliPipeline.PagesIngestResult pagesResult =
                            pages.ingestPages(req, file, docId);
                    pageCount = pagesResult.pageCount();
                } else {
                    warnings.add("enable_visual_index=true but file is not a PDF; "
                            + "visual side skipped for this document. "
                            + "Text chunks still ingested.");
                }
            }
        } finally {
            lock.unlock();
        }

        String message = chunkResult.message();
        if (pageCount > 0) {
            message += " + " + pageCount + " pages visual-indexed";
        }

        return new IngestResult(
                NAME,
                chunkResult.kbId(),
                chunkResult.kbName(),
                docId,
                chunkResult.processingStatus(),
                chunkResult.chunkCount(),
                pageCount,
                chunkResult.addedToKb(),
                message,
                warnings,
                null);
    }

    @Override
    public SearchResponse search(SearchRequest req) {
        return fusion.search(req, NAME);
    }

    @Override
    public DeleteResult deleteDocument(String kbName, String docId) {
        if (kbName == null || kbName.isBlank()) {
            throw new IngestException("kb_name is required");
        }
        if (docId == null || docId.isBlank()) {
            throw new IngestException("doc_id is required");
        }
        boolean textDeleted = chunks.deleteDoc(kbName, docId);
        ColPaliPipeline.DeleteDocResult visual = pages.deleteDoc(kbName, docId);
        String message;
        if (!textDeleted && !visual.pointsDeleted()) {
            message = "No collection found for KB '" + kbName + "'; nothing deleted.";
        } else {
            message = "Deleted document " + docId + " from KB '" + kbName + "'"
                    + (visual.pointsDeleted()
                            ? " (" + visual.imagesRemoved() + " page image(s) removed)" : "")
                    + ".";
        }
        return new DeleteResult(NAME, kbName, docId, textDeleted,
                visual.pointsDeleted(), visual.imagesRemoved(), message);
    }

    /**
     * Full KB teardown: cancel pending queue work first (a queued visual job
     * draining afterwards would resurrect a stub {@code <kb>_pages}), then
     * drop the chunk collection, the pages collection, and the stored page
     * images. Idempotent — deleting an absent KB reports nothing dropped.
     * Caveat: a job already IN_PROGRESS can't be stopped and may recreate a
     * stub pages collection when it completes; delete again if that matters.
     */
    @Override
    public KbDeleteResult deleteKnowledgeBase(String kbName) {
        if (kbName == null || kbName.isBlank()) {
            throw new IngestException("kb_name is required");
        }
        int jobsCancelled = queue.cancelPending(kbName);
        boolean textDropped = chunks.dropCollection(kbName);
        ColPaliPipeline.DropResult visual = pages.dropVisualIndex(kbName);
        String message;
        if (!textDropped && !visual.collectionDropped()) {
            message = "No collections found for KB '" + kbName + "'; nothing deleted"
                    + (jobsCancelled > 0 ? " (" + jobsCancelled + " queued job(s) cancelled)" : "")
                    + ".";
        } else {
            message = "Deleted KB '" + kbName + "': "
                    + (textDropped ? "chunk collection" : "no chunk collection")
                    + ", " + (visual.collectionDropped() ? "pages collection" : "no pages collection")
                    + ", " + visual.filesRemoved() + " page image(s), "
                    + jobsCancelled + " queued job(s) cancelled.";
        }
        return new KbDeleteResult(NAME, kbName, textDropped,
                visual.collectionDropped(), visual.filesRemoved(), jobsCancelled, message);
    }

    @Override
    public Long documentCount(String kbName) {
        return chunks.countDocuments(kbName);
    }

    @Override
    public List<KnowledgeBaseSummary> listKnowledgeBases() {
        List<KnowledgeBaseSummary> textOnly = chunks.listKbCollections();
        List<KnowledgeBaseSummary> augmented = new ArrayList<>(textOnly.size());
        for (KnowledgeBaseSummary kb : textOnly) {
            boolean visualEnabled = pages.isEnabledFor(kb.name());
            Long visualPages = visualEnabled ? pages.getPageCount(kb.name()) : null;
            augmented.add(kb.withVisualIndex(visualEnabled, visualPages));
        }
        return augmented;
    }

    /**
     * Deterministic UUID v5 for chunk point IDs. Preserved as a static helper
     * for back-compat with tests; new code should use {@link UuidV5#forChunk}.
     */
    public static String pointIdFor(String docId, int chunkIndex) {
        return UuidV5.forChunk(docId, chunkIndex);
    }

    /** Resolve the effective visual-index flag for this request. */
    boolean resolveVisualIndexEnabled(IngestRequest req) {
        if (req.enableVisualIndex() != null) {
            return req.enableVisualIndex();
        }
        return defaultVisualIndexEnabled;
    }

    /**
     * Reject mode mismatch on an existing KB: if the KB has a visual index but
     * this call says no visual (or vice versa), throw with a clear message.
     * Fresh KBs (neither chunk nor visual collection exists yet) pass through
     * unchanged regardless of the requested mode.
     */
    void validateModeConsistency(String kbName, boolean visualRequested) {
        boolean kbHasChunks = chunks.collectionExists(kbName);
        boolean kbHasVisual = pages.isEnabledFor(kbName);
        if (!kbHasChunks && !kbHasVisual) {
            // Fresh KB; either mode is fine for the first ingest.
            return;
        }
        if (kbHasVisual && !visualRequested) {
            throw new IngestException(
                    "KB '" + kbName + "' was created with visual index enabled, "
                            + "but this call has enable_visual_index=false. "
                            + "Either set enable_visual_index=true or create a new KB.");
        }
        if (!kbHasVisual && visualRequested) {
            throw new IngestException(
                    "KB '" + kbName + "' was created without a visual index, "
                            + "but this call has enable_visual_index=true. "
                            + "Either set enable_visual_index=false or create a new KB.");
        }
    }

    /**
     * Routing heuristic for sync vs queue.
     *
     * <p>Conservative: only queue PDFs that have visual indexing enabled AND
     * exceed the configured page-count threshold. Text-only ingest is fast
     * enough to run synchronously; non-PDF files have no page count to
     * compare against. The agent gets immediate results in the common case;
     * only the genuinely-slow ingests are queued.
     */
    boolean shouldQueue(FetchedFile file, boolean visualRequested) {
        if (!visualRequested) return false;
        if (!isPdf(file)) return false;
        if (syncThresholdPages <= 0) return false;
        int pages = countPdfPages(file);
        return pages >= syncThresholdPages;
    }

    private static int countPdfPages(FetchedFile file) {
        try (PDDocument doc = Loader.loadPDF(file.content())) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            return 0;   // unknown; treat as small.
        }
    }

    private ReentrantLock lockFor(String docId) {
        // floorMod: hashCode can be negative, and a negative index throws.
        return docLocks[Math.floorMod(docId.hashCode(), DOC_LOCK_STRIPES)];
    }

    /**
     * For a PATH source, repoint the request at a hardlink snapshot of its
     * file (keyed on the job id) so later overwrites of the original path
     * can't change the bytes the worker reads. Falls through to the original
     * request when linking fails (cross-device, no hardlinks) — the upload
     * path then refuses replace-overwrites of that path instead.
     */
    private IngestRequest snapshotRequest(IngestRequest req, String jobId) {
        if (req.sourceType() != IngestRequest.SourceType.PATH || snapshots == null) {
            return req;
        }
        Optional<Path> link = snapshots.link(jobId, Path.of(req.sourceValue()));
        if (link.isEmpty()) {
            return req;
        }
        // Keep the original filename: the link's name matches, but an explicit
        // override (if any) must survive for payload/reporting purposes.
        return new IngestRequest(IngestRequest.SourceType.PATH,
                link.get().toString(), req.filename(), req.kbName(),
                req.kbDescription(), req.pollTimeoutSeconds(), req.backend(),
                req.metadata(), req.enableVisualIndex());
    }

    private FetchedFile fetch(IngestRequest req) {
        return switch (req.sourceType()) {
            case URL -> fetcher.fromUrl(req.sourceValue(), req.filename());
            case PATH -> fetcher.fromPath(req.sourceValue(), req.filename());
            case INLINE -> fetcher.fromInline(req.sourceValue(), req.filename());
        };
    }

    private static boolean isPdf(FetchedFile file) {
        if (file.contentType() != null
                && file.contentType().toLowerCase().contains("pdf")) {
            return true;
        }
        return file.filename() != null
                && file.filename().toLowerCase().endsWith(".pdf");
    }
}
