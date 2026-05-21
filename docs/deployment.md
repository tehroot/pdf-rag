# Deployment

> **Note:** This doc covers the pre-fusion deployment story (Qdrant + llama-server
> + Java MCP server). The fusion design adds a Python ColPali sidecar as a fourth
> service. For a quick visual-enabled stack, the repo root has a
> `docker-compose.yml` that brings up Qdrant + llama-server + colpali-server +
> pdf-rag-http in one go (`docker compose up -d`). Full architecture is in
> [architecture.md](architecture.md); sidecar specifics are in
> [components/colpali-sidecar.md](components/colpali-sidecar.md). This doc itself
> still needs a refresh pass to integrate the visual side into every section.

How to build, configure, and run pdf-rag-ingest with the Qdrant backend (the
default) and optionally the Open WebUI backend. There are two runnable artifacts
(one per transport); pick whichever your MCP client supports — they expose the
same tools and read the same env vars.

## Prerequisites

| What | Why | Note |
|------|-----|------|
| JDK 21+ | Compiles to Java 21 bytecode. | Newer JDKs work; we target `--release 21`. |
| Maven 3.9.x | Quarkus 3.33 wants ≥ 3.9.6. | The repo uses `mvnvm` (auto-pins 3.9.9). Any installed `mvn` ≥ 3.9.6 works too. |
| **Qdrant** (Qdrant backend) | Vector store. | Local Docker is fine; **pin to `qdrant/qdrant:v1.13.x`** for the multivector + multistage query API we use. |
| **Embeddings endpoint** (Qdrant backend) | OpenAI-compatible `/v1/embeddings`. | llama.cpp's `llama-server` (started with `--embeddings`) is the project default. vLLM / OpenAI / Together / LM Studio also work over the same OpenAI shape. |
| **ColPali sidecar** (Qdrant backend, optional but default-on) | Visual-side embeddings. | Python service in `sidecar/`. CPU image with ColSmolVLM or GPU image with ColQwen2. See `sidecar/README.md` and `components/colpali-sidecar.md`. |
| **Open WebUI** (legacy backend, optional) | If you still want the Open WebUI target. | 0.9.x; URL in `OPEN_WEBUI_BASE_URL`. |

### Local infrastructure for the Qdrant backend

The cheapest setup that exercises the full default pipeline:

```bash
# Qdrant
docker run -p 6333:6333 -v qdrant-data:/qdrant/storage qdrant/qdrant:latest

# llama-server with an embedding GGUF. The --embeddings flag is required so
# that /v1/embeddings is wired up. --port 8081 avoids the conflict with our
# HTTP transport (which also defaults to :8080 → :8081 / :8080 here).
docker run -p 8081:8081 \
  -v $PWD/models:/models \
  ghcr.io/ggml-org/llama.cpp:server \
  -m /models/bge-large-en-v1.5-f16.gguf \
  --embeddings \
  --host 0.0.0.0 \
  --port 8081
```

If you have a GPU, add `--gpus all` and use the `:server-cuda` image. If you
prefer a different backend, anything that speaks OpenAI-compatible
`/v1/embeddings` works — vLLM (`vllm/vllm-openai:latest --task embedding`),
[LM Studio's server mode](https://lmstudio.ai), or vanilla OpenAI. Just point
`EMBED_BASE_URL` at it.

## Build

```bash
mvn package                            # build everything (core + both transports); runs all tests
mvn package -DskipTests                # skip the test phase
mvn -pl core test                      # core tests only — no external services needed
mvn -pl core test -Dtest=ChunkerTest                                  # one class
mvn -pl core test -Dtest=QdrantClientTest#search_passesFilterAndParsesHits  # one method
mvn -pl server-http -am package -DskipTests   # rebuild just one transport (and its deps via -am)
```

Produces:

| Module | Output | Notes |
|--------|--------|-------|
| `core/` | `core/target/pdf-rag-ingest-core-1.0-SNAPSHOT.jar` | Library only; no entry point. Indexed for CDI (Jandex). |
| `server-stdio/` | `server-stdio/target/quarkus-app/quarkus-run.jar` | Quarkus fast-jar layout — the app is the *directory*. |
| `server-http/`  | `server-http/target/quarkus-app/quarkus-run.jar`  | Same layout. |

## Configuration

All configuration is via environment variables; defaults are in
`core/src/main/resources/application.properties`. Anything in the properties
file can be overridden by the matching uppercased `_`-separated env var.

### Core / backend selection

| Env var | Required | Default | Purpose |
|---------|----------|---------|---------|
| `INGEST_BACKEND` | no | `qdrant` | `qdrant` or `openwebui`. Used when a tool call omits `backend`. |

### Qdrant backend

| Env var | Required | Default | Purpose |
|---------|----------|---------|---------|
| `QDRANT_URL` | yes (in practice) | `http://localhost:6333` | Qdrant REST root, no trailing slash. |
| `QDRANT_API_KEY` | no | *(empty)* | Sent as `api-key` header (Qdrant Cloud). Empty for local. |
| `EMBED_BASE_URL` | yes | `http://localhost:8081/v1` | OpenAI-compatible root. Default port matches llama-server's typical setup. |
| `EMBED_API_KEY` | no | *(empty)* | `Authorization: Bearer …` if non-empty. |
| `EMBED_MODEL` | yes | `bge-large-en-v1.5` | Whatever model your endpoint serves. |
| `EMBED_BATCH_SIZE` | no | `64` | Batch size per `/embeddings` call. |
| `INGEST_CHUNK_SIZE_CHARS` | no | `1500` | Max chunk size in characters. |
| `INGEST_CHUNK_OVERLAP_CHARS` | no | `200` | Adjacent-chunk overlap. |

### Open WebUI backend (only needed if `INGEST_BACKEND=openwebui` or the agent passes `backend='openwebui'`)

| Env var | Required | Default | Purpose |
|---------|----------|---------|---------|
| `OPEN_WEBUI_BASE_URL` | yes | `http://localhost:3000` | Open WebUI root URL. |
| `OPEN_WEBUI_API_KEY` | yes | *(empty)* | `Authorization: Bearer …` on every call. |

### HTTP transport

| Env var | Required | Default | Purpose |
|---------|----------|---------|---------|
| `PORT` | no | `8080` | Listen port. |
| `MCP_CORS_ORIGINS` | no | `*` | CORS allow-list. Restrict for production. |

## Run

### stdio transport — spawned per session by an MCP client

The stdio jar is **not** a daemon. It reads JSON-RPC framed messages on stdin
and writes replies on stdout. MCP clients spawn it as a child process when they
need it.

```bash
# Minimum env for Qdrant backend
export QDRANT_URL=http://localhost:6333
export EMBED_BASE_URL=http://localhost:8081/v1
export EMBED_MODEL=bge-large-en-v1.5
java -jar server-stdio/target/quarkus-app/quarkus-run.jar
```

If you run this in a terminal it sits there waiting for input — that's correct.
See [mcp-integration.md](mcp-integration.md) for client wiring.

### Streamable HTTP transport — long-lived service at `:PORT/mcp`

```bash
export QDRANT_URL=http://localhost:6333
export EMBED_BASE_URL=http://localhost:8081/v1
export EMBED_MODEL=bge-large-en-v1.5
java -jar server-http/target/quarkus-app/quarkus-run.jar
# now listens on 0.0.0.0:8080, MCP endpoint at http://localhost:8080/mcp
```

Live-reload during development:

```bash
mvn -pl server-http quarkus:dev
```

Quarkus dev mode picks up code changes in `core/` too — edit a tool, save,
reconnect the MCP client; no rebuild needed.

### Useful one-liner: end-to-end smoke test via stdio

```bash
( printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
  printf '%s\n' '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_knowledge_bases","arguments":{}}}'
  sleep 2
) | java -jar server-stdio/target/quarkus-app/quarkus-run.jar 2>/dev/null
```

`list_knowledge_bases` (with the default `backend=all`) lists Qdrant collections
*and* Open WebUI KBs in one shot — cheapest "is everything wired up?" check.
Per-backend scope: pass `arguments:{"backend":"qdrant"}` or `"openwebui"`.

## Packaging considerations

### Quarkus fast-jar layout

The "jar" Quarkus produces under `target/quarkus-app/` is a small runner pointing
at sibling directories: `lib/` (third-party jars), `app/` (your code),
`quarkus/` (generated metadata). To deploy:

- Copy the **entire** `target/quarkus-app/` directory, not just `quarkus-run.jar`.
- Or build the uber-jar: add `quarkus.package.jar.type=uber-jar` to the
  transport module's `application.properties` for a single file. Note: with
  Tika in the dep tree this jar is ~70 MB.

### Container image (pattern; not yet checked in)

```dockerfile
# --- build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./mvnw -B -DskipTests package      # or mvn / mvnvm if installed in the image

# --- runtime: stdio ---
FROM eclipse-temurin:21-jre AS stdio
WORKDIR /app
COPY --from=build /src/server-stdio/target/quarkus-app /app
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]

# --- runtime: http ---
FROM eclipse-temurin:21-jre AS http
WORKDIR /app
COPY --from=build /src/server-http/target/quarkus-app /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
```

For HTTP, run with:

```bash
docker run --rm -p 8080:8080 \
  -e QDRANT_URL=http://host.docker.internal:6333 \
  -e EMBED_BASE_URL=http://host.docker.internal:8081/v1 \
  -e EMBED_MODEL=bge-large-en-v1.5 \
  pdf-rag-ingest-http
```

### docker-compose for the full local stack

```yaml
services:
  qdrant:
    image: qdrant/qdrant:latest
    ports: ["6333:6333"]
    volumes: ["qdrant_data:/qdrant/storage"]

  llama-server:
    image: ghcr.io/ggml-org/llama.cpp:server
    command: -m /models/bge-large-en-v1.5-f16.gguf --embeddings --host 0.0.0.0 --port 8081
    volumes: ["./models:/models"]
    ports: ["8081:8081"]
    # For GPU acceleration, switch to :server-cuda and uncomment:
    # deploy:
    #   resources:
    #     reservations:
    #       devices:
    #         - capabilities: [gpu]

  ingest:
    image: pdf-rag-ingest-http:latest
    depends_on: [qdrant, llama-server]
    environment:
      INGEST_BACKEND: qdrant
      QDRANT_URL: http://qdrant:6333
      EMBED_BASE_URL: http://llama-server:8081/v1
      EMBED_MODEL: bge-large-en-v1.5
    ports: ["8080:8080"]

volumes:
  qdrant_data:
```

### systemd unit for the HTTP transport

```ini
# /etc/systemd/system/pdf-rag-ingest.service
[Unit]
Description=pdf-rag-ingest MCP server (HTTP)
After=network-online.target
Wants=network-online.target

[Service]
Environment=INGEST_BACKEND=qdrant
Environment=QDRANT_URL=http://127.0.0.1:6333
Environment=EMBED_BASE_URL=http://127.0.0.1:8081/v1
Environment=EMBED_MODEL=bge-large-en-v1.5
Environment=PORT=8080
Environment=MCP_CORS_ORIGINS=http://localhost:3000
ExecStart=/usr/lib/jvm/temurin-21/bin/java -jar /opt/pdf-rag-ingest/quarkus-app/quarkus-run.jar
Restart=on-failure
User=pdfrag
Group=pdfrag

[Install]
WantedBy=multi-user.target
```

## Health & observability

- **Liveness/readiness**: not currently exposed. The HTTP transport returns 200
  on its MCP endpoint once Quarkus is up; treat that as readiness.
- **Logs**: stdio routes everything to stderr; HTTP logs to stdout. Set
  `QUARKUS_LOG_LEVEL=DEBUG` for verbose troubleshooting.
- **Wire-level tracing**: no built-in HTTP logging interceptor. If you need to
  see exact bytes going to Qdrant or the embeddings endpoint, run with `-Djdk.httpclient.HttpClient.log=all`
  on the `java` command line, or proxy through `socat`/`mitmproxy`.

## Upgrading

- **Quarkus**: change `quarkus.platform.version` in the parent `pom.xml`.
- **Qdrant**: REST shapes are stable on the 1.x line. The fields we read are
  generic enough that minor-version upgrades shouldn't break.
- **Apache Tika**: bumped via `<tika.version>` in the parent `pom.xml`. Tika 3.x
  is the current line; 2.x → 3.x is a breaking move
  (e.g. `Metadata.RESOURCE_NAME_KEY` was relocated to `TikaCoreProperties`).
- **Embedding endpoint**: OpenAI compatibility is the contract. llama.cpp,
  vLLM, and vanilla OpenAI all keep the embeddings response shape stable
  across versions; if you ever see "returned N vectors for M inputs" after an
  upgrade, something has gone wrong.
- **Open WebUI**: most upgrades are transparent. The pieces most likely to drift
  are the response shapes captured in
  `core/src/main/java/org/example/backend/openwebui/dto/`.

## Failure modes seen in the wild

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `tools/list` returns `[]` | Jandex index missing or `quarkus.index-dependency.core.*` missing. | Confirm both; rebuild with `mvn package`. |
| `POST /api/v1/files/` or `POST /v1/embeddings` → 400 `Invalid HTTP request received` | New `HttpClient` somewhere isn't pinned to HTTP/1.1. | Add `.version(HttpClient.Version.HTTP_1_1)` to the builder. |
| Qdrant `ensureCollection` throws `dim=X but embeddings produced Y` | Switched `EMBED_MODEL` to one with a different vector size. | Delete the old collection (or ingest into a new `kb_name`). |
| Embedding response: "returned N vectors for M inputs" | The endpoint is rate-limiting or has different batch limits than `EMBED_BATCH_SIZE`. | Lower `EMBED_BATCH_SIZE`. |
| Qdrant 401 / 403 on every call | `QDRANT_API_KEY` empty against a Qdrant Cloud cluster. | Set the env var; we send it as the `api-key` header (Qdrant's convention). |
| `POST /knowledge/{id}/file/add` → 400 `content provided is empty` | Open WebUI polling logic removed/shortened. | Restore `waitUntilProcessed`; ensure it sees `completed` before attach. |
| stdio client connects but tool calls hang | Something is writing to stdout from `core` — log, `println`, or banner. | Audit recent changes; the stdio `application.properties` must keep `quarkus.banner.enabled=false` and `quarkus.log.console.stderr=true`. |
