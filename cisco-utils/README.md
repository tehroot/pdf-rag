# cisco-utils

Scripts for the Cisco UCS C240 M5 (`UCSC-C240-M5L`, serial WZP22320UPL), the
replacement for the R530 huge-dumb. They use the CIMC XML API (`/nuova`),
because Redfish is disabled on this CIMC.

## Files

| File | Runs on | Purpose |
|---|---|---|
| `cimc_lib.sh` | Mac | Shared login and logout. The other scripts source it. |
| `cimc.sh` | Mac | Read-only queries (`configResolveClass`). |
| `cimc_conf.sh` | Mac | Sends one XML call. **It can change the server.** |
| `vmedia.sh` | Mac | Lists, creates and removes the vMedia mappings (`map='www'`). |
| `serve.py` | huge-dumb | Read-only ISO server with `Range` support, for the vMedia. |

## Settings

| Variable | Default |
|---|---|
| `CIMC_HOST` | `192.168.1.160` (Shared LOM, DHCP). The plan: Dedicated mode, reserved `.21`. |
| `CIMC_USER` | `admin` |
| `CIMC_PW_FILE` | `~/.config/cisco-utils/cimc.pw` (mode 600) |

**Do not put the password file in this folder.** It is outside git on purpose.

## Boot an ISO through the CIMC vMedia

1. Put `serve.py` and the ISO in one directory on huge-dumb (now `~/huu/`).
2. Start the server. It reads the ISO list only at startup, so restart it after you add an ISO:

   ```bash
   ssh -f markcardish@192.168.1.76 'cd ~/huu && setsid nohup python3 serve.py 8099 >> serve.log 2>&1 < /dev/null &'
   ```

3. Map the ISO, then check that the output shows `mappingStatus="OK"`:

   ```bash
   ./vmedia.sh map DEB13 debian-13.7.0-amd64-netinst.iso
   ```

4. In the KVM: power cycle, press **F6**, and select **"Cisco CIMC-Mapped vDVD"**.
5. After the work, remove the mapping and stop the server:

   ```bash
   ./vmedia.sh unmap DEB13
   ssh markcardish@192.168.1.76 'pkill -xf "python3 serve.py 8099"'
   ```

## Known problems (found during the 2026-10-07 bring-up)

- `commVMediaMap` rejects an empty `mountOptions`. Do not send the attribute.
- The CIMC `httpfs2` client needs HTTP/1.1 and `Range`. With HTTP/1.0 the
  mapping fails: `mount.httpfs2: HEAD: reply does not contain status`.
- During a CIMC restart, the login fails for some minutes. A watcher must not
  report that as a failure.
- The HUU 4.3(2) accepts only 4.0(4) or later as a source. From 4.0(1a), go
  through 4.1(3n) first.
- The Device Connector (Intersight) cannot be disabled through the XML API.
  Use the web UI: Admin → Device Connector.
