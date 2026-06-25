#!/usr/bin/env bash
# Full local dev pipeline, end to end: test -> build images -> up -> smoke.
# This is the "does everything still work?" button.
#
# Usage: scripts/pipeline.sh [--gpu] [--full] [--no-test]
#   --gpu      build/run the GPU (CUDA) sidecar variant
#   --full     run the full Java build + sidecar tests (default: fast core tests)
#   --no-test  skip the test stage (just build images + up + smoke)
set -euo pipefail
. "$(dirname "$0")/lib.sh"

GPU_FLAG=""
TEST_MODE="--core"
RUN_TESTS=1
for arg in "$@"; do
  case "$arg" in
    --gpu) GPU_FLAG="--gpu" ;;
    --full) TEST_MODE="--all" ;;
    --no-test) RUN_TESTS=0 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown arg: $arg" ;;
  esac
done

step() { echo; info "[pipeline] $*"; }

if [ "$RUN_TESTS" = "1" ]; then
  step "1/4 tests ($TEST_MODE)"
  "$SCRIPTS_DIR/test.sh" "$TEST_MODE"
else
  step "1/4 tests — skipped (--no-test)"
fi

step "2/4 build images $GPU_FLAG"
"$SCRIPTS_DIR/build-images.sh" $GPU_FLAG

step "3/4 start stack $GPU_FLAG"
"$SCRIPTS_DIR/up.sh" $GPU_FLAG

step "4/4 wait for readiness, then smoke"
init_env
# Give colpali (model load) + pdf-rag-http a moment; poll up to ~90s.
i=0
while [ "$i" -lt 30 ]; do
  if [ "$(http_code "http://localhost:$PDF_RAG_PORT/mcp" 3)" != "000" ] \
     && curl -fsS -m 3 "http://localhost:$COLPALI_PORT/healthz" 2>/dev/null | grep -q '"ready":true'; then
    break
  fi
  i=$((i + 1)); sleep 3
done
"$SCRIPTS_DIR/smoke.sh"

echo
ok "pipeline complete — stack is up. Tear down with scripts/down.sh"
