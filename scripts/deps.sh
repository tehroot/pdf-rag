#!/usr/bin/env bash
# Print the ML dependency versions actually resolved inside the sidecar image.
#
# sidecar/pyproject.toml pins loose ranges and there is no lock file, so every
# image build re-resolves — two builds of the same commit can ship different
# transformers/colpali-engine versions. This reports what a given image really
# got, which is the input to pinning them (and the first thing to check when
# the sidecar loads a model on one host and dies on another).
#
# Runs against the IMAGE, not a running container, so it works while the
# sidecar is crash-looping.
#
# Usage: scripts/deps.sh [--gpu] [--all]
#   --gpu   inspect the CUDA image (matches how the stack is built on a GPU host)
#   --all   full pip freeze instead of just the ML packages
set -euo pipefail
. "$(dirname "$0")/lib.sh"

load_env
ALL=0
for arg in "$@"; do
  case "$arg" in
    --gpu) enable_gpu ;;
    --all) ALL=1 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $arg" ;;
  esac
done

# `compose run --no-deps` starts nothing else: qdrant and llama-server are
# irrelevant to a dependency listing, and the sidecar's own healthcheck is
# bypassed because we override the entrypoint.
if [ "$ALL" = "1" ]; then
  info "full pip freeze from the sidecar image"
  dc run --rm --no-deps --entrypoint pip colpali-server freeze
else
  info "ML dependency versions in the sidecar image"
  dc run --rm --no-deps --entrypoint pip colpali-server freeze \
    | grep -Ei '^(transformers|colpali-engine|torch|accelerate|numpy|pillow)==' \
    || warn "no matching packages — is this a [ml]-extras image?"
fi

echo
info "pyproject declares (sidecar/pyproject.toml):"
grep -E '^\s+"(transformers|colpali-engine|torch|accelerate|numpy)' "$ROOT/sidecar/pyproject.toml" \
  | sed 's/^/    /'
echo
warn "no lock file: these ranges re-resolve on every build. Pin them once the"
warn "running set is known good — see the transformers 5.x note in CLAUDE.md."
