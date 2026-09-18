#!/usr/bin/env bash
# Corpus-sizing scan: project pages, chunks, embed time, and storage for a
# directory of PDFs BEFORE committing it to the ingest pipeline.
#
# Usage: scripts/size-corpus.sh <directory> [CorpusSizer options]
#   e.g. scripts/size-corpus.sh /mnt/windisk/backup_files/milpdfs --sample 500 --json /tmp/sizing.json
#   (run with no args to see the full option list)
#
# Runs org.hayden.sizing.CorpusSizer from the built server-http quarkus-app
# (building it first if needed). Requires Java 21 on the host.
#
# On a deployed host without Java, run it inside the pdf-rag-http container
# instead (paths are container-side, e.g. under /host). The image must have
# been built from a commit that contains CorpusSizer — after pulling, rebuild
# with scripts/build-images.sh (or docker compose build pdf-rag-http) first:
#   docker compose exec pdf-rag-http java \
#     -cp '/app/app/*:/app/lib/main/*:/app/lib/boot/*' \
#     org.hayden.sizing.CorpusSizer /host/<path> --sample 500
set -euo pipefail
. "$(dirname "$0")/lib.sh"

APP_DIR="$ROOT/server-http/target/quarkus-app"

# Rebuild when the built core jar is missing OR predates CorpusSizer — a bare
# directory-existence check lets a stale pre-CorpusSizer build through and dies
# with ClassNotFound. Zip entry names are stored uncompressed, so grep-ing the
# jar finds the class without needing unzip; -a forces a raw byte scan (BSD
# grep's binary-file handling otherwise misses matches past NUL bytes).
core_jar="$(ls "$APP_DIR"/lib/main/org.hayden.pdf-rag-ingest-core-*.jar 2>/dev/null | head -n1 || true)"
if [ -z "$core_jar" ] || ! LC_ALL=C grep -qa 'org/hayden/sizing/CorpusSizer.class' "$core_jar"; then
  require_cmd mvn
  info "building server-http (missing or stale build)"
  ( cd "$ROOT" && run mvn -pl server-http -am package -DskipTests -q )
fi

require_cmd java
# lib/boot has jboss-logging, which PDFBox's commons-logging shim resolves to;
# the logmanager property quiets the JBoss LogManager startup warning.
exec java -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -cp "$APP_DIR/app/*:$APP_DIR/lib/main/*:$APP_DIR/lib/boot/*" \
  org.hayden.sizing.CorpusSizer "$@"
