#!/bin/sh
# Phi bring-up, host part: packages, vfio-pci for the Phi, VM forwarding past Docker.
set -eux
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends qemu-system-x86 qemu-utils libvirt-daemon-system libvirt-clients virtinst ovmf dnsmasq-base

# vfio-pci claims the Phi (8086:225c) at boot, and now
echo "options vfio-pci ids=8086:225c" > /etc/modprobe.d/vfio-phi.conf
echo "vfio-pci" > /etc/modules-load.d/vfio-phi.conf
modprobe vfio-pci
echo vfio-pci > /sys/bus/pci/devices/0000:04:00.0/driver_override
echo 0000:04:00.0 > /sys/bus/pci/drivers_probe || true

# Let libvirt NAT traffic through the FORWARD DROP policy that Docker sets (persistent)
cat > /etc/systemd/system/libvirt-docker-forward.service <<'UNIT'
[Unit]
Description=Accept libvirt virbr0 traffic in the DOCKER-USER chain
After=docker.service libvirtd.service
Wants=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/sh -c 'iptables -C DOCKER-USER -i virbr0 -j ACCEPT 2>/dev/null || iptables -I DOCKER-USER -i virbr0 -j ACCEPT; iptables -C DOCKER-USER -o virbr0 -j ACCEPT 2>/dev/null || iptables -I DOCKER-USER -o virbr0 -j ACCEPT'

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now libvirtd
virsh net-autostart default
virsh net-start default || true
systemctl enable --now libvirt-docker-forward.service
usermod -aG libvirt markcardish || true
echo HOST-SETUP-DONE
