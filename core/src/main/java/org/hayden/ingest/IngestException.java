package org.hayden.ingest;

public class IngestException extends RuntimeException {

    public IngestException(String message) {
        super(message);
    }

    public IngestException(String message, Throwable cause) {
        super(message, cause);
    }
}
