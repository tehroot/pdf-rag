package org.hayden.backend.qdrant;

/**
 * Storage for rendered page PNGs, kept outside Qdrant to avoid bloating
 * payloads and the WAL on large corpora. Implementations:
 *
 * <ul>
 *   <li>{@link FilesystemPageImageStore} — v1; writes under a local directory.
 *   <li>{@code S3PageImageStore} — vNext; same interface, S3-compatible bucket
 *       backend (RustFS, MinIO, AWS S3, …). Not implemented in v1.
 * </ul>
 *
 * <p>Keys are opaque strings produced by {@code store(...)} and consumed by
 * {@code retrieve(...)}. The format is implementation-specific; callers must
 * not parse them.
 */
public interface PageImageStore {

    /**
     * Persist a rendered page image. Returns the storage key, which callers
     * are expected to record (typically in the Qdrant point payload as
     * {@code page_image_key}) and pass back to {@link #retrieve(String)} later.
     */
    String store(String kbName, String docId, int pageNumber, byte[] pngBytes);

    /**
     * Retrieve the bytes previously stored under {@code key}. Returns null if
     * the key is no longer resolvable (e.g. file was deleted out of band).
     * Implementations should NOT throw on missing keys — let the caller decide
     * how to handle the missing case.
     */
    byte[] retrieve(String key);

    /**
     * Delete every image stored under {@code kbName}. Used by
     * {@code drop_visual_index} to clean up when a KB's visual index is removed.
     * Returns the number of files removed.
     */
    int deleteForKb(String kbName);

    /**
     * Delete every image for a single document within a KB. Useful when
     * re-ingesting a document or removing it explicitly. Returns the number
     * of files removed.
     */
    int deleteForDoc(String kbName, String docId);

    /**
     * Stable identifier for which backend implementation is in use. Lets the
     * dispatcher and admin tools report what's configured.
     */
    String backendName();
}
