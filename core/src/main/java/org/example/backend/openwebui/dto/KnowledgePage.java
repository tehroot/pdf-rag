package org.example.backend.openwebui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgePage(List<KnowledgeBase> items, int total) {
}
