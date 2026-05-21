package org.hayden.tools;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hayden.backend.KnowledgeBaseSummary;
import org.hayden.backend.openwebui.OpenWebUiBackend;
import org.hayden.backend.openwebui.dto.ProcessStatus;
import org.hayden.backend.qdrant.ColPaliPipeline;
import org.hayden.ingest.DropVisualIndexResult;
import org.hayden.ingest.IngestException;
import org.hayden.ingest.IngestRequest;
import org.hayden.ingest.IngestRequest.SourceType;
import org.hayden.ingest.IngestResult;
import org.hayden.ingest.IngestService;
import org.hayden.ingest.InspectPageResult;
import org.hayden.ingest.SearchRequest;
import org.hayden.ingest.SearchResponse;
import org.hayden.jobs.IngestJob;
import org.hayden.jobs.IngestQueue;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IngestTools {

    @Inject
    IngestService ingestService;

    @Inject
    OpenWebUiBackend openWebUi;

    @Inject
    ColPaliPipeline colpaliPipeline;

    @Inject
    IngestQueue ingestQueue;

    @Tool(name = "ingest_document",
            description = """
                    Ingest a single document into a knowledge base.
                    Resolves the source (url, absolute filesystem path, or base64 bytes),
                    finds-or-creates the named KB on the chosen backend, extracts text,
                    chunks + embeds (Qdrant) or uploads-and-polls (Open WebUI), and stores
                    the document. When enable_visual_index is true (the default for the
                    Qdrant backend), additionally rasterizes the PDF pages and indexes
                    them via the ColPali sidecar for visual retrieval. Returns identifiers
                    and the final processing status.""")
    @Blocking
    public IngestResult ingestDocument(
            @ToolArg(description = "Source kind: 'url', 'path', or 'inline'.") String source_type,
            @ToolArg(description = "URL, absolute path, or base64 bytes depending on source_type.") String source_value,
            @ToolArg(description = "Knowledge base / collection name. Reused if it exists; otherwise created.") String kb_name,
            @ToolArg(description = "Filename to register. Required for source_type='inline'; otherwise inferred.", required = false) String filename,
            @ToolArg(description = "Description applied only when creating a new KB; ignored otherwise.", required = false) String kb_description,
            @ToolArg(description = "Max seconds to wait for processing (Open WebUI backend only). Ignored by Qdrant.", required = false, defaultValue = "300") Integer poll_timeout_seconds,
            @ToolArg(description = "Backend to use: 'qdrant' or 'openwebui'. Defaults to the server-configured backend.", required = false) String backend,
            @ToolArg(description = "Optional key/value metadata stored alongside each chunk (Qdrant only).", required = false) Map<String, Object> metadata,
            @ToolArg(description = "Whether to also index page images via ColPali (Qdrant backend only). Defaults to the server-configured INGEST_DEFAULT_VISUAL_INDEX value. Hard-rejects if the KB was created with a different setting.", required = false) Boolean enable_visual_index) {

        IngestRequest req = new IngestRequest(
                SourceType.parse(source_type),
                source_value,
                filename,
                kb_name,
                kb_description,
                poll_timeout_seconds == null ? 300L : poll_timeout_seconds.longValue(),
                backend,
                metadata,
                enable_visual_index);
        return ingestService.ingest(req);
    }

    @Tool(name = "search_documents",
            description = """
                    Vector-search a knowledge base for chunks relevant to a query.
                    On the Qdrant backend, the retrieval mode controls whether text-side
                    (chunks), visual-side (ColPali pages), or both pipelines run, with
                    'auto' (the default) picking based on whether the KB has a visual index.
                    The Open WebUI backend uses its own chat-side RAG and exposes no
                    direct search endpoint here.""")
    @Blocking
    public SearchResponse searchDocuments(
            @ToolArg(description = "Knowledge base / Qdrant collection name to search.") String kb_name,
            @ToolArg(description = "Natural-language query. Embedded with the same model used at ingest.") String query,
            @ToolArg(description = "How many hits to return.", required = false, defaultValue = "5") Integer top_k,
            @ToolArg(description = "Backend to use. Defaults to the server-configured backend.", required = false) String backend,
            @ToolArg(description = "Optional metadata filter, e.g. {\"source\":\"https://...\"} or {\"filename\":\"x.pdf\"}.", required = false) Map<String, Object> filter,
            @ToolArg(description = "Retrieval mode: 'auto' (fusion when visual index exists, else text_only), 'fusion', 'text_only', or 'colpali_only'. Defaults to the server-configured RETRIEVAL_MODE.", required = false) String retrieval_mode,
            @ToolArg(description = "Operator override for fusion strategy: 'rrf' or 'weighted'. Defaults to the server-configured FUSION_STRATEGY.", required = false) String fusion_strategy) {

        SearchRequest req = new SearchRequest(
                backend,
                kb_name,
                query,
                top_k == null ? 5 : top_k,
                filter,
                retrieval_mode,
                fusion_strategy);
        return ingestService.search(req);
    }

    @Tool(name = "list_knowledge_bases",
            description = """
                    List knowledge bases / collections.
                    Defaults to listing across all enabled backends; pass backend='qdrant' or
                    'openwebui' to scope, or backend='all' explicitly.""")
    @Blocking
    public List<KnowledgeBaseSummary> listKnowledgeBases(
            @ToolArg(description = "Backend to query: 'qdrant', 'openwebui', or 'all'.", required = false, defaultValue = "all") String backend) {
        return ingestService.listKnowledgeBases(backend);
    }

    @Tool(name = "get_file_status",
            description = "Return Open WebUI's current processing status for a previously uploaded file. Open WebUI backend only.")
    @Blocking
    public ProcessStatus getFileStatus(
            @ToolArg(description = "Open WebUI file id (returned from ingest_document with backend='openwebui').") String file_id) {
        return openWebUi.getFileStatus(file_id);
    }

    @Tool(name = "get_ingest_status",
            description = """
                    Check the status of a queued ingest job. When ingest_document returns
                    a result with processing_status='queued' and a job_id, the agent polls
                    this tool until status is 'completed' or 'failed'. Returns the full
                    job record including the final IngestResult on completion.""")
    @Blocking
    public IngestJob getIngestStatus(
            @ToolArg(description = "Job id from ingest_document's queued result.") String job_id) {
        return ingestQueue.getJob(job_id)
                .orElseThrow(() -> new IngestException("Unknown job_id: " + job_id
                        + ". Either the id is wrong, or the queue's persistence root has been "
                        + "wiped (older completed jobs may have been pruned)."));
    }

    @Tool(name = "inspect_page",
            description = """
                    Retrieve a rendered page image from the Qdrant visual index as base64 PNG.
                    Useful when a search hit's text is suspect (e.g. messy OCR) and a
                    multimodal agent wants to read the page directly. Requires that the
                    KB was ingested with enable_visual_index=true. Returns null fields
                    if the page isn't in the visual index.""")
    @Blocking
    public InspectPageResult inspectPage(
            @ToolArg(description = "Knowledge base / Qdrant collection name.") String kb_name,
            @ToolArg(description = "Document id (from a previous ingest_document result's file_id field).") String doc_id,
            @ToolArg(description = "Page number (1-indexed).") Integer page_number) {
        if (page_number == null || page_number < 1) {
            throw new IngestException("page_number must be >= 1");
        }
        InspectPageResult result = colpaliPipeline.inspectPage(kb_name, doc_id, page_number);
        if (result == null) {
            throw new IngestException("No rendered page found for kb='" + kb_name
                    + "' doc_id='" + doc_id + "' page_number=" + page_number
                    + ". Either the KB has no visual index, the document wasn't ingested with"
                    + " enable_visual_index=true, or the page image has been deleted.");
        }
        return result;
    }

    @Tool(name = "drop_visual_index",
            description = """
                    Admin tool: delete the visual (ColPali page) index for a KB.
                    Drops the <kb>_pages Qdrant collection AND removes the stored page
                    images. The text-side chunk collection is untouched — the KB
                    continues working in text_only mode.
                    Requires confirm=true to actually drop; with confirm=false (the
                    default) returns the dry-run plan without changing anything.""")
    @Blocking
    public DropVisualIndexResult dropVisualIndex(
            @ToolArg(description = "Knowledge base / Qdrant collection name.") String kb_name,
            @ToolArg(description = "Must be true to actually drop. With false (the default), only reports what would happen.", required = false, defaultValue = "false") Boolean confirm) {
        if (kb_name == null || kb_name.isBlank()) {
            throw new IngestException("kb_name is required");
        }
        boolean enabled = colpaliPipeline.isEnabledFor(kb_name);
        if (!enabled) {
            return new DropVisualIndexResult(kb_name, false, 0,
                    "KB '" + kb_name + "' has no visual index; nothing to drop.");
        }
        if (confirm == null || !confirm) {
            Long pageCount = colpaliPipeline.getPageCount(kb_name);
            return new DropVisualIndexResult(kb_name, false, 0,
                    "Dry run: would drop visual index for KB '" + kb_name
                            + "' (currently " + pageCount + " pages indexed). "
                            + "Re-call with confirm=true to actually drop.");
        }
        ColPaliPipeline.DropResult result = colpaliPipeline.dropVisualIndex(kb_name);
        return new DropVisualIndexResult(
                kb_name,
                result.collectionDropped(),
                result.filesRemoved(),
                "Dropped visual index for KB '" + kb_name + "': "
                        + (result.collectionDropped() ? "Qdrant collection deleted, " : "")
                        + result.filesRemoved() + " page-image file(s) removed.");
    }
}
