package org.hayden.ingest;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import org.hayden.backend.KnowledgeBaseSummary;

/**
 * One KB row in the REST status surface ({@code GET /kb}): the
 * {@link KnowledgeBaseSummary} fields plus a distinct-document count.
 * JSON is snake_case to match the rest of the REST/MCP surface.
 *
 * <p>{@code documentCount} is null when the backend can't report it (Open
 * WebUI) — and saturates at the Qdrant facet limit for very large KBs (see
 * {@code QdrantClient.countDocuments}).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record KnowledgeBaseStatus(
        String backend,
        String name,
        Long documentCount,
        Long vectors,
        Integer dim,
        Boolean visualIndexEnabled,
        Long visualIndexPages) {

    public static KnowledgeBaseStatus of(KnowledgeBaseSummary s, Long documentCount) {
        return new KnowledgeBaseStatus(
                s.backend(), s.name(), documentCount, s.vectors(), s.dim(),
                s.visualIndexEnabled(), s.visualIndexPages());
    }
}
