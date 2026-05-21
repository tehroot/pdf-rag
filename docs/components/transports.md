# Transport modules (`server-stdio`, `server-http`)

Two near-empty Maven modules that wrap `core` with one of Quarkus MCP's
mutually-exclusive transport artifacts. Combined, they expose the same
`IngestTools` over two protocols.

## What they do

Each module is a Quarkus application that:

1. Depends on `pdf-rag-ingest-core` (the JAR with all the `@Tool`-annotated
   beans).
2. Depends on exactly one of `quarkus-mcp-server-stdio` or
   `quarkus-mcp-server-http`.
3. Has an `application.properties` that tells Quarkus to index the `core` JAR
   for CDI bean discovery (the Jandex incantation).
4. Has *zero* Java source files of its own.

Run as a fat jar (`target/quarkus-app/quarkus-run.jar`), they become standalone
MCP servers — the agent connects and gets the four tools.

## server-stdio

The stdio transport: the MCP host launches the jar as a child process, JSON-RPC
frames flow over stdin/stdout.

### Layout

```
server-stdio/
├── pom.xml                       core + quarkus-mcp-server-stdio
└── src/main/resources/
    └── application.properties    Quarkus + index-dependency + stdio discipline
```

### pom.xml

```xml
<dependencies>
  <dependency>
    <groupId>org.hayden</groupId>
    <artifactId>pdf-rag-ingest-core</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkiverse.mcp</groupId>
    <artifactId>quarkus-mcp-server-stdio</artifactId>
  </dependency>
</dependencies>
```

That's the entire dependency set. The Quarkus plugin handles building the
fast-jar layout.

### application.properties

```properties
quarkus.application.name=pdf-rag-ingest-stdio

# Make Quarkus index the core JAR so its @Tool / @ApplicationScoped beans
# are discovered. Without this, tools/list returns []. See "Jandex" below.
quarkus.index-dependency.core.group-id=org.hayden
quarkus.index-dependency.core.artifact-id=pdf-rag-ingest-core

# stdio transport: do not emit Quarkus banner / colored logs to stdout —
# clients read JSON-RPC frames from stdout. Route logs to stderr instead.
quarkus.banner.enabled=false
quarkus.log.console.stderr=true
quarkus.log.level=INFO

ingest.openwebui.base-url=${OPEN_WEBUI_BASE_URL:http://localhost:3000}
ingest.openwebui.api-key=${OPEN_WEBUI_API_KEY:}
```

Three things to notice:

1. **`quarkus.index-dependency.core.*`** — `core` is a regular JAR dependency,
   and Quarkus' Arc (CDI) only scans the application module by default. This
   tells Arc to also scan `core`'s `META-INF/jandex.idx`.
2. **`quarkus.banner.enabled=false`** — Quarkus prints a multi-line banner at
   startup by default. To stdout. Which would corrupt the JSON-RPC frame
   stream. Off.
3. **`quarkus.log.console.stderr=true`** — Routes Quarkus' JBoss Log Manager
   to stderr. JSON-RPC framing on stdout, logs on stderr, total separation.

### How it runs

The jar is not a daemon. The MCP host (Claude Desktop, Cline, mcp-cli, etc.)
spawns it as a child process and connects to its stdin/stdout:

```
host           server-stdio jar
  │                  │
  │ spawn            │
  │ ───────────────► │  (process starts, Quarkus boots, MCP listener attaches to stdin)
  │                  │
  │ <JSON-RPC>       │
  │ ───────────────► │  initialize
  │ <JSON-RPC>       │
  │ ◄─────────────── │  initialize response
  │ <JSON-RPC>       │
  │ ───────────────► │  notifications/initialized
  │ <JSON-RPC>       │
  │ ───────────────► │  tools/call ingest_document
  │ <JSON-RPC>       │
  │ ◄─────────────── │  tool result
  │ ...              │
```

When the host closes stdin, Quarkus shuts down. There's no port, no service
discovery, no auth (the host IS the auth boundary — it spawned the process).

## server-http

The Streamable HTTP transport: the jar listens on `:PORT/mcp` and serves
JSON-RPC over Server-Sent Events + POSTs.

### Layout

```
server-http/
├── pom.xml                       core + quarkus-mcp-server-http
└── src/main/resources/
    └── application.properties    Quarkus + index-dependency + CORS + port
```

### pom.xml

```xml
<dependencies>
  <dependency>
    <groupId>org.hayden</groupId>
    <artifactId>pdf-rag-ingest-core</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkiverse.mcp</groupId>
    <artifactId>quarkus-mcp-server-http</artifactId>
  </dependency>
</dependencies>
```

### application.properties

```properties
quarkus.application.name=pdf-rag-ingest-http

quarkus.index-dependency.core.group-id=org.hayden
quarkus.index-dependency.core.artifact-id=pdf-rag-ingest-core

quarkus.http.host=0.0.0.0
quarkus.http.port=${PORT:8080}

# Streamable HTTP MCP servers must serve CORS preflight to browser-based agents.
quarkus.http.cors.enabled=true
quarkus.http.cors.origins=${MCP_CORS_ORIGINS:*}

ingest.openwebui.base-url=${OPEN_WEBUI_BASE_URL:http://localhost:3000}
ingest.openwebui.api-key=${OPEN_WEBUI_API_KEY:}
```

Differences from stdio:

- **Banner stays on, logs go to stdout.** It's a long-running service; usual
  Quarkus log conventions apply.
- **HTTP-specific config** — `quarkus.http.host`, `quarkus.http.port`, CORS.
- **CORS is enabled.** Streamable HTTP MCP clients are often browsers (e.g.
  LibreChat, AnythingLLM); they need preflight responses. `MCP_CORS_ORIGINS=*`
  is fine for local dev; tighten for production.

### Endpoint

The MCP endpoint is `:PORT/mcp`. The Quarkus MCP extension wires this
automatically — there's no controller/JAX-RS endpoint defined in this module.

### How it runs

```bash
java -jar server-http/target/quarkus-app/quarkus-run.jar
# now listens on 0.0.0.0:8080
# clients connect to http://localhost:8080/mcp
```

Long-lived process. No auth at the MCP layer (any reachable caller can invoke
tools). For production deployments, put it behind a reverse proxy with TLS +
basic auth or a static bearer token. See [../deployment.md](../deployment.md)
for the systemd unit pattern.

## Why two modules instead of one

`quarkiverse-mcp-server` 1.x publishes stdio and HTTP as separate Maven
artifacts (`quarkus-mcp-server-stdio` and `quarkus-mcp-server-http`). They
register competing protocol handlers — you can't load both into one Quarkus
build. The choices:

1. **Build flavors via Maven profiles.** Cumbersome; CI builds two times
   anyway.
2. **Conditional bean activation.** Fights the framework; transport-specific
   wiring isn't easy to factor out.
3. **Two modules.** What we do. Each is ~15 lines of pom + ~15 lines of
   properties; the actual work lives in `core`.

The third option also makes deployments cleaner — you ship the jar you need,
not both.

## Jandex — why the index-dependency lines matter

Quarkus Arc (CDI) scans the application module's classes for beans
(`@ApplicationScoped`, `@Inject`, etc.) at build time and bakes a registry
into the native image / fast-jar. It only scans *external* JARs if you tell
it to.

`core` is an external JAR from each transport module's perspective. To make
it scannable:

1. `core/pom.xml` runs `jandex-maven-plugin` (via the parent's
   `pluginManagement`), which writes a `META-INF/jandex.idx` into the
   resulting JAR — a binary index of every class, annotation, and reference.
2. Each transport module's `application.properties` has the
   `quarkus.index-dependency.core.*` lines that say "yes, scan this dep at
   build time."

Drop either piece and the symptoms are identical: `tools/list` returns `[]`,
the build succeeds, the app starts, the agent sees no tools.

## Editing rule

**To add or change a tool, edit `core/` only.** Both transports pick it up via
CDI. The only reason to touch a transport module is transport-level config
(CORS origins, port, log routing).

## Tests

No tests in the transport modules. They contain no Java code — only
configuration. Verification is implicit: if the multi-module build succeeds
and `target/quarkus-app/quarkus-run.jar` exists, the wiring is correct.

Smoke testing instructions are in [../deployment.md](../deployment.md) and
[../mcp-integration.md](../mcp-integration.md).
