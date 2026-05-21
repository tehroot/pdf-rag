# Integrating pdf-rag-ingest with a local LLM (MCP)

> **Note:** This doc predates the fusion design. The tool surface now includes
> a fifth tool (`inspect_page`) and new args on `ingest_document`
> (`enable_visual_index`) and `search_documents` (`retrieval_mode`,
> `fusion_strategy`). See [components/mcp-tools.md](components/mcp-tools.md)
> for the current tool surface, and [architecture.md](architecture.md) for the
> backend architecture. The wiring examples below for Claude Desktop / Cline /
> mcp-cli still work; you'll just want to add `COLPALI_SIDECAR_URL` to the env
> if running the visual side.

This server exposes its tools over the Model Context Protocol. To use it with a
local LLM stack you need:

1. The Qdrant backend's infrastructure running locally: a Qdrant instance and
   an OpenAI-compatible embeddings endpoint (llama.cpp's llama-server by default).
   For visual indexing (default-on), also run the ColPali sidecar (see
   `components/colpali-sidecar.md`). See [deployment.md](deployment.md) for the
   Docker recipes and the root `docker-compose.yml` for the whole stack.
2. The right transport jar built — `server-stdio/target/quarkus-app/quarkus-run.jar`
   for stdio hosts, `server-http/target/quarkus-app/quarkus-run.jar` for HTTP hosts.
3. The MCP host configured with the right env vars (`QDRANT_URL`,
   `EMBED_BASE_URL`, `EMBED_MODEL`, `COLPALI_SIDECAR_URL`,
   `INGEST_DEFAULT_VISUAL_INDEX`, etc.) and command/URL.

The two transports are functionally identical — same five tools, same backends,
same env. Pick the one your host supports:

| Transport | When to pick it |
|-----------|-----------------|
| **stdio** (`server-stdio`) | The MCP host launches the server as a child process. Default for Claude Desktop, Cline, Continue, `mcp-cli`, and most "desktop" MCP hosts. |
| **Streamable HTTP** (`server-http`) | The MCP host connects to an already-running URL. Default for Open WebUI's native MCP client, browser-based agents, multi-user setups, and anything cross-machine. |

If both are available, prefer **stdio** for single-user dev (no port to manage)
and **HTTP** when the server runs on a different host than the LLM, or when
multiple MCP clients share one server.

---

## Stdio integrations

Every stdio host follows the same pattern: it spawns `java -jar quarkus-run.jar`
as a child, hands it a pipe pair, and frames JSON-RPC over stdin/stdout.
Environment variables must be set in the host's config (the host won't read
your shell `~/.zshrc`).

### Claude Desktop / Claude Code

Add an entry to `mcpServers` in `~/Library/Application Support/Claude/claude_desktop_config.json`
(macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "rag-ingest": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/pdf-rag-ingest/server-stdio/target/quarkus-app/quarkus-run.jar"
      ],
      "env": {
        "INGEST_BACKEND": "qdrant",
        "QDRANT_URL": "http://localhost:6333",
        "EMBED_BASE_URL": "http://localhost:8081/v1",
        "EMBED_MODEL": "bge-large-en-v1.5"
      }
    }
  }
}
```

Restart Claude Desktop. The four tools (`ingest_document`, `search_documents`,
`list_knowledge_bases`, `get_file_status`) appear under the server's tool menu.

If you also want the Open WebUI backend reachable from the same server, add:

```json
"OPEN_WEBUI_BASE_URL": "http://localhost:3000",
"OPEN_WEBUI_API_KEY": "ow-..."
```

Then the agent can pass `backend="openwebui"` to any tool that takes it.

For Claude Code (CLI), the same shape goes into `~/.claude/mcp_servers.json`
(or whichever path your version reads).

### Cline / Continue / Roo / Cursor — VS Code-based agents

These read `mcp_servers.json` (or an analogous file) inside the extension's
settings directory. The shape is identical to Claude Desktop:

```json
{
  "mcpServers": {
    "rag-ingest": {
      "command": "java",
      "args": ["-jar", "/abs/path/server-stdio/target/quarkus-app/quarkus-run.jar"],
      "env": {
        "QDRANT_URL": "http://localhost:6333",
        "EMBED_BASE_URL": "http://localhost:8081/v1",
        "EMBED_MODEL": "bge-large-en-v1.5"
      }
    }
  }
}
```

For Cline specifically: VS Code → Cline panel → ⚙ → "MCP Servers" → paste this entry.

### `mcp-cli` / scripted hosts

```bash
mcp-cli connect --transport stdio \
  --command java \
  --arg -jar --arg /abs/path/server-stdio/target/quarkus-app/quarkus-run.jar \
  --env QDRANT_URL=http://localhost:6333 \
  --env EMBED_BASE_URL=http://localhost:8081/v1 \
  --env EMBED_MODEL=bge-large-en-v1.5
```

The exact flag spelling varies between MCP CLIs; the shape ("command + args + env")
is universal across stdio MCP hosts.

### Common stdio gotchas

- **Absolute paths only** in `command`/`args`. MCP hosts have no meaningful CWD,
  and `~` is not expanded.
- **Logs go to stderr** by design (so stdout stays clean for JSON-RPC). If
  you're troubleshooting, look at the host's "MCP server log" panel — Claude
  Desktop logs to `~/Library/Logs/Claude/mcp-server-rag-ingest.log`.
- **No shell expansion.** `"$HOME"` won't be substituted in the `command`
  array. Use literal paths.
- **Env is per-server.** Setting `QDRANT_URL` in your shell won't leak into a
  Claude Desktop-spawned child — it must be in the JSON.

---

## Streamable HTTP integrations

The HTTP transport listens on `http://<host>:<PORT>/mcp` and speaks Streamable
HTTP (SSE + POST). Start it first, then point clients at the URL.

### Run it

```bash
export QDRANT_URL=http://localhost:6333
export EMBED_BASE_URL=http://localhost:8081/v1
export EMBED_MODEL=bge-large-en-v1.5
export PORT=8080                                          # optional
export MCP_CORS_ORIGINS=http://localhost:3000             # tighten from * if reachable from browsers
java -jar server-http/target/quarkus-app/quarkus-run.jar
# → http://localhost:8080/mcp
```

### Open WebUI as the MCP client

Open WebUI 0.5+ ships with built-in MCP support. Pointing it at this server
gives Open WebUI's own chat models access to `search_documents` (Qdrant-backed
RAG) and `ingest_document` (write to that same Qdrant collection or, with
`backend="openwebui"`, into Open WebUI's own KB):

1. Admin Panel → Settings → Tools / MCP Servers.
2. Add a server with transport **Streamable HTTP** and URL
   `http://localhost:8080/mcp`.
3. Enable the server; the four tools appear in the chat tool picker.

Recursion warning: if Open WebUI is using *this* server's `search_documents`
for its RAG and we're also using `backend="openwebui"` to write to Open WebUI's
own KB, you have two parallel knowledge bases. That's usually a bug, not a
feature — pick one storage backend per logical corpus.

### Browser-based MCP hosts (LibreChat, AnythingLLM, etc.)

Most provide an "Add MCP Server" form with fields for URL and transport. Use:

- **Transport:** Streamable HTTP (some UIs label it "HTTP" or "SSE")
- **URL:** `http://<host>:<port>/mcp`
- **Headers:** none required (pdf-rag-ingest does not enforce MCP-side auth;
  the Qdrant + embeddings auth is server-side env, invisible to the MCP client).

### Securing the HTTP transport

By default the HTTP transport has **no MCP-client authentication** — any caller
that can reach `:8080/mcp` can invoke `ingest_document`. Acceptable when bound
to `127.0.0.1`; not acceptable on a public interface. Options:

- Bind to localhost only: `QUARKUS_HTTP_HOST=127.0.0.1`.
- Front it with a reverse proxy (nginx, Caddy, Traefik) that enforces TLS +
  basic auth or a static bearer token; have the MCP client send the matching
  header.
- Tighten `MCP_CORS_ORIGINS` to the exact origins that need it.

---

## Choosing a backend at call time

`ingest_document`, `search_documents`, and `list_knowledge_bases` all take an
optional `backend` argument. Behavior:

| `backend` arg | Effect |
|--------------|--------|
| omitted / empty | Use `INGEST_BACKEND` env (default `qdrant`). |
| `"qdrant"` | Qdrant pipeline (Tika → chunk → llama-server → Qdrant). |
| `"openwebui"` | Open WebUI pipeline. `search_documents` is unsupported. |
| `"all"` (only meaningful on `list_knowledge_bases`) | Merge collections + KBs across both. |

A typical agent prompt looks like:

> "Ingest https://example.com/specs.pdf into the `engineering-docs` knowledge
> base, then search it for 'rate limiting strategy'."

The agent issues:

```json
{"name":"ingest_document","arguments":{
   "source_type":"url",
   "source_value":"https://example.com/specs.pdf",
   "kb_name":"engineering-docs"}}
{"name":"search_documents","arguments":{
   "kb_name":"engineering-docs",
   "query":"rate limiting strategy",
   "top_k":5}}
```

Both default to Qdrant; `search_documents` returns ranked chunks with text and
metadata for the model to reason over.

## Naming knowledge bases

- For a recurring corpus: hardcode a name (e.g. `"engineering-docs"`). The
  first call creates the collection; subsequent calls reuse it. **The vector
  dim is fixed at collection creation** — if you switch `EMBED_MODEL` later,
  the next ingest into the same `kb_name` will fail loudly. Use a new name.
- For multi-tenant setups: prefix the name (e.g. `"user-42_uploads"`). Qdrant
  has no naming structure, so the convention is yours.
- For experimentation: pass a one-off name; the agent can later list it via
  `list_knowledge_bases`. Delete it through Qdrant's dashboard or REST API
  (there's no `delete_kb` tool yet).

## Metadata for filtering

The `metadata` arg on `ingest_document` (Qdrant backend only) is a key/value
map stored alongside each chunk. At search time, the `filter` arg performs an
exact-match match on those keys. Useful patterns:

```json
// Ingest
"metadata": {"project": "alpha", "doc_type": "spec", "version": "v3"}

// Search only chunks tagged with that project + version
"filter":   {"project": "alpha", "version": "v3"}
```

Several keys are filled in automatically by the backend and are also filterable:
`filename`, `source`, `source_type` (url/path/inline), `content_type`, `doc_id`,
`embed_model`.

## End-to-end test against a real stack

```bash
docker compose up -d qdrant llama-server   # see deployment.md for the compose snippet
export QDRANT_URL=http://localhost:6333
export EMBED_BASE_URL=http://localhost:8081/v1
export EMBED_MODEL=bge-large-en-v1.5
java -jar server-http/target/quarkus-app/quarkus-run.jar &
SERVER_PID=$!

# From any MCP host pointed at http://localhost:8080/mcp, prompt:
#    "Ingest https://www.africau.edu/images/default/sample.pdf into KB 'smoke',
#     then search it for 'dummy'."
#
# Expected: ingest_document returns added_to_kb=true with chunk_count>0;
# search_documents returns 1-3 hits whose text mentions "Dummy PDF file".

curl -s http://localhost:6333/collections/smoke   # verify the collection exists

kill $SERVER_PID
```

Troubleshooting paths:

- Ingest returns `added_to_kb=true, chunk_count=0` → Tika extracted no text.
  Check `content_type` and the source file itself.
- Search returns 0 hits but ingest reported chunks → mismatch in `kb_name` or
  the collection actually has 0 points (`GET /collections/<name>` will tell you).
- Embedding endpoint timing out → bump `ingest.embed.request-timeout-seconds`
  or lower `EMBED_BATCH_SIZE`.

## What this server explicitly does **not** do

- No deletion or re-ingest dedupe. Calling `ingest_document` twice with the
  same URL but different chunking settings produces a duplicate copy in the
  collection. Within identical chunking settings, point IDs are deterministic
  (UUID v5 of `doc_id+chunk_index`), so a re-ingest with the *same `doc_id`*
  is idempotent — but each ingest gets a fresh `doc_id`, so in practice you
  get duplicates. Cleanup happens through Qdrant's API.
- No multi-file or directory ingest in a single call. Loop and call once per
  file from the agent.
- No reranker. Search returns Qdrant's raw cosine-similarity ranking; if you
  need a reranker, wire it on the agent side or as a separate MCP server.
- No source types beyond `url` / `path` / `inline`. S3, GCS, etc. would be
  additional `SourceType` variants — they are not implemented.
- No authentication on the MCP transport itself. The Qdrant + embeddings
  credentials are env-side and invisible to MCP callers; if you need to gate
  who can ingest, put a reverse proxy in front of the HTTP transport.
