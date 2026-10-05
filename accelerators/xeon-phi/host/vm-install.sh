#!/bin/sh
# Phi bring-up: create the phi-host VM and install CentOS 7.9 unattended.
# The Phi is attached after the install, so the installer does not touch it.
set -eux
mkdir -p /srv/vm
cp /home/markcardish/phi/phi-ks.cfg /srv/vm/phi-ks.cfg
CODE=$(ls /usr/share/OVMF/OVMF_CODE_4M.fd /usr/share/OVMF/OVMF_CODE.fd 2>/dev/null | head -1)
VARS=$(ls /usr/share/OVMF/OVMF_VARS_4M.fd /usr/share/OVMF/OVMF_VARS.fd 2>/dev/null | head -1)
virt-install --name phi-host --memory 16384 --vcpus 8 --cpu host-passthrough \
  --machine q35 \
  --boot loader="$CODE",loader.readonly=yes,loader.type=pflash,nvram.template="$VARS" \
  --disk path=/srv/vm/phi-host.qcow2,size=60,format=qcow2,bus=virtio \
  --network network=default,model=virtio \
  --location http://vault.centos.org/7.9.2009/os/x86_64/ \
  --initrd-inject /srv/vm/phi-ks.cfg \
  --extra-args "inst.ks=file:/phi-ks.cfg console=ttyS0,115200 inst.text" \
  --qemu-commandline='-fw_cfg name=opt/ovmf/X-PciMmio64Mb,string=65536' \
  --graphics none --console pty,target_type=serial \
  --os-variant centos7.0 --noautoconsole --wait -1
virsh autostart phi-host || true
echo VM-INSTALL-DONE
