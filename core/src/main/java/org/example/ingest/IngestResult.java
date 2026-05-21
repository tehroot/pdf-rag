package org.example.ingest;

import java.util.List;

public record IngestResult(
        String backend,
        String kbId,
        String kbName,
        String fileId,
        String processingStatus,
        int chunkCount,
        int pageCount,
        boolean addedToKb,
        String message,
        List<String> warnings,
        String jobId) {

    /** Back-compat: no jobId. Used by sync ingest paths and the test suite. */
    public IngestResult(String backend, String kbId, String kbName, String fileId,
                        String processingStatus, int chunkCount, int pageCount,
                        boolean addedToKb, String message, List<String> warnings) {
        this(backend, kbId, kbName, fileId, processingStatus, chunkCount, pageCount,
                addedToKb, message, warnings, null);
    }

    /** Back-compat: no warnings, no pageCount, no jobId. */
    public IngestResult(String backend, String kbId, String kbName, String fileId,
                        String processingStatus, int chunkCount, boolean addedToKb,
                        String message) {
        this(backend, kbId, kbName, fileId, processingStatus, chunkCount, 0, addedToKb,
                message, List.of(), null);
    }

    /** Construct a "queued" result that the agent can use to poll get_ingest_status. */
    public static IngestResult queued(String backend, String kbName, String docId, String jobId) {
        return new IngestResult(
                backend,
                kbName,
                kbName,
                docId,
                "queued",
                0,
                0,
                false,
                "Queued for ingestion as job " + jobId + "; poll get_ingest_status to track progress",
                List.of(),
                jobId);
    }
}
