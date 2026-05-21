package org.example.backend.openwebui;

public class OpenWebUiException extends RuntimeException {

    private final int statusCode;

    public OpenWebUiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public OpenWebUiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public int statusCode() {
        return statusCode;
    }
}
