#!/bin/sh
# Phi bring-up: fetch stock Intel MPSS 3.8.6 (archive.org) and the community
# mpss-modules RPM for the CentOS 7.9 GA kernel 3.10.0-1160.el7, then verify.
set -eux
mkdir -p /srv/vm/mpss
cd /srv/vm/mpss
wget -q -c https://archive.org/download/intel-mpss-3.8.6/mpss-3.8.6-linux.tar
wget -q -c https://archive.org/download/intel-mpss-3.8.6/mpss-src-3.8.6.tar
GH=https://github.com/jjkeijser/mpss/releases/download/RHEL7.9
wget -q -c $GH/mpss-modules-3.10.0-1160.el7.x86_64-3.8.6-4.x86_64.rpm
wget -q -c $GH/mpss-modules-dev-3.10.0-1160.el7.x86_64-3.8.6-4.x86_64.rpm
cat > md5.expected <<'MD5'
8ad2ecfadec5f12213276fc0b588f466  mpss-3.8.6-linux.tar
49f6a8c6cc462e0559894a65c1e488b7  mpss-src-3.8.6.tar
MD5
md5sum -c md5.expected
ls -l
echo MPSS-FETCH-DONE
