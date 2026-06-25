#!/usr/bin/env bash
# Restart one or all services (without recreating containers).
#
# Usage: scripts/restart.sh [--gpu] [service ...]
set -euo pipefail
. "$(dirname "$0")/lib.sh"

load_env
SERVICES=()
for arg in "$@"; do
  case "$arg" in
    --gpu) enable_gpu ;;
    -h|--help) usage; exit 0 ;;
    *) SERVICES+=("$arg") ;;
  esac
done
dc restart "${SERVICES[@]}"
ok "restarted ${SERVICES[*]:-all services}"
