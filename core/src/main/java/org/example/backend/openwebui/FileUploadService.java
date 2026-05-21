package org.example.backend.openwebui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.backend.openwebui.dto.ProcessStatus;
import org.example.backend.openwebui.dto.UploadedFile;

import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class FileUploadService {

    @Inject
    OpenWebUiClient client;

    @ConfigProperty(name = "ingest.openwebui.poll.initial-ms", defaultValue = "200")
    long initialPollMs;

    @ConfigProperty(name = "ingest.openwebui.poll.max-ms", defaultValue = "2000")
    long maxPollMs;

    public UploadedFile upload(String filename, String contentType, byte[] content) {
        return client.uploadFile(filename, contentType, content);
    }

    public ProcessStatus waitUntilProcessed(String fileId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        long sleepMs = initialPollMs;
        while (true) {
            ProcessStatus status = client.getFileProcessStatus(fileId);
            if (status.completed() || status.failed()) {
                return status;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new OpenWebUiException(-1,
                        "Timed out after " + timeout + " waiting for file " + fileId
                                + " (last status=" + status.status() + ")");
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OpenWebUiException("Interrupted while polling file " + fileId, e);
            }
            sleepMs = Math.min(maxPollMs, sleepMs * 2);
        }
    }
}
