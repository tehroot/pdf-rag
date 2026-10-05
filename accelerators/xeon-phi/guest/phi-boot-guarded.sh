#!/bin/sh
# Phi bring-up: first boot of the card's uOS, with a thermal guard.
# Shuts the card down if the die temperature reaches LIMIT_C.
LIMIT_C=85
LOG=/root/mpss/first-boot.log
exec > "$LOG" 2>&1
set -x
[ -f /root/.ssh/id_rsa ] || ssh-keygen -q -t rsa -b 2048 -N "" -f /root/.ssh/id_rsa
micctrl --initdefaults
systemctl start mpss
set +x
end=$(( $(date +%s) + 600 ))
while [ "$(date +%s)" -lt "$end" ]; do
  st=$(cat /sys/class/mic/mic0/state 2>/dev/null)
  t=$(micsmc -t 2>/dev/null | awk '/Cpu Temp/{print int($4); exit}')
  echo "$(date +%T) state=$st die=${t:-na}C"
  if [ -n "$t" ] && [ "$t" -ge "$LIMIT_C" ]; then
    echo "THERMAL LIMIT $t >= $LIMIT_C: shutting the card down"
    micctrl --shutdown mic0 --wait
    exit 2
  fi
  sleep 5
done
echo GUARD-WINDOW-ENDED
