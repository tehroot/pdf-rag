#!/usr/bin/env bash
# Smoke-test a running stack: probe each service and exercise the pdf-rag-http
# REST surface end to end (directory ingest + status/delete validation paths).
# Does NOT require Qdrant to hold data — it checks wiring, not retrieval quality.
#
# Usage: scripts/smoke.sh
set -euo pipefail
. "$(dirname "$0")/lib.sh"

init_env
require_cmd curl
case "${1:-}" in -h|--help) usage; exit 0 ;; esac

FAILED=0
check() { # check NAME CONDITION-CMD...
  if "${@:2}" >/dev/null 2>&1; then ok "$1"; else err "$1"; FAILED=1; fi
}

BASE="http://localhost:$PDF_RAG_PORT"

info "service liveness"
check "qdrant reachable"        sh -c "[ \"$(http_code http://localhost:$QDRANT_PORT/healthz)\" != 000 ]"
check "llama-server reachable"  sh -c "[ \"$(http_code http://localhost:$LLAMA_PORT/health)\" != 000 ]"
check "colpali-server ready"    sh -c "curl -fsS -m 5 http://localhost:$COLPALI_PORT/healthz | grep -q ready"
check "pdf-rag-http reachable"  sh -c "[ \"$(http_code $BASE/mcp)\" != 000 ]"

echo
info "REST surface (pdf-rag-http)"
# Unknown job → 404 proves the route is wired.
check "GET /ingest/status/{id} routes (404)" \
  sh -c "[ \"$(http_code $BASE/ingest/status/__smoke__)\" = 404 ]"
# Missing kb_name → 400 proves validation + the exception mapper.
del_code() { curl -s -o /dev/null -m 5 -w '%{http_code}' -X DELETE "$BASE/ingest/document?doc_id=x" 2>/dev/null || echo 000; }
check "DELETE /ingest/document validates (400)" sh -c "[ \"$(del_code)\" = 400 ]"
# Directory ingest of an empty temp dir → 200 with files_found:0.
TMPD="$(mktemp -d 2>/dev/null || echo /tmp/pdf-rag-smoke.$$)"
mkdir -p "$TMPD"
RESP="$(curl -s -m 30 -X POST "$BASE/ingest/directory" -H 'Content-Type: application/json' \
  -d "{\"directory\":\"$TMPD\",\"kb_name\":\"__smoke__\"}" 2>/dev/null || true)"
rmdir "$TMPD" 2>/dev/null || true
if printf '%s' "$RESP" | grep -q '"files_found"'; then
  ok "POST /ingest/directory returns a summary"
else
  err "POST /ingest/directory (got: ${RESP:-no response})"; FAILED=1
fi

echo
info "upload ingest (POST /ingest/upload)"
# A minimal one-page PDF, generated on the fly. ingest=false keeps this a
# wiring check (store + doc-id assignment) with no dependency on the
# embedding pipeline being warm.
SMOKE_PDF="$(mktemp 2>/dev/null || echo /tmp/pdf-rag-smoke-pdf.$$)"
printf '%%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\nxref\n0 4\ntrailer<</Size 4/Root 1 0 R>>\n%%%%EOF\n' > "$SMOKE_PDF"
UP_RESP="$(curl -s -m 30 -X POST "$BASE/ingest/upload" \
  -F kb_name=__smoke__ -F ingest=false -F "files=@$SMOKE_PDF;filename=smoke.pdf" 2>/dev/null || true)"
rm -f "$SMOKE_PDF"
if printf '%s' "$UP_RESP" | grep -q '"status":"stored"'; then
  ok "POST /ingest/upload stores a file"
else
  err "POST /ingest/upload (got: ${UP_RESP:-no response})"; FAILED=1
fi
SMOKE_DOC_ID="$(printf '%s' "$UP_RESP" | sed -n 's/.*"doc_id":"\([^"]*\)".*/\1/p' | head -1)"
# Proof the file is really in the store: a second upload of the same name
# with on_conflict=reject must fail per-file with "already exists".
SMOKE_PDF2="$(mktemp 2>/dev/null || echo /tmp/pdf-rag-smoke-pdf2.$$)"
printf '%%PDF-1.4 second copy\n' > "$SMOKE_PDF2"
RE_RESP="$(curl -s -m 30 -X POST "$BASE/ingest/upload" \
  -F kb_name=__smoke__ -F ingest=false -F on_conflict=reject \
  -F "files=@$SMOKE_PDF2;filename=smoke.pdf" 2>/dev/null || true)"
rm -f "$SMOKE_PDF2"
if printf '%s' "$RE_RESP" | grep -q 'on_conflict=reject'; then
  ok "stored file exists (re-upload with on_conflict=reject refused)"
else
  err "upload store existence check (got: ${RE_RESP:-no response})"; FAILED=1
fi
# Clean up: delete_source removes both the (empty) index entry and the file.
if [ -n "$SMOKE_DOC_ID" ]; then
  DEL_RESP="$(curl -s -m 10 -X DELETE \
    "$BASE/ingest/document?kb_name=__smoke__&doc_id=$SMOKE_DOC_ID&delete_source=true" 2>/dev/null || true)"
  if printf '%s' "$DEL_RESP" | grep -q 'Source file deleted'; then
    ok "DELETE /ingest/document?delete_source=true removes the stored file"
  else
    err "delete_source cleanup (got: ${DEL_RESP:-no response})"; FAILED=1
  fi
fi

echo
if [ "$FAILED" = "0" ]; then ok "smoke passed"; else die "smoke FAILED"; fi
