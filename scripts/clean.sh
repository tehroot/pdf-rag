#!/usr/bin/env bash
# Tear everything down and remove build artifacts. Destructive — prompts first.
#
# Usage: scripts/clean.sh [--all] [--yes]
#   (default)  stop stack + delete its volumes, then `mvn clean`
#   --all      also remove the sidecar venv (.venv); keeps ./models and .env
#   --yes      don't prompt (for CI)
set -euo pipefail
. "$(dirname "$0")/lib.sh"

load_env
ALL=0
for arg in "$@"; do
  case "$arg" in
    --all) ALL=1 ;;
    --yes|-y) ASSUME_YES=1 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $arg" ;;
  esac
done

warn "this stops the stack and DELETES its named volumes (Qdrant data, HF cache,"
warn "page images, queue) and Maven target/ dirs. ./models, ./incoming and .env are kept."
confirm "continue?" || die "aborted"

dc down --volumes --remove-orphans 2>/dev/null || true
ok "stack + volumes removed"

if command -v mvn >/dev/null 2>&1; then
  ( cd "$ROOT" && run mvn -q clean ) && ok "maven target/ cleaned"
fi

if [ "$ALL" = "1" ] && [ -d "$ROOT/sidecar/.venv" ]; then
  rm -rf "$ROOT/sidecar/.venv"
  ok "sidecar venv removed"
fi
ok "clean complete"
