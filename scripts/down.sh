#!/usr/bin/env bash
# Stop and remove the stack's containers (and network).
#
# Usage: scripts/down.sh [--volumes]
#   --volumes  ALSO delete the named volumes (qdrant-data, hf-cache,
#              page-images, ingest-queue) — destroys indexed data + model cache.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

load_env
DOWN_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --volumes|-v) DOWN_ARGS+=(--volumes) ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $arg" ;;
  esac
done

# The GPU overlay only adds a device reservation; `down` works against the base
# file regardless, so we don't need --gpu here.
if printf '%s\n' "${DOWN_ARGS[@]:-}" | grep -q volumes; then
  warn "this will DELETE all named volumes (Qdrant data, HF cache, page images, queue)"
  confirm "continue?" || die "aborted"
fi
dc down --remove-orphans "${DOWN_ARGS[@]}"
ok "stack stopped"
