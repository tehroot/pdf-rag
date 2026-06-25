#!/usr/bin/env bash
# Run the test suites.
#
# Usage: scripts/test.sh [--core|--full|--sidecar|--all]
#   (default) --core : fast core unit tests   (mvn -pl core test)
#   --full           : all Java modules + tests (mvn -B package)
#   --sidecar        : Python sidecar tests     (pytest in sidecar/.venv)
#   --all            : --full then --sidecar
set -euo pipefail
. "$(dirname "$0")/lib.sh"

MODE="core"
case "${1:-}" in
  --core|"") MODE="core" ;;
  --full) MODE="full" ;;
  --sidecar) MODE="sidecar" ;;
  --all) MODE="all" ;;
  -h|--help) usage; exit 0 ;;
  *) die "unknown arg: $1" ;;
esac

run_java() {
  require_cmd mvn
  if [ "$1" = "full" ]; then
    info "mvn -B package (all modules + tests)"
    ( cd "$ROOT" && run mvn -B package )
  else
    info "mvn -pl core test (fast, no external services)"
    ( cd "$ROOT" && run mvn -pl core test )
  fi
}

run_sidecar() {
  if [ ! -x "$ROOT/sidecar/.venv/bin/pytest" ]; then
    warn "sidecar venv not found — creating it (scripts/bootstrap.sh --sidecar)"
    require_cmd python3
    python3 -m venv "$ROOT/sidecar/.venv"
    "$ROOT/sidecar/.venv/bin/pip" install -q --upgrade pip
    "$ROOT/sidecar/.venv/bin/pip" install -q -e "$ROOT/sidecar[dev]"
  fi
  info "sidecar pytest"
  ( cd "$ROOT/sidecar" && run ./.venv/bin/pytest -q )
}

case "$MODE" in
  core)    run_java core ;;
  full)    run_java full ;;
  sidecar) run_sidecar ;;
  all)     run_java full; run_sidecar ;;
esac
ok "tests passed"
