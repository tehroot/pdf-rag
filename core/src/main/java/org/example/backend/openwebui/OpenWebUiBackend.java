package org.example.backend.openwebui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.example.backend.Backend;
import org.example.backend.KnowledgeBaseSummary;
import org.example.backend.openwebui.dto.KnowledgeBase;
import org.example.backend.openwebui.dto.ProcessStatus;
import org.example.backend.openwebui.dto.UploadedFile;
import org.example.ingest.FetchedFile;
import org.example.ingest.FileFetcher;
import org.example.ingest.IngestException;
import org.example.ingest.IngestRequest;
import org.example.ingest.IngestResult;
import org.example.ingest.SearchRequest;
import org.example.ingest.SearchResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class OpenWebUiBackend implements Backend {

    public static final String NAME = "openwebui";

    @Inject
    FileFetcher fetcher;

    @Inject
    KnowledgeService kbService;

    @Inject
    FileUploadService uploadService;

    @Inject
    OpenWebUiClient client;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public IngestResult ingest(IngestRequest req) {
        FetchedFile file = switch (req.sourceType()) {
            case URL -> fetcher.fromUrl(req.sourceValue(), req.filename());
            case PATH -> fetcher.fromPath(req.sourceValue(), req.filename());
            case INLINE -> fetcher.fromInline(req.sourceValue(), req.filename());
        };

        KnowledgeBase kb = kbService.findOrCreate(req.kbName(), req.kbDescription());

        UploadedFile uploaded = uploadService.upload(file.filename(), file.contentType(), file.content());

        ProcessStatus status = uploadService.waitUntilProcessed(
                uploaded.id(),
                Duration.ofSeconds(req.pollTimeoutSeconds()));

        if (status.failed()) {
            return new IngestResult(NAME, kb.id(), kb.name(), uploaded.id(),
                    status.status(), 0, false,
                    "Open WebUI failed to process the file: "
                            + (status.error() == null ? "no error message" : status.error()));
        }

        client.addFileToKnowledgeBase(kb.id(), uploaded.id());
        return new IngestResult(NAME, kb.id(), kb.name(), uploaded.id(),
                status.status(), 0, true,
                "Added file " + uploaded.id() + " to knowledge base '" + kb.name() + "'");
    }

    @Override
    public SearchResponse search(SearchRequest req) {
        throw new IngestException(
                "Backend 'openwebui' does not support direct search. "
                        + "Use Open WebUI's chat/RAG endpoints instead, or switch to the 'qdrant' backend.");
    }

    @Override
    public List<KnowledgeBaseSummary> listKnowledgeBases() {
        List<KnowledgeBaseSummary> out = new ArrayList<>();
        for (KnowledgeBase kb : client.listKnowledgeBases()) {
            out.add(new KnowledgeBaseSummary(NAME, kb.id(), kb.name(), null, null));
        }
        return out;
    }

    public ProcessStatus getFileStatus(String fileId) {
        return client.getFileProcessStatus(fileId);
    }
}
