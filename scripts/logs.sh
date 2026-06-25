#!/usr/bin/env bash
# Follow container logs.
#
# Usage: scripts/logs.sh [service ...]
#   no args  -> all services
#   service  -> qdrant | llama-server | colpali-server | pdf-rag-http
set -euo pipefail
. "$(dirname "$0")/lib.sh"

load_env
case "${1:-}" in -h|--help) usage; exit 0 ;; esac
dc logs -f --tail=100 "$@"
