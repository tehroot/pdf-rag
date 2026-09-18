package org.hayden.jobs;

/**
 * What a queued job actually runs.
 *
 * <p>{@code FULL} is the legacy shape: both pipelines (text + visual) inside
 * one job. Since the visual split, nothing submits FULL jobs — the text side
 * runs synchronously at submit and only {@code VISUAL} work is queued — but
 * jobs persisted before an upgrade deserialize without a kind and are
 * normalized to FULL so they recover through the original path.
 */
public enum JobKind {
    /** Legacy: run text + visual pipelines in the worker. */
    FULL,
    /** Visual side only: render → VLM embed → upsert pages. */
    VISUAL
}
