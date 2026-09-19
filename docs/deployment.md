# Deployment

> **Note:** This doc covers the pre-fusion deployment story (Qdrant + llama-server
> + Java MCP server). The fusion design adds a Python ColPali sidecar as a fourth
> service. For a quick visual-enabled stack, the repo root has a
> `docker-compose.yml` that brings up Qdrant + llama-server + colpali-server +
> pdf-rag-http in one go (`docker compose up -d`). Full architecture is in
> [architecture.md](architecture.md); sidecar specifics are in
> [components/colpali-sidecar.md](components/colpali-sidecar.md). This doc itself
> still needs a refresh pass to integrate the visual side into every section.
> Two overlays layer on the base file: `docker-compose.gpu.yml` (CUDA
> sidecar, llama-server on the GPU; auto-loaded through the committed
> `docker-compose.override.yml` symlink) and `docker-compose.pool.yml` (nginx
> balancer in front of several sidecars). See
> [Compose overlays: GPU and sidecar pool](#compose-overlays-gpu-and-sidecar-pool).
>
> **For the fastest path, use the [`scripts/`](../scripts/README.md) wrappers**
> (`scripts/bootstrap.sh` then `scripts/up.sh [--gpu]`) — they handle `.env`, the
> embedding-model download, the GPU overlay, and health checks. Current
> project state lives in [status.md](status.md).

How to build, configure, and run pdf-rag-ingest with the Qdrant backend (the
default) and optionally the Open WebUI backend. There are two runnable artifacts
(one per transport); pick whichever your MCP client supports — they expose the
same tools and read the same env vars.

## Prerequisites

| What | Why | Note |
|------|-----|------|
| JDK 21+ | Compiles to Java 21 bytecode. | Newer JDKs work; we target `--release 21`. |
| Maven 3.9.x | Quarkus 3.33 wants ≥ 3.9.6. | The repo uses `mvnvm` (auto-pins 3.9.9). Any installed `mvn` ≥ 3.9.6 works too. |
| **Qdrant** (Qdrant backend) | Vector store. | Local Docker is fine; **pin to `qdrant/qdrant:v1.13.x`** for the multivector + multistage query API we use. Both ports are needed: REST 6333 for everything, gRPC 6334 for the `<kb>_pages` upserts (`ingest.qdrant.upsert-transport=grpc`, the default; a gRPC failure logs a warning and falls back to REST for that batch). |
| **Embeddings endpoint** (Qdrant backend) | OpenAI-compatible `/v1/embeddings`. | llama.cpp's `llama-server` (started with `--embeddings`) is the project default. vLLM / OpenAI / Together / LM Studio also work over the same OpenAI shape. |
| **ColPali sidecar** (Qdrant backend, optional but default-on) | Visual-side embeddings. | Python service in `sidecar/`. CPU image with ColSmolVLM or GPU image with ColQwen2. See `sidecar/README.md` and `components/colpali-sidecar.md`. |
| **Open WebUI** (legacy backend, optional) | If you still want the Open WebUI target. | 0.9.x; URL in `OPEN_WEBUI_BASE_URL`. |

### Local infrastructure for the Qdrant backend

The cheapest setup that exercises the full default pipeline:

```bash
# Qdrant (6333 REST, 6334 gRPC for the page multivector upserts)
docker run -p 6333:6333 -p 6334:6334 -v qdrant-data:/qdrant/storage qdrant/qdrant:v1.13.4

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

If you have a GPU, add `--gpus all` and use the `:server-cuda` image. Anything
that speaks OpenAI-compatible `/v1/embeddings` works — vLLM
(`vllm/vllm-openai:latest --task embedding`),
[LM Studio's server mode](https://lmstudio.ai), or vanilla OpenAI — point
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
| `SWAGGER_UI_ALWAYS_INCLUDE` | no | `true` | **Build-time.** Serve Swagger UI (`/q/swagger-ui`) on the packaged app, not just dev mode. The `/q/openapi` schema is served regardless. Set `false` at build to keep the UI dev-only. |

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

Run in a terminal, it sits waiting for input — that's correct. See
[mcp-integration.md](mcp-integration.md) for client wiring.

### Streamable HTTP transport — long-lived service at `:PORT/mcp`

```bash
export QDRANT_URL=http://localhost:6333
export EMBED_BASE_URL=http://localhost:8081/v1
export EMBED_MODEL=bge-large-en-v1.5
java -jar server-http/target/quarkus-app/quarkus-run.jar
# now listens on 0.0.0.0:8080, MCP endpoint at http://localhost:8080/mcp
```

Alongside `/mcp`, the HTTP transport serves a plain **REST surface** for
bulk/operational use — `POST /ingest/directory`, `GET /ingest/status/{jobId}`,
`DELETE /ingest/document` (see [components/directory-ingest.md](components/directory-ingest.md))
— and its **OpenAPI docs**, all on the same port:

- Swagger UI: `http://localhost:8080/q/swagger-ui`
- OpenAPI schema: `http://localhost:8080/q/openapi` (append `?format=json` for JSON)

Swagger UI is enabled on the built server via `SWAGGER_UI_ALWAYS_INCLUDE=true`
(build-time). Because `MCP_CORS_ORIGINS` defaults to `*`, an always-on UI is
reachable by anything that can reach the port — restrict at the network layer, or
rebuild with the flag `false` to keep the UI dev-only (the raw `/q/openapi` schema
stays available either way).

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

### Compose overlays: GPU and sidecar pool

The checked-in stack is three files, layered in this order:

| File | Adds |
|------|------|
| `docker-compose.yml` | The base stack: qdrant, llama-server (CPU image), colpali-server (CPU build), pdf-rag-http. |
| `docker-compose.gpu.yml` | CUDA sidecar build + NVIDIA reservation; `PYTORCH_CUDA_ALLOC_CONF=expandable_segments:True` on the sidecar so its allocator hands VRAM back; llama-server on `ghcr.io/ggml-org/llama.cpp:server-cuda` with `--n-gpu-layers ${LLAMA_GPU_LAYERS:-99}` (the full `command` is repeated because compose replaces a scalar); `INGEST_QUEUE_WORKERS` 2, `COLPALI_BATCH_SIZE` 16, `INGEST_QDRANT_MULTIVECTOR_UPSERT_BATCH` 4 as defaults for pdf-rag-http. Auto-loaded by plain `docker compose` through the committed `docker-compose.override.yml` symlink. |
| `docker-compose.pool.yml` | `colpali-lb`: `nginx:1.27-alpine`, least-connections balancer on port 8090 in front of the local sidecar and remote replicas; pdf-rag-http gets `COLPALI_SIDECAR_URL=http://colpali-lb:8090` and depends on `colpali-lb` being healthy. |

The pool is not auto-loaded. Bring it up by naming all three files:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml -f docker-compose.pool.yml up -d
```

**Always pass `--no-deps` when you recreate one service during a run.**
The sidecar's `/embed_pages` blocks its event loop for the whole batch, so
its Docker health probe can report `unhealthy` while it is busy; compose
then refuses to start dependents and leaves `pdf-rag-http` stopped (seen
2026-09-17, 5 min outage). The base file already sets the probe `timeout`
to 40 s and `retries` to 5, which covers a batch, but do not depend on it:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml -f docker-compose.pool.yml \
  up -d --no-deps --force-recreate colpali-server      # or llama-server, pdf-rag-http, colpali-lb
```

**Balancer config.** Upstreams live in `deploy/colpali-lb.conf`. The
`./deploy` directory (not the file) is bind-mounted at `/etc/nginx/deploy`
and nginx is started with `-c /etc/nginx/deploy/colpali-lb.conf`: a
single-file bind mount pins the inode, and `git pull` writes a new file, so
a reload would keep the old config. After editing or pulling the conf:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml -f docker-compose.pool.yml \
  exec colpali-lb nginx -s reload
```

Do not reload casually during a run: old nginx workers close idle
keep-alive connections the JDK client still holds. The client retries an
embed POST once on I/O error, then requeues the job as transient, so the
cost is re-work rather than lost jobs. The conf has no `max_conns` on the
embed pool (nginx OSS answers 502 "no live upstreams" instead of queueing;
pdf-rag-http treats 502/503/504 as transient, but requests queue better at
the sidecars), `keepalive_timeout 3600s`, and a `/healthz` that reports
ready when every replica is mid-batch.

**Remote replicas.** `deploy/bigdumb-sidecar-compose.yml` is the layout on
a second GPU host (two cards, four replicas per card, ports 8100-8103 and
8110-8113, compose profile `pool`). Replicas run the same image and model
cache with the same `COLPALI_*` env as the local sidecar, so vectors match;
add or remove `server` lines in `deploy/colpali-lb.conf` to size the pool.
Measured rates, the cross-GPU-generation parity caveat, and the incident
list are in [plans/sidecar-pool-v1.md](plans/sidecar-pool-v1.md); the
byte-level data path is in
[components/visual-dataflow.md](components/visual-dataflow.md).

### JVM heap for pdf-rag-http

`docker-compose.yml` passes `JAVA_TOOL_OPTIONS: ${PDF_RAG_JAVA_TOOL_OPTIONS:-}`
into `pdf-rag-http`. Empty means the JDK ergonomic default (25% of host
RAM). Each queue worker holds a batch response, its decoded arrays, and a
document's accumulated page vectors, so size the heap for the worker count,
e.g. `PDF_RAG_JAVA_TOOL_OPTIONS=-Xmx64g` in `.env` (documented in
`.env.example`). The same variable
carries system properties for keys with no env alias, e.g.
`-Dingest.qdrant.upsert-transport=rest`.

### The `/documents` upload store mount (write-side exposure)

The checked-in `docker-compose.yml` mounts a third big-storage location beside
`QDRANT_DATA_DIR` and `PAGE_IMAGES_DIR`: `${INGEST_DOCUMENTS_DIR:-documents}`
at `/documents`, **read-write** — the durable document store behind
`POST /ingest/upload` (see
[components/upload-ingest.md](components/upload-ingest.md)). Unlike `/docs`
and `/host` (both read-only), this is the corpus of record: uploaded files
stay until an operator deletes them, queued visual jobs re-read them, and
`POST /ingest/directory` over `/documents/<kb>` re-indexes them.

On a ZFS host, give it its own dataset with a quota so a fill cannot starve
the Qdrant storage on the same pool:

```bash
zfs create -o recordsize=1M -o compression=lz4 -o quota=200G tank/documents
# .env: INGEST_DOCUMENTS_DIR=/tank/documents
```

`recordsize=1M` suits whole-file PDF reads; `lz4` is close to free. The quota
is not a suggestion: the REST surface has **no auth** and CORS defaults to
`*`, and this endpoint is the first that *writes* caller-controlled bytes to
permanent server storage — anyone who reaches `:8080` can consume pool
capacity. Path confinement, per-request caps, and the free-space reserve are
v1 mitigations; the real mitigation is network isolation. The container runs
as root, so stored files are `root:root` on the tank (set `user:` on the
service to change that). `ingest.upload.require_mount=true` (default) makes
the server refuse uploads when `/documents` is not actually a mount — a
missing bind would otherwise silently store the corpus inside the container.

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

### Bulk directory loads

For a corpus of thousands of PDFs use `scripts/bulk_ingest_runner.py`
rather than one `POST /ingest/directory` over the whole tree: the text side
runs synchronously inside the call and a big call is cut by the 30-minute
HTTP idle timeout while the server keeps working. The runner posts small
batches (default 40 files, 3 in flight), each staged as a uniquely named
directory of symlinks under a path the container sees as `/host/...`, and
decides what is still missing from Qdrant and the jobs API, so it can be
killed and restarted freely. `--help` lists the options; the module
docstring has the R530 invocation.

Sizing that held on the R530 (40 cores, one A4500, eight remote sidecar
replicas), 2026-09-18:

| Setting | Value | Why |
|---|---|---|
| runner `--batch` / `--concurrent` | 40 / 3 | 3 calls x 4 files per call = 12 text threads; each call ends in 1-3 min |
| `LLAMA_PARALLEL` | 8 (GPU override default) | 12 text threads on 4 slots queued on the embedder: 2.8 files/min; 8 slots: ~31 files/min |
| `INGEST_QUEUE_WORKERS` | 20 | keeps ~2 embed requests in flight per sidecar replica |
| `PDF_RAG_JAVA_TOOL_OPTIONS` | `-Xmx48g` | ~1-2 GB per worker on long documents |
| `INGEST_MAX_FILE_BYTES` | 419430400 | scanned reports reach 372 MB; the 100 MB default rejected 37 |
| `INGEST_EMBED_REQUEST_TIMEOUT_SECONDS` | 600 | 12 text threads on 8 slots: a batch at the tail of a long document waits past 120 s |
| `COMPOSE_FILE` in `.env` | `docker-compose.yml:docker-compose.gpu.yml:docker-compose.pool.yml` | a recreate without the pool file silently points the service at the single local sidecar |
| `indexing_threshold` on `<kb>_pages` | raised for the load, restored after | see the throughput plan |

Long scanned documents still overflow a 48 GB heap when twenty of them
are in flight at once (each worker holds a document's vectors until the
last batch). Such a job now ends as `failed` with "Java heap space
exhausted"; re-run those files with `INGEST_QUEUE_WORKERS` at 4-6 (a
recreate is safe while the queue is idle). The structural fix is step 3
of [plans/ingest-planner-v1.md](plans/ingest-planner-v1.md).

After a load, audit before declaring it complete: for the KB, list
failed jobs whose file has no later completed job, then count points by
`filename` in `<kb>` and `<kb>_pages` (`POST .../points/count` with
`exact: true`). A file with chunks and zero pages had its visual job fail
for good; re-submit it through `/ingest/directory` (replace semantics
rewrite both sides). The runner keys "done" on page points for this
reason; `--text-only` keeps the chunk test for KBs without a visual index.

Two things the runner protects against, learned the hard way: a staging
directory reused across runs re-ingests its old contents (replace
semantics, then vacuum churn in Qdrant), so batch directories are never
reused; and pausing the runner must be done by pid (`kill -STOP`), because
`pkill -f` on a pattern that appears in your own shell's command line stops
the shell too.

Chunks longer than the text embedder's 512-token cap make the embedder
reject the 64-chunk batch, and the client then sends those chunks one
request each (the `Embedding batch of N rejected as too large; isolating
per input` warning). It is correct but slow; keep `INGEST_CHUNK_SIZE_CHARS`
small enough that OCR-heavy text stays under the cap.

## Health & observability

- **Liveness/readiness**: not currently exposed as a dedicated health endpoint.
  The HTTP transport returns 200 on its MCP endpoint once Quarkus is up, and
  `GET /q/openapi` → 200 is another cheap "HTTP/REST layer is up" probe; treat
  either as readiness.
- **Sidecar readiness**: `GET /healthz` on the sidecar returns
  `{status, ready}`; the compose healthcheck exits 0 only on `ready:true`
  (timeout 40 s, 5 retries, `start_period` 15 m for the first model
  download). Through the balancer, `/healthz` proxies to any replica with a
  1 s timeout and answers `{"ready":true,"note":"pool busy; ..."}` when none
  answers in time. `GET /info` lists `encodings`.
- **Balancer access log**: `deploy/colpali-lb.conf` logs one line per
  embed request to stdout (nginx status, upstream status, timings, replica);
  probes are silent. `docker compose ... logs colpali-lb` matches a job
  failure on the Java side to what nginx saw.
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
  `core/src/main/java/org/hayden/backend/openwebui/dto/`.

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
| Log: `gRPC upsert into '<kb>_pages' failed (...); falling back to REST for this batch` on every batch | Port 6334 not reachable from pdf-rag-http, or `ingest.qdrant.grpc-host` points at the wrong host. | Expose 6334 on Qdrant; set `-Dingest.qdrant.grpc-host=<host>` via `PDF_RAG_JAVA_TOOL_OPTIONS`, or `-Dingest.qdrant.upsert-transport=rest` to stop the attempts. Jobs still succeed over REST, slower. |
| pdf-rag-http refuses to start; SmallRye Config reports a property as missing | A `@ConfigProperty` with an empty `defaultValue` (SmallRye treats it as no value). Crash-looped the R530 for 9 min on 2026-09-18. | Give the key a sentinel default in code (`auto` for `ingest.qdrant.grpc-host`) or pass the value through `PDF_RAG_JAVA_TOOL_OPTIONS`. Tag the running image before a risky deploy so a revert can land. |
| Visual jobs requeue in a loop with `ColPali sidecar ... returned HTTP 502` | Balancer has no live upstream: every replica down, or mid-batch with `max_conns` set. | Check replicas; keep `max_conns` off the embed pool. 502/503/504 are transient (requeue + backoff), not job failures. |
| `IOException: Java heap space` on embed reads | Too many queue workers for the JVM heap. | Set `PDF_RAG_JAVA_TOOL_OPTIONS=-Xmx<n>g`, or lower `INGEST_QUEUE_WORKERS`. `ingest.colpali.wire-encoding=f32b64` (default) already cuts the response to a fraction of the JSON size. |
| Scanned PDF ingest fails with `PDFBox extracted no text` | Text-only ingest (`enable_visual_index=false`) or a non-PDF with no text. | With a visual index requested the ingest proceeds with 0 chunks; enable the visual index for scanned corpora. |
| Stack restart left `pdf-rag-http` stopped | A busy sidecar failed its health probe; compose refused to start dependents. | Recreate with `--no-deps`; the base file's 40 s probe timeout covers a batch. |
