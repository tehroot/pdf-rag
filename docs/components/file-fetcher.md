# FileFetcher

`core/src/main/java/org/hayden/ingest/FileFetcher.java` (~165 lines). The
input stage of both backends — turns whatever the agent gave us (a URL, a
path, base64 bytes) into a uniform `FetchedFile{filename, contentType, content}`
record.

## What it does

`FileFetcher` is the only piece that talks to the outside world for *input*.
Three source types, one record out:

```
                            ┌─────────────┐
"url"   ──► fromUrl()    ──┤             ├──► FetchedFile{filename,
                            │ FetchedFile │                contentType,
"path"  ──► fromPath()   ──┤             │                content bytes}
                            │             │
"inline"──► fromInline() ──┤             │
                            └─────────────┘
```

Whatever follows (Tika extraction for Qdrant, multipart upload for Open WebUI)
operates on the same uniform shape.

## Interface

```java
public record FetchedFile(String filename, String contentType, byte[] content) {}

public FetchedFile fromUrl(String url, String overrideFilename);
public FetchedFile fromPath(String path, String overrideFilename);
public FetchedFile fromInline(String base64Content, String filename);
```

`overrideFilename` is optional for `url` and `path` (we infer from the URL
path or the file's basename); **required** for `inline` because there's
nothing else to name it.

Config (env overrides shown):

| Key | Env | Default |
|-----|-----|---------|
| `ingest.max-file-bytes` | — | `104857600` (100 MB) |
| `ingest.fetch.connect-timeout-seconds` | — | `10` |
| `ingest.fetch.request-timeout-seconds` | — | `120` |

## Internals

### `fromUrl(url, overrideFilename)`

1. Parse the string into a `URI`. Throws `IngestException` on garbage.
2. Reject anything other than `http` / `https`. The most likely abuse vector
   here is `file://` — explicitly disallowed so a misbehaving agent can't read
   the host filesystem through this path. Use `fromPath` if you actually want a
   local file (and pass an absolute path).
3. Build an `HttpRequest` with `Duration.ofSeconds(requestTimeoutSeconds)`.
4. Send with `HttpResponse.BodyHandlers.ofByteArray()`.
5. Reject non-2xx.
6. Enforce `ingest.max-file-bytes` against `body.length` — too-large fails
   *before* we try to chunk or upload.
7. Compute the filename: caller's override wins; otherwise infer from the URI
   path (the segment after the last `/`).
8. Compute the content type: take the `Content-Type` response header
   (stripping any `; charset=...`); if missing, fall back to an extension
   lookup table.

### `fromPath(path, overrideFilename)`

1. Reject non-absolute paths. (`Path.isAbsolute()`.)
2. Reject non-regular files (no symlinks-to-directories, no special files).
3. Reject unreadable files.
4. Get size, enforce `ingest.max-file-bytes`, then read all bytes.
5. Compute filename and content-type same as `fromUrl` (filename comes from
   the path's basename; content-type from the extension lookup).

### `fromInline(base64Content, filename)`

1. Require non-blank `filename`.
2. `Base64.getDecoder().decode(...)` — invalid base64 raises `IngestException`.
3. Enforce `ingest.max-file-bytes` on the *decoded* size (the caller paid the
   ~4/3 inflation overhead on the wire, we measure the real payload).
4. Content-type from filename extension.

### Content-type sniffing

Static table (lowercased extension → MIME):

| ext | content-type |
|-----|--------------|
| `pdf` | `application/pdf` |
| `txt` | `text/plain` |
| `md` | `text/markdown` |
| `html` / `htm` | `text/html` |
| `json` | `application/json` |
| `csv` | `text/csv` |
| `docx` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` |
| `xlsx` | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` |
| `pptx` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` |
| anything else | `application/octet-stream` |

This table is intentionally short — Tika does proper sniffing downstream, and
the content-type we record is mostly a hint (Tika's `AutoDetectParser` reads
magic bytes regardless). For Open WebUI uploads it's used to set the
multipart-part `Content-Type` header; some Open WebUI versions look at the
declared MIME to pick a parser, so the table covers the formats people
actually ingest.

### `HttpClient` setup

`@PostConstruct init()`:

```java
this.http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
        .build();
```

`HTTP_1_1` is the load-bearing line — same reason as in [embedder.md](embedder.md)
and [qdrant-client.md](qdrant-client.md). `Redirect.NORMAL` means we follow
3xx for same-origin / cross-origin GETs, which most public PDF URLs need.

## Failure modes

| Case | Throws |
|------|--------|
| `url` is malformed | `IngestException("Invalid URL: ...")` |
| `url` scheme not http/https | `IngestException("Only http and https URLs are supported, got: ...")` |
| HTTP non-2xx | `IngestException("Download of ... returned HTTP X")` |
| Network I/O fails | `IngestException("Failed to download ...", IOException)` |
| Download exceeds size cap | `IngestException("File ... is N bytes; exceeds ingest.max-file-bytes=M")` |
| `path` not absolute | `IngestException("Path must be absolute: ...")` |
| `path` not a regular file | `IngestException("Not a regular file: ...")` |
| `path` not readable | `IngestException("File is not readable: ...")` |
| `inline` filename missing | `IngestException("filename is required for inline source")` |
| `inline` base64 invalid | `IngestException("source_value is not valid base64", ...)` |
| Decoded inline exceeds size cap | `IngestException("File ... exceeds ingest.max-file-bytes=M")` |

All failures happen *before* either backend touches its store, so there's no
half-ingested state to clean up.

## Why it's like this

- **One record, three sources.** Both backends ingest the same way after this
  stage; the source-type asymmetry stops here. This keeps `IngestService` /
  `QdrantBackend` / `OpenWebUiBackend` from each having to handle URL fetching.
- **Byte arrays, not streams.** `byte[]` is simpler than a stream for the
  downstream consumers (Tika needs a re-readable input, multipart upload
  computes Content-Length). 100 MB cap keeps memory bounded; if you need huge
  files, the streaming refactor would start here.
- **Hard-coded extension table over content-detection.** We have to ship the
  bytes to Tika regardless, and Tika's own detection is far more reliable than
  ours. The table just provides a reasonable hint for Open WebUI and a fallback
  when the URL response has no `Content-Type`.
- **`file://` is rejected on purpose.** Don't add it back without thinking
  about the security implications — an agent that can pass `file:///etc/passwd`
  becomes a host filesystem reader. If you really want local files, use
  `source_type='path'` with an absolute path.
- **HTTP/1.1 pin.** `java.net.http` defaults to HTTP/2 and sends a cleartext
  h2c upgrade trio on plain HTTP. Some PDF hosts respond to that politely;
  some — usually fronted by uvicorn or older nginx — don't. Pinning is the
  cheap insurance.

## Tests

`FileFetcherTest` (11 tests):

- `fromUrl_returnsBytesAndInfersContentType` — WireMock-served PDF.
- `fromUrl_stripsCharsetFromContentType` — `text/plain; charset=utf-8` → `text/plain`.
- `fromUrl_overrideFilenameWins` — caller's `filename` arg overrides URL inference.
- `fromUrl_non2xxFails`, `fromUrl_rejectsNonHttpScheme` — error paths.
- `fromPath_readsLocalFile`, `fromPath_requiresAbsolutePath` — local files.
- `fromInline_decodesBase64`, `fromInline_requiresFilename`,
  `fromInline_rejectsBadBase64`, `fromInline_enforcesMaxSize` — inline path.

The pattern: construct `FileFetcher` directly, set `@ConfigProperty` fields by
reflection, invoke `init()` reflectively, run a method. No CDI startup — the
class is well-isolated from container plumbing.
