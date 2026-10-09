#!/bin/bash
# CIMC virtual-media mappings over HTTP (map='www'), served by serve.py.
#   vmedia.sh list
#   vmedia.sh map <volume-name> <iso-file> [<base-url>]   (default base-url http://192.168.1.76:8099/)
#   vmedia.sh unmap <volume-name>
# After 'map', boot the host with F6 and pick "Cisco CIMC-Mapped vDVD".
source "$(dirname "$0")/cimc_lib.sh"
SVC=sys/svc-ext/vmedia-svc

case "$1" in
  list)
    cimc_login || exit 1
    cimc_post "<configResolveChildren cookie='$CIMC_COOKIE' inDn='$SVC' inHierarchical='false'/>" \
      | grep -oE '<commVMediaMap [^>]*>' || echo "no mappings" ;;
  map)
    [ -n "$2" ] && [ -n "$3" ] || { sed -n '2,6p' "$0"; exit 2; }
    base=${4:-http://192.168.1.76:8099/}
    # Check the server first: the CIMC error for a bad URL is vague.
    curl -sfI -m 10 "$base$3" >/dev/null || { echo "not reachable: $base$3 (is serve.py running?)" >&2; exit 1; }
    cimc_login || exit 1
    # Do not send mountOptions: the CIMC rejects an empty value.
    cimc_post "<configConfMo cookie='$CIMC_COOKIE' dn='$SVC/vmmap-$2' inHierarchical='false'><inConfig><commVMediaMap dn='$SVC/vmmap-$2' volumeName='$2' map='www' remoteShare='$base' remoteFile='$3' status='created'/></inConfig></configConfMo>" \
      | grep -oE '(mappingStatus|errorDescr)="[^"]*"' ;;
  unmap)
    [ -n "$2" ] || { sed -n '2,6p' "$0"; exit 2; }
    cimc_login || exit 1
    cimc_post "<configConfMo cookie='$CIMC_COOKIE' dn='$SVC/vmmap-$2' inHierarchical='false'><inConfig><commVMediaMap dn='$SVC/vmmap-$2' status='removed'/></inConfig></configConfMo>" \
      | grep -oE '(errorCode|errorDescr)="[^"]*"' || echo "removed $2" ;;
  *) sed -n '2,6p' "$0"; exit 2 ;;
esac
