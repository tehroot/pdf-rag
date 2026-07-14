#!/usr/bin/env bash
# Build the container images via docker compose (the pdf-rag-http image runs its
# own multi-stage Maven build inside Docker, so local Maven is NOT required).
# Builds the two services with a build context: colpali-server and pdf-rag-http.
# qdrant and llama-server are upstream images (pulled, not built).
#
# Usage: scripts/build-images.sh [--gpu] [--no-cache] [service ...]
#   --gpu       build the CUDA sidecar image (Dockerfile.cuda) instead of CPU
#   --no-cache  rebuild from scratch
#   service...  limit to specific services (e.g. pdf-rag-http)
set -euo pipefail
. "$(dirname "$0")/lib.sh"

load_env
BUILD_ARGS=()
SERVICES=()
for arg in "$@"; do
  case "$arg" in
    --gpu) enable_gpu ;;
    --no-cache) BUILD_ARGS+=(--no-cache) ;;
    -h|--help) usage; exit 0 ;;
    -*) die "unknown flag: $arg" ;;
    *) SERVICES+=("$arg") ;;
  esac
done

if [ "${GPU:-0}" = "1" ]; then
  info "building images (GPU: sidecar=$COLPALI_DOCKERFILE)"
else
  info "building images (CPU sidecar)"
fi
dc build "${BUILD_ARGS[@]}" "${SERVICES[@]}"
ok "images built"
1