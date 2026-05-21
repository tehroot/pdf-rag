package org.example.backend.qdrant;

import java.util.Map;

/**
 * One page-level search hit from {@link ColPaliPipeline#searchPages}. Carries
 * enough context for fusion to join with chunk hits on {@code (docId, pageNumber)}
 * and for downstream callers to retrieve the original page image via
 * {@code inspect_page}.
 */
public record PageHit(double score,
                      String docId,
                      int pageNumber,
                      String filename,
                      String source,
                      int textQuality,
                      String pageImageKey,
                      Map<String, Object> payload) {
}
