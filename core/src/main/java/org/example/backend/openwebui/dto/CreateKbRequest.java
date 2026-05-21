package org.example.backend.openwebui.dto;

import java.util.Map;

public record CreateKbRequest(String name,
                              String description,
                              Map<String, Object> data,
                              Map<String, Object> access_control) {

    public static CreateKbRequest of(String name, String description) {
        return new CreateKbRequest(name,
                description == null ? "" : description,
                Map.of(),
                Map.of());
    }
}
