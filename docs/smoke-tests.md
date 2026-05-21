# End-to-end smoke tests

Run this against a live stack to verify everything is wired correctly. Each
test is a copy-pasteable shell command sequence with expected output;
deviation from the expected output is the failure mode to investigate.

The runbook assumes the **R530-style CPU-only deployment**: Qdrant +
llama-server (bge-small) + ColPali sidecar (ColSmolVLM-500M, CPU) +
Quarkus MCP HTTP server, all in containers on a single host. Adjust env
overrides for other topologies.

## Prerequisites

Before running:

1. `docker compose` is installed and running.
2. `./models/bge-small-en-v1.5-f16.gguf` exists on disk:
   ```bash
   mkdir -p models
   # If you have huggingface-cli installed:
   huggingface-cli download CompendiumLabs/bge-small-en-v1.5-gguf \
     bge-small-en-v1.5-f16.gguf --local-dir ./models

   # Or download manually from huggingface.co and place the file at:
   #   ./models/bge-small-en-v1.5-f16.gguf
   ```
3. `./.env` exists (copy from `.env.example` and edit):
   ```bash
   cp .env.example .env
   # Edit if needed; defaults match the R530 deployment.
   ```
4. Tools installed locally: `curl`, `jq`, optionally `qpdf` or `pdfinfo` for
   building test PDFs.

## Phase 0 — Bring the stack up

```bash
docker compose build                          # first time only
docker compose up -d

# Wait for all services to be healthy.
docker compose ps
```

**Expected**: four containers (`pdf-rag-qdrant`, `pdf-rag-llama`,
`pdf-rag-colpali`, `pdf-rag-http`), all `running`. The colpali-server takes
the longest to become ready (loading ColSmolVLM into RAM); allow ~30-90s
on a cold cache.

Watch logs while waiting:

```bash
docker compose logs -f colpali-server | grep -i "ready\|model"
```

When ready, expect a line like:
```
INFO:colpali_server:model loaded; ready
```

## Phase 1 — Per-service smoke

### 1a. Qdrant

```bash
curl -s http://localhost:6333/readyz
# Expected: "all shards are ready"

curl -s http://localhost:6333/collections | jq
# Expected:
# {
#   "result": { "collections": [] },
#   "status": "ok",
#   ...
# }
```

### 1b. llama-server (text embeddings)

```bash
curl -s http://localhost:8081/health | jq
# Expected: {"status":"ok"}

curl -s http://localhost:8081/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model":"bge-small-en-v1.5","input":["hello world"]}' | jq '.data[0].embedding | length'
# Expected: 384  (bge-small-en-v1.5 dim; 768 if you swapped to bge-base, 1024 for bge-large)
```

### 1c. ColPali sidecar

```bash
curl -s http://localhost:8090/healthz | jq
# Expected: {"status":"ok","ready":true}

curl -s http://localhost:8090/info | jq
# Expected:
# {
#   "model_name": "vidore/colsmolvlm-v0.1",
#   "vector_dim": 128,
#   "supports_pooled": true,
#   "pooled_methods": ["rows", "cols"],
#   "max_batch_size": 4,
#   "device": "cpu"
# }
```

If `ready` is false, the model is still loading. Wait and retry.

If you see `vector_dim` other than what you expect (e.g., 768 for some
SmolVLM variants), the sidecar is fine but the dim flowed through to Qdrant
on first ingest. As long as it's consistent, retrieval works.

### 1d. Quarkus MCP HTTP

The MCP transport speaks Streamable HTTP (SSE + POST). The simplest smoke is
a JSON-RPC frame against the `/mcp` endpoint:

```bash
# tools/list — should return the 7 @Tool methods we expose.
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[].name'
# Expected (any order):
# "ingest_document"
# "search_documents"
# "list_knowledge_bases"
# "get_file_status"
# "inspect_page"
# "get_ingest_status"
# "drop_visual_index"
```

If `tools/list` returns `[]`, the Jandex index didn't pick up the `core` JAR
— check the transport module's `application.properties` for the
`quarkus.index-dependency.core.*` lines.

## Phase 2 — Tool-level smoke

For the rest of this runbook, the `mcp_call` helper makes JSON-RPC tool calls
ergonomic. Drop this into your shell:

```bash
mcp_call() {
  local tool="$1"
  local args="$2"
  curl -s -X POST http://localhost:8080/mcp \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d "$(jq -nc --arg t "$tool" --argjson a "$args" \
      '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:$t,arguments:$a}}')" \
    | jq '.result.content[0].text | fromjson'
}
```

(Quarkus MCP returns tool results inside `result.content[0].text` as a
JSON-encoded string; `fromjson` peels that.)

### 2a. List empty knowledge bases

```bash
mcp_call list_knowledge_bases '{"backend":"all"}'
# Expected: []
```

### 2b. Ingest a small text file (sync path, text-only — fast)

```bash
# A short inline payload triggers the sync path regardless of visual settings,
# since inline files aren't PDFs.
INLINE_B64=$(printf "Quick brown fox. Lazy dog. Rate limiting is set to 100 RPM per user." | base64)

mcp_call ingest_document "$(jq -nc --arg b "$INLINE_B64" '{
  source_type:"inline",
  source_value:$b,
  filename:"smoke.txt",
  kb_name:"smoke-text",
  enable_visual_index:false
}')"
# Expected (notable fields):
# {
#   "backend":"qdrant",
#   "kb_name":"smoke-text",
#   "processing_status":"completed",
#   "chunk_count":1,
#   "page_count":0,
#   "added_to_kb":true,
#   "job_id":null,
#   ...
# }
```

Then verify the chunk landed:

```bash
mcp_call list_knowledge_bases '{"backend":"qdrant"}' | jq '.[] | {name, vectors, visualIndexEnabled}'
# Expected:
# { "name":"smoke-text", "vectors": 1, "visualIndexEnabled": false }
```

### 2c. Search the text-only KB

```bash
mcp_call search_documents '{
  "kb_name":"smoke-text",
  "query":"what is the rate limit?",
  "top_k":3
}' | jq '{fusion_mode, confidence, hits: .hits | map({score, text, confidence})}'
# Expected:
# {
#   "fusion_mode": "text_only",
#   "confidence": "medium" or "high",
#   "hits": [{ "score": <cosine>, "text": "Quick brown fox...", "confidence": "..." }]
# }
```

Notice `fusion_mode: "text_only"` because the KB has no visual index. The
auto-mode fallback resolved correctly.

### 2d. Build a multi-page PDF and ingest with visual indexing

Quick fixture: a 3-page PDF assembled with `qpdf` from `/etc/hostname`-style
content, or any PDF you have at hand. For something deterministic, use a
Python one-liner if available:

```bash
python3 - <<'PY' > /tmp/smoke.pdf
import sys
try:
    from reportlab.pdfgen import canvas
    from reportlab.lib.pagesizes import letter
    c = canvas.Canvas(sys.stdout.buffer, pagesize=letter)
    for i in range(3):
        c.drawString(72, 720, f"Page {i+1} of the smoke-test PDF.")
        c.drawString(72, 700, "Topic: rate limiting strategy details on this page.")
        c.showPage()
    c.save()
except ImportError:
    # Fallback: PDFBox via python-pdfbox or just download a sample.
    sys.exit("Install reportlab (pip install reportlab) or supply your own /tmp/smoke.pdf")
PY

ls -la /tmp/smoke.pdf
```

If reportlab isn't available, any small PDF works. Use a path-based ingest
(filesystem mounted into the container, OR base64 inline):

```bash
PDF_B64=$(base64 < /tmp/smoke.pdf)

mcp_call ingest_document "$(jq -nc --arg b "$PDF_B64" '{
  source_type:"inline",
  source_value:$b,
  filename:"smoke.pdf",
  kb_name:"smoke-visual",
  enable_visual_index:true
}')"
# Expected (3-page PDF is below the default INGEST_ASYNC_THRESHOLD_PAGES=5
# threshold → SYNC path. The call blocks ~5-15s on CPU ColPali, then returns:
# {
#   "backend":"qdrant",
#   "kb_name":"smoke-visual",
#   "file_id":"<doc-uuid>",
#   "processing_status":"completed",
#   "chunk_count": 3 or so,
#   "page_count": 3,
#   "added_to_kb": true,
#   "warnings": [],
#   "job_id": null
# }
```

Capture the doc id for later:

```bash
DOC_ID=$(mcp_call list_knowledge_bases '{"backend":"qdrant"}' \
  | jq -r '.[] | select(.name=="smoke-visual") | .name')
# (or save the file_id from the previous ingest's response)
```

### 2e. Verify both collections exist

```bash
curl -s http://localhost:6333/collections | jq '.result.collections[].name'
# Expected (any order, internal _pages NOT filtered at the Qdrant API level):
# "smoke-text"
# "smoke-visual"
# "smoke-visual_pages"
```

```bash
mcp_call list_knowledge_bases '{"backend":"qdrant"}' | jq
# Expected (note _pages is filtered out by the tool):
# [
#   {"name":"smoke-text",   "visualIndexEnabled": false, ...},
#   {"name":"smoke-visual", "visualIndexEnabled": true,  "visualIndexPages": 3}
# ]
```

### 2f. Fusion search

```bash
mcp_call search_documents '{
  "kb_name":"smoke-visual",
  "query":"rate limiting strategy",
  "top_k":3
}' | jq '{fusion_mode, confidence, warnings, hits: .hits | map({score, page_start, page_end, text_score, page_score, confidence, text: (.text // "(orphan)")[0:60]})}'
# Expected:
# {
#   "fusion_mode": "fusion",
#   "confidence": "high" or "medium",
#   "warnings": [],
#   "hits": [
#     {
#       "score": <RRF score, ~0.03>,
#       "page_start": 1 or 2 or 3,
#       "page_end": same as page_start,
#       "text_score": <cosine>,
#       "page_score": <ColPali MAX_SIM>,
#       "confidence": "high" / "medium" / "low",
#       "text": "Page 1 of the smoke-test PDF. Topic: rate..."
#     },
#     ...
#   ]
# }
```

Things to confirm:

- `fusion_mode: "fusion"` (auto-mode resolved to fusion because visual is present)
- Hits carry both `text_score` and `page_score` (proves the chunk-to-page join worked)
- `confidence` is set per hit and at the response level

### 2g. `inspect_page` round-trip

```bash
# Re-read the doc_id from the visual ingest response, or pull it from any chunk:
DOC_ID=$(curl -s -X POST http://localhost:6333/collections/smoke-visual/points/scroll \
  -H "Content-Type: application/json" \
  -d '{"limit":1,"with_payload":true}' \
  | jq -r '.result.points[0].payload.doc_id')
echo "DOC_ID=$DOC_ID"

mcp_call inspect_page "$(jq -nc --arg d "$DOC_ID" '{
  kb_name:"smoke-visual",
  doc_id:$d,
  page_number:2
}')" | jq '{kbName, docId, pageNumber, width, height, base64Png_len: (.base64Png | length)}'
# Expected:
# {
#   "kbName": "smoke-visual",
#   "docId": "<uuid>",
#   "pageNumber": 2,
#   "width": <pixels, ~1275 at 150 DPI on letter>,
#   "height": <pixels, ~1650>,
#   "base64Png_len": <large, ~100000+>
# }
```

If you want to actually view the PNG:

```bash
mcp_call inspect_page "$(jq -nc --arg d "$DOC_ID" '{kb_name:"smoke-visual",doc_id:$d,page_number:2}')" \
  | jq -r '.base64Png' | base64 -d > /tmp/inspected-page.png
open /tmp/inspected-page.png   # macOS; on Linux: xdg-open
```

### 2h. Async ingest (queued path)

Build a bigger PDF (≥ `INGEST_ASYNC_THRESHOLD_PAGES`, default 5):

```bash
python3 - <<'PY' > /tmp/smoke-big.pdf
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import letter
import sys
c = canvas.Canvas(sys.stdout.buffer, pagesize=letter)
for i in range(10):
    c.drawString(72, 720, f"Page {i+1} of the bigger smoke-test PDF.")
    c.drawString(72, 700, f"This is content for page {i+1}.")
    c.drawString(72, 680, "Topic: rate limiting and throttling strategy.")
    c.showPage()
c.save()
PY

BIG_B64=$(base64 < /tmp/smoke-big.pdf)

mcp_call ingest_document "$(jq -nc --arg b "$BIG_B64" '{
  source_type:"inline",
  source_value:$b,
  filename:"smoke-big.pdf",
  kb_name:"smoke-async",
  enable_visual_index:true
}')"
# Expected (10-page PDF >= threshold → ASYNC path. Call returns IMMEDIATELY):
# {
#   "backend":"qdrant",
#   "kb_name":"smoke-async",
#   "file_id":"<doc-uuid>",
#   "processing_status":"queued",
#   "chunk_count":0,
#   "page_count":0,
#   "added_to_kb":false,
#   "message":"Queued for ingestion as job <jobId> ...",
#   "job_id":"<jobId>"
# }
```

Capture the job id, then poll:

```bash
JOB_ID=$(...)   # from the ingest response

while true; do
  status=$(mcp_call get_ingest_status "$(jq -nc --arg j "$JOB_ID" '{job_id:$j}')")
  current=$(echo "$status" | jq -r .status)
  echo "$(date +%H:%M:%S) $current"
  case "$current" in
    COMPLETED|FAILED) echo "$status" | jq; break ;;
  esac
  sleep 2
done
# Expected progression:
#   01:23:00 QUEUED
#   01:23:02 IN_PROGRESS
#   01:23:04 IN_PROGRESS
#   ...
#   01:23:50 COMPLETED
# Followed by the full IngestJob record with status=COMPLETED and the
# nested result showing chunk_count, page_count, added_to_kb.
```

On a CPU sidecar, a 10-page PDF should resolve in ~30-90 seconds.

### 2i. `colpali_only` search mode

Useful when text is OCR garbage and you want ColPali ranking only:

```bash
mcp_call search_documents '{
  "kb_name":"smoke-visual",
  "query":"rate limiting strategy",
  "top_k":3,
  "retrieval_mode":"colpali_only"
}' | jq '{fusion_mode, hits: .hits | map({score, page_number: .page_start, page_score})}'
# Expected:
# {
#   "fusion_mode": "colpali_only",
#   "hits": [
#     {"score": <ColPali score>, "page_number": <int>, "page_score": <same>},
#     ...
#   ]
# }
```

Note: hits have no `text` (null) since this is pure visual retrieval.

### 2j. Mode-mismatch hard rejection

Try ingesting into the visual KB with `enable_visual_index=false`:

```bash
SHORT_B64=$(printf "second doc" | base64)

mcp_call ingest_document "$(jq -nc --arg b "$SHORT_B64" '{
  source_type:"inline",
  source_value:$b,
  filename:"x.txt",
  kb_name:"smoke-visual",
  enable_visual_index:false
}')"
# Expected: error response with a clear message:
#   "KB 'smoke-visual' was created with visual index enabled, but this call
#    has enable_visual_index=false. Either set enable_visual_index=true or
#    create a new KB."
```

### 2k. `drop_visual_index` dry run + commit

```bash
# Dry run (default):
mcp_call drop_visual_index '{"kb_name":"smoke-visual"}' | jq
# Expected:
# {
#   "kbName": "smoke-visual",
#   "qdrantCollectionDropped": false,
#   "imageFilesRemoved": 0,
#   "message": "Dry run: would drop visual index for KB 'smoke-visual' (currently 3 pages indexed)..."
# }

# Commit:
mcp_call drop_visual_index '{"kb_name":"smoke-visual","confirm":true}' | jq
# Expected:
# {
#   "kbName": "smoke-visual",
#   "qdrantCollectionDropped": true,
#   "imageFilesRemoved": 3,
#   "message": "Dropped visual index for KB 'smoke-visual': Qdrant collection deleted, 3 page-image file(s) removed."
# }

# Verify:
mcp_call list_knowledge_bases '{"backend":"qdrant"}' \
  | jq '.[] | select(.name=="smoke-visual") | {name, visualIndexEnabled, visualIndexPages}'
# Expected:
# { "name": "smoke-visual", "visualIndexEnabled": false, "visualIndexPages": null }
```

The chunk side is untouched — `smoke-visual` continues working as a
text-only KB after this.

## Phase 3 — Failure-mode smoke

### 3a. Sidecar-down at query time → soft-degrade

```bash
docker compose stop colpali-server

mcp_call search_documents '{
  "kb_name":"smoke-async",
  "query":"throttling strategy",
  "top_k":3
}' | jq '{fusion_mode, warnings, hit_count: (.hits | length)}'
# Expected:
# {
#   "fusion_mode": "text_only_fallback",
#   "warnings": ["ColPali sidecar unreachable mid-search; degraded to text_only. ..."],
#   "hit_count": <some number; text-side still works>
# }

docker compose start colpali-server
```

### 3b. Sidecar-down at ingest time → hard fail

```bash
docker compose stop colpali-server

mcp_call ingest_document '{
  "source_type":"inline",
  "source_value":"'"$(printf 'visual ingest attempt' | base64)"'",
  "filename":"y.txt",
  "kb_name":"smoke-new-visual",
  "enable_visual_index":true
}'
# Expected: error:
#   "Visual index requested for KB 'smoke-new-visual' but the ColPali sidecar
#    is unreachable. Retry when the sidecar is available, or call again with
#    enable_visual_index=false to skip the visual side."

docker compose start colpali-server
```

### 3c. Crash recovery — restart mid-ingest

A more involved test; skip if not specifically interested in the queue
recovery semantics.

```bash
# Submit a long async ingest job:
mcp_call ingest_document "$(jq -nc --arg b "$BIG_B64" '{
  source_type:"inline",
  source_value:$b,
  filename:"smoke-big.pdf",
  kb_name:"smoke-restart",
  enable_visual_index:true
}')" | jq -r '.job_id' > /tmp/recovery-job

# While IN_PROGRESS, kill the worker and bring it back:
docker compose restart pdf-rag-http

# Poll the job — should resume from QUEUED (retry_count=1) and eventually complete:
JOB_ID=$(cat /tmp/recovery-job)
mcp_call get_ingest_status "$(jq -nc --arg j "$JOB_ID" '{job_id:$j}')" | jq '{status, retryCount}'
# Expected eventually:
# { "status": "COMPLETED", "retryCount": 1 }
```

## Phase 4 — Cleanup

```bash
# Drop both test KBs.
mcp_call drop_visual_index '{"kb_name":"smoke-async","confirm":true}'

curl -s -X DELETE http://localhost:6333/collections/smoke-text
curl -s -X DELETE http://localhost:6333/collections/smoke-visual
curl -s -X DELETE http://localhost:6333/collections/smoke-async
curl -s -X DELETE http://localhost:6333/collections/smoke-restart

# Or just stop everything:
docker compose down
# (volumes persist for next run; add -v to wipe them too)
```

## What "green" looks like

You're done when:

| Step | Status |
|------|--------|
| All four containers running and healthy | ✓ |
| Per-service `/healthz` returns ok | ✓ |
| `tools/list` returns 7 tools | ✓ |
| Text-only ingest sync, chunk count > 0 | ✓ |
| Visual ingest of small PDF sync, chunk + page counts > 0 | ✓ |
| Fusion search returns hits with both `text_score` AND `page_score` populated | ✓ |
| `inspect_page` returns base64 PNG with sensible dimensions | ✓ |
| Visual ingest of large PDF returns queued; polling resolves to COMPLETED | ✓ |
| Mode-mismatch ingest throws clear error | ✓ |
| `drop_visual_index` dry run + confirm both work | ✓ |
| Sidecar-down query soft-degrades; sidecar-down ingest hard-fails | ✓ |

If any line fails, the troubleshooting tree starts with that section's
expected output — compare and read the relevant component walkthrough in
`docs/components/`.

## Common failures

| Symptom | Likely cause |
|---------|--------------|
| `tools/list` returns `[]` | Jandex index missing from `core` JAR. Rebuild the `pdf-rag-http` image. |
| `ingest_document` hangs forever | Sidecar isn't reaching `ready: true`. Check `docker compose logs colpali-server`. |
| Visual ingest 400 from sidecar | PNG payload too large for sidecar's max batch. Lower `COLPALI_BATCH_SIZE`. |
| `400 Invalid HTTP request received` from llama-server / colpali | HTTP/1.1 pin missing somewhere. Should not happen with our clients; if it does, file an issue. |
| Search returns 0 hits despite ingest succeeding | Check `EMBED_MODEL` consistency between ingest and search (must use same embedder). |
| `inspect_page` throws "no rendered page found" | KB has no visual index, OR doc_id is wrong. Re-verify with `list_knowledge_bases` and a Qdrant scroll. |
| Async job stuck `QUEUED` | Worker isn't running. Check `docker compose logs pdf-rag-http \| grep worker`. |
| OOM during ColPali ingest | Lower `COLPALI_MAX_BATCH_SIZE`. ColSmolVLM-500M needs ~1-2 GB; if the host is tight, reduce concurrency. |
