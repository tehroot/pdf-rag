# Dispatcher + Backend interface

`core/src/main/java/org/example/ingest/IngestService.java` (the dispatcher) and
`core/src/main/java/org/example/backend/Backend.java` (the interface).
Two backends today: `QdrantBackend`, `OpenWebUiBackend`. Adding a third means
implementing `Backend` and dropping it on the classpath — nothing else changes.

## What it does

`IngestService` is a thin façade. The MCP tool layer never sees a backend
directly; it calls `IngestService.ingest()` / `.search()` / `.listKnowledgeBases()`
with the request record, and the dispatcher picks an implementation by name.

```
┌──────────────┐  ingest(req)         ┌────────────────┐
│ IngestTools  │ ───────────────────▶ │ IngestService  │
└──────────────┘                      │ (dispatcher)   │
                                      └────────┬───────┘
                                               │ pick(req.backend())
                       ┌───────────────────────┼───────────────────────┐
                       ▼                                               ▼
                 QdrantBackend                                  OpenWebUiBackend
                 (name() = "qdrant")                            (name() = "openwebui")
```

## Interface

### `Backend`

```java
public interface Backend {
    String name();
    IngestResult ingest(IngestRequest req);
    SearchResponse search(SearchRequest req);
    List<KnowledgeBaseSummary> listKnowledgeBases();
}
```

That's the entire contract. Four methods, two of which produce records the
tool layer surfaces verbatim to MCP.

Convention: `name()` returns lowercase. `Backend` implementations are
`@ApplicationScoped` CDI beans and get picked up automatically by the
container; no registration step.

### `IngestService`

```java
@ApplicationScoped
public class IngestService {
    @Inject Instance<Backend> backends;
    @ConfigProperty(name = "ingest.backend.default") String defaultBackend;

    public IngestResult ingest(IngestRequest req)   { return pick(req.backend()).ingest(req); }
    public SearchResponse search(SearchRequest req) { return pick(req.backend()).search(req); }

    public List<KnowledgeBaseSummary> listKnowledgeBases(String backendName) {
        if (backendName == null || backendName.isBlank() || "all".equalsIgnoreCase(backendName)) {
            // merge across all backends
            ...
        }
        return pick(backendName).listKnowledgeBases();
    }

    Backend pick(String requested) {
        String wanted = (requested == null || requested.isBlank()) ? defaultBackend : requested;
        for (Backend b : backends) if (b.name().equalsIgnoreCase(wanted)) return b;
        throw new IngestException("Unknown backend '" + wanted + "' (known: ...)");
    }
}
```

## Internals

### Backend resolution

`pick(String requested)` is the heart of the dispatcher:

1. If `requested` is null/blank → fall back to `ingest.backend.default` (env
   `INGEST_BACKEND`, default `qdrant`).
2. Case-insensitive match against `Backend.name()`.
3. If nothing matches, throw `IngestException("Unknown backend 'X' (known: ...)")`
   — the list of registered backends is included so the error is actionable.

The match is `equalsIgnoreCase`, so the agent can pass `"Qdrant"` or `"QDRANT"`
and it still works.

### CDI `Instance<Backend>`

`@Inject Instance<Backend>` is how CDI hands you "all beans that implement this
interface". It's not a `List` — it's a lazy iterable that re-resolves per call,
which means a Backend implementation added later (e.g. for tests) shows up
without restarting CDI. We iterate with the enhanced-for loop; performance is
fine because the registry is small (two entries today).

### Merge mode for `list_knowledge_bases`

`listKnowledgeBases(backendName)` has a special "all backends" mode. Anything
falsy (null / blank / "all") iterates every backend and flattens the results.
Each `KnowledgeBaseSummary` carries its own `backend` field so callers can tell
where a KB lives:

```json
[
  {"backend": "qdrant",   "id": "engineering-docs", "name": "engineering-docs", "vectors": 1024, "dim": 1024},
  {"backend": "openwebui", "id": "kb-1234",         "name": "Reports",          "vectors": null, "dim": null}
]
```

If `backendName` is a specific name, scope to just that backend's list.

## Failure modes

- **Unknown backend** → `IngestException("Unknown backend 'X' (known: [qdrant, openwebui])")`.
  The list in the message is built from the live `Instance<Backend>` iteration,
  so it tells you exactly which names are registered right now.
- **Backend-internal failures** — anything a backend throws bubbles up through
  the dispatcher untouched. The dispatcher doesn't try to translate or retry.
- **`search` on `openwebui`** — `OpenWebUiBackend.search()` throws
  `IngestException("Backend 'openwebui' does not support direct search. ...")`.
  The dispatcher delegates first, then sees the throw, so the error reaches
  the agent verbatim.

## Why it's like this

- **`Backend` as an interface, not an abstract class.** Implementations share
  almost no code (the Qdrant path is fundamentally different from the Open
  WebUI path), so a base class would force artificial commonality. The shared
  bits — `FileFetcher`, request/response records — live in `org.example.ingest`
  and each backend injects what it needs.
- **CDI discovery instead of an enum or factory.** Adding a Redis backend
  means: implement `Backend`, mark `@ApplicationScoped`, return `name() =
  "redis"`. No registration table, no switch statement, no service-loader file.
  This is the part that scales.
- **`Instance<Backend>` over `Set<Backend>`.** With a `Set` injection, CDI
  resolves once at startup; `Instance` re-resolves per call, which keeps tests
  trivial (you can construct an `IngestService` and hand it your own
  `Instance` stub, exactly what `IngestServiceTest` does).
- **Per-call backend arg + env default.** Per-call lets the agent target a
  specific backend without restarting; env default keeps the common case
  trivial. The two compose well: leave `INGEST_BACKEND=qdrant`, and tools that
  don't care just work.
- **No retry, no fallback, no timeouts here.** Those are backend concerns
  (Open WebUI's poll loop, Qdrant's request timeout). The dispatcher should
  remain dumb — it's load-bearing precisely because there's nothing in it to
  break.

## Tests

`IngestServiceTest` (8 tests) covers:

- `ingest_routesByExplicitBackendArg` — `"openwebui"` arg → `OpenWebUiBackend` only.
- `ingest_routesToDefaultWhenNull` / `…WhenBlank` — null and `"   "` both fall back to `defaultBackend`.
- `ingest_rejectsUnknownBackend` — `"redis"` raises `IngestException`.
- `search_routesByExplicitBackendArg` — same dispatch logic for `search()`.
- `listKnowledgeBases_mergesAcrossBackends_whenAllRequested` / `…_whenNoneSpecified` /
  `…_scopesToOneBackend` — the three modes of `list_knowledge_bases`.

The test constructs two `FakeBackend` instances (an inner class) and wraps
them in a small `TestInstance<T>` that implements `jakarta.enterprise.inject.Instance<T>`
by delegating to a `List`. No `@QuarkusTest`, no CDI startup; runs in ~30 ms.

If you add a new method to `Backend`, add a routing test to this class — it's
the only place the dispatch behavior is exercised in isolation.
