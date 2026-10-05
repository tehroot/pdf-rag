#!/bin/sh
# Phi bring-up: pass the Xeon Phi (0000:04:00.0) through to phi-host.
set -eux
cat > /srv/vm/phi-hostdev.xml <<'XML'
<hostdev mode='subsystem' type='pci' managed='yes'>
  <source>
    <address domain='0x0000' bus='0x04' slot='0x00' function='0x0'/>
  </source>
</hostdev>
XML
virsh shutdown phi-host || true
for i in $(seq 1 60); do
  [ "$(virsh domstate phi-host)" = "shut off" ] && break
  sleep 2
done
virsh domstate phi-host
# A fresh CentOS 7 guest can ignore the ACPI shutdown; then power it off from inside:
#   ssh root@<guest> poweroff
# Check inside <hostdev>: a q35 guest also has virtual controllers on bus 0x04.
virsh dumpxml --inactive phi-host | grep -A3 "<hostdev" | grep -q "bus='0x04'" \
  || virsh attach-device phi-host /srv/vm/phi-hostdev.xml --config
virsh start phi-host
echo PHI-ATTACH-DONE
