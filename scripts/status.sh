#!/usr/bin/env bash
# Show container status plus a quick HTTP health probe of each service.
#
# Usage: scripts/status.sh
set -euo pipefail
. "$(dirname "$0")/lib.sh"

init_env
case "${1:-}" in -h|--help) usage; exit 0 ;; esac

info "containers"
dc ps || warn "could not list containers (is the Docker daemon running?)"

echo
info "health probes"
probe() { # probe NAME URL EXPECT-DESC
  local code; code="$(http_code "$2" 3)"
  if [ "$code" = "000" ]; then
    printf '  %s%-16s%s down (no response)\n' "$_C_RED" "$1" "$_C_RESET"
  else
    printf '  %s%-16s%s up   (HTTP %s) %s\n' "$_C_GREEN" "$1" "$_C_RESET" "$code" "$2"
  fi
}
probe "qdrant"        "http://localhost:$QDRANT_PORT/healthz"
probe "llama-server"  "http://localhost:$LLAMA_PORT/health"
probe "colpali-server" "http://localhost:$COLPALI_PORT/healthz"
# pdf-rag-http has no liveness route that 2xx's; /mcp answering at all = up.
probe "pdf-rag-http"  "http://localhost:$PDF_RAG_PORT/mcp"
