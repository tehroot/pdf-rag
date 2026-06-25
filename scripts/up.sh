#!/usr/bin/env bash
# Start the whole stack (qdrant + llama-server + colpali-server + pdf-rag-http)
# in the background. compose waits for qdrant + llama healthchecks before
# starting pdf-rag-http.
#
# Usage: scripts/up.sh [--gpu] [--build] [service ...]
#   --gpu     layer docker-compose.gpu.yml + use the CUDA sidecar
#   --build   (re)build images before starting
#   service.. start only specific services
set -euo pipefail
. "$(dirname "$0")/lib.sh"

init_env
UP_ARGS=(-d)
SERVICES=()
for arg in "$@"; do
  case "$arg" in
    --gpu) enable_gpu ;;
    --build) UP_ARGS+=(--build) ;;
    -h|--help) usage; exit 0 ;;
    -*) die "unknown flag: $arg" ;;
    *) SERVICES+=("$arg") ;;
  esac
done

# Friendly pre-flight: llama-server can't start without its model file.
MODEL_FILE="$(env_or LLAMA_MODEL_FILE bge-small-en-v1.5-f16.gguf)"
if [ ! -f "$ROOT/models/$MODEL_FILE" ]; then
  warn "models/$MODEL_FILE is missing — llama-server will fail to start."
  warn "run scripts/bootstrap.sh first (downloads the embedding model)."
fi

[ "${GPU:-0}" = "1" ] && info "starting stack (GPU)" || info "starting stack (CPU)"
dc up "${UP_ARGS[@]}" "${SERVICES[@]}"

ok "stack starting. MCP: http://localhost:$PDF_RAG_PORT/mcp   REST: http://localhost:$PDF_RAG_PORT/ingest/directory"
info "check health with scripts/status.sh, follow logs with scripts/logs.sh"
