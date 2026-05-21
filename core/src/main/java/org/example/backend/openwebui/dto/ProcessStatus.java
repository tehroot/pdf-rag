package org.example.backend.openwebui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProcessStatus(String status, String error) {

    public boolean completed() {
        return "completed".equalsIgnoreCase(status);
    }

    public boolean failed() {
        return "failed".equalsIgnoreCase(status);
    }
}
