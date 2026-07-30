package org.hayden.ingest;

/**
 * The ColPali sidecar is down or not yet ready. Thrown on the queue-worker
 * path so {@link org.hayden.jobs.IngestWorker} can treat the failure as
 * transient — requeue the job without a retry penalty and back off — instead
 * of permanently failing it. Sidecar-down is a property of the environment,
 * not of the job: a queued visual job is still perfectly ingestable once the
 * sidecar returns.
 *
 * <p>The synchronous ingest path deliberately does NOT use this type: an
 * interactive caller with the sidecar down gets an immediate hard failure
 * (plain {@link IngestException}) they can act on.
 */
public class SidecarUnavailableException extends IngestException {

    public SidecarUnavailableException(String message) {
        super(message);
    }
}
