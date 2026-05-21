package org.hayden.backend.openwebui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeBase(String id, String name, String description) {
}
