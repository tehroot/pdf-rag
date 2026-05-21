package org.example.ingest;

public record FetchedFile(String filename, String contentType, byte[] content) {

    public long sizeBytes() {
        return content.length;
    }
}
