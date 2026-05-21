# PageImageStore

`core/.../backend/qdrant/PageImageStore.java` (interface) +
`FilesystemPageImageStore.java` (v1 impl). Storage for the rendered page
PNGs that `ColPaliPipeline` produces at ingest. Kept **outside Qdrant** to
avoid bloating payloads and the WAL on large corpora.

## Why an abstraction

A 100-page PDF at 150 DPI is ~10-20 MB of PNG. Multiply by hundreds of
documents and Qdrant's WAL + snapshots become unwieldy. Out-of-band storage:

- Filesystem (v1) — single host, simple, fast.
- S3-compatible blob storage (vNext) — multi-host, cloud-ready. RustFS / MinIO
  / AWS S3 / Backblaze B2 are interchangeable behind the same interface.

The Qdrant payload stores only a `page_image_key` (an opaque string); the
store turns that key into bytes on demand.

## Interface

```java
public interface PageImageStore {
    /** Persist a PNG; returns the opaque key callers should store and use to retrieve. */
    String store(String kbName, String docId, int pageNumber, byte[] pngBytes);

    /** Retrieve by key; null on missing (don't throw — let caller decide). */
    byte[] retrieve(String key);

    /** Delete every image stored under a KB; returns count of files removed. */
    int deleteForKb(String kbName);

    /** Delete every image for a single doc within a KB. */
    int deleteForDoc(String kbName, String docId);

    /** Stable identifier — used for telemetry / admin reporting. */
    String backendName();
}
```

`retrieve` returns null instead of throwing for missing keys because the
"file was deleted out of band" case is normal — `inspect_page` handles it
gracefully.

## `FilesystemPageImageStore` — v1 impl

### Layout

```
${ingest.page_store.root}/
  <kb_name>/
    <doc_id>/
      000001.png
      000002.png
      ...
      000NNN.png
```

- Per-KB directories so `deleteForKb` is `rm -rf <kb_name>/`.
- Per-doc directories let `deleteForDoc` be just as clean.
- Page numbers zero-padded to 6 digits (`000007.png`) for filesystem sort
  sanity. `ls -1` lists in numeric order even with 1000-page docs.
- Default root: `${user.home}/.pdf-rag-ingest/page-images`. Override via
  `INGEST_PAGE_STORE_ROOT` env.

### Atomic writes

```java
Path tmp = Files.createTempFile(target.getParent(), name + ".", ".tmp");
Files.write(tmp, pngBytes);
Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING);
```

Tmp file + atomic rename. Avoids the case where a concurrent reader sees a
half-written PNG. Rare in practice (the same KB+doc+page isn't usually
written concurrently) but cheap insurance.

### Path-traversal defense

`sanitizeKb` / `sanitizeDoc` reject `..`, `.`, `/`, `\` in component names.
`resolveSafe(key)` normalizes the resolved path and refuses anything that
walks outside the root.

```java
Path resolved = root.resolve(key).normalize();
if (!resolved.startsWith(root)) return null;
```

So a crafted `key = "../../etc/passwd"` returns null from `retrieve` instead
of dumping system files.

### Config-impl guard

At init, the impl checks `ingest.page_store.impl` and throws if anything
other than `"filesystem"` is requested. v1 only supports filesystem; when
S3 lands the check moves to a factory.

## Failure modes

| Case | Result |
|------|--------|
| Empty PNG bytes | `IngestException("Cannot store empty PNG ...")`. |
| Null PNG bytes | `IngestException`. |
| Page number < 1 | `IngestException("page_number must be >= 1")`. |
| Invalid kbName (slash, traversal, dot) | `IngestException("kb name contains unsafe characters ...")`. |
| Invalid docId | `IngestException("doc_id contains unsafe characters ...")`. |
| Filesystem write fails (disk full, perms) | `IngestException("Failed to write page image to ...", IOException)`. |
| Missing key on retrieve | Returns null (not an exception). |
| Path-traversal key on retrieve | Returns null. |
| `deleteForKb` on missing directory | Returns 0 (idempotent). |
| Unsupported `ingest.page_store.impl` at init | `IngestException("not supported in v1")` — startup fails fast. |

## Why it's like this

- **Filesystem is the right v1 default.** Single-host deployments are most
  common; filesystem is fast, no extra deps, easy to back up. S3 is the
  natural next step but adds complexity (auth, region, multi-part upload)
  that isn't justified for a v1.
- **`store` returns the key.** Callers don't construct keys; they accept
  whatever the store returns. Lets future impls use different formats
  (S3 object keys, content-addressable hashes, etc.) without API change.
- **Atomic writes.** Cheap (one extra rename). Avoids a small but real class
  of concurrent-reader bugs.
- **Sanitization.** kbName and docId originate from user-controlled input;
  treating them as raw filesystem path components is a path-traversal risk.
  The sanitizer rejects rather than silently rewriting — explicit failure is
  preferable to ambiguous behavior.
- **`retrieve` returns null instead of throwing.** The "missing file" case
  isn't exceptional — it happens when a KB is dropped or when a page image
  is manually removed for cleanup. The tool layer turns missing into a
  human-readable error message; throwing here would just shift that to the
  catch-rethrow pattern everywhere.

## Tests

`FilesystemPageImageStoreTest` (15 tests). Each test creates a tmp dir and
cleans it up.

- Store + retrieve round-trip.
- Overwrite-on-same-key.
- `deleteForDoc` removes only that doc's pages.
- `deleteForKb` removes the whole KB tree; unrelated KBs survive.
- Idempotent: `deleteForKb` on missing KB returns 0.
- Rejects empty/null PNGs.
- Rejects invalid KB names (`..`, `kb/with/slash`, `.`, empty).
- Rejects invalid doc IDs.
- Rejects non-positive page numbers.
- Path-traversal keys on retrieve return null.
- Unsupported impl at init throws.
- Backend name is reported correctly.
- Page numbers are zero-padded to six digits.

The path-traversal test is particularly load-bearing — making sure a
malicious key can't walk outside root is the security property.
