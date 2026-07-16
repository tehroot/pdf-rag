package org.hayden.ingest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Response body for {@code GET /kb}: every knowledge base with its stats,
 * plus roll-ups. {@code totalDocuments} sums the per-KB distinct-document
 * counts (KBs whose backend can't report a count contribute 0).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KnowledgeBaseListResponse(
        int count,
        long totalDocuments,
        List<KnowledgeBaseStatus> knowledgeBases) {
}
