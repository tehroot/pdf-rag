package org.hayden.ingest;

/**
 * A stored document can't be overwritten right now: a non-terminal queued job
 * still reads the target path and no immutable snapshot of its bytes exists
 * (hardlinks unavailable — cross-device store, or a filesystem without them).
 * Overwriting would let the job render pages from bytes its chunks never came
 * from. Mapped to HTTP 409 on the REST surface; the message names the job id
 * so the caller can wait it out or use {@code on_conflict=suffix}.
 */
public class SourceConflictException extends IngestException {

    public SourceConflictException(String message) {
        super(message);
    }
}
