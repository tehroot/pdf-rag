#!/bin/bash
# Shared login/logout for the CIMC XML API (/nuova). Source this file; do not run it.
#   CIMC_HOST     CIMC address            (default 192.168.1.160)
#   CIMC_USER     CIMC user               (default admin)
#   CIMC_PW_FILE  file with the password  (default ~/.config/cisco-utils/cimc.pw, mode 600)
# Redfish is disabled on this CIMC, so everything goes through the XML API.

CIMC_HOST=${CIMC_HOST:-192.168.1.160}
CIMC_USER=${CIMC_USER:-admin}
CIMC_PW_FILE=${CIMC_PW_FILE:-$HOME/.config/cisco-utils/cimc.pw}
CIMC_URL=https://$CIMC_HOST/nuova

# POST one XML body (read from stdin, so the password never appears in argv).
cimc_post() { curl -sk -m "${2:-60}" -X POST "$CIMC_URL" --data-binary @- <<<"$1"; }

cimc_login() {
  [ -r "$CIMC_PW_FILE" ] || { echo "no password file: $CIMC_PW_FILE" >&2; return 1; }
  local pw r
  pw=$(<"$CIMC_PW_FILE")
  r=$(cimc_post "<aaaLogin inName='$CIMC_USER' inPassword='$pw'></aaaLogin>" 20)
  CIMC_COOKIE=$(echo "$r" | grep -oE 'outCookie="[^"]+"' | cut -d'"' -f2)
  [ -n "$CIMC_COOKIE" ] || { echo "LOGIN FAILED: $(echo "$r" | grep -oE 'errorDescr="[^"]*"')" >&2; return 1; }
  # Release the session on exit; the CIMC allows only a few concurrent sessions.
  trap cimc_logout EXIT
}

cimc_logout() {
  [ -n "$CIMC_COOKIE" ] && cimc_post "<aaaLogout cookie='$CIMC_COOKIE' inCookie='$CIMC_COOKIE'></aaaLogout>" 10 >/dev/null
  CIMC_COOKIE=
}
