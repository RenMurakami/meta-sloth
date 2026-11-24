# meta-sloth

Yocto layer for **Sloth** — a thermal-camera deer detection ECU on a
Jetson Orin Nano Super devkit. This repository is the product software stack:
the `sloth` distro, the `sloth-orin-nano-nvme` (product) and
`sloth-orin-nano-sd` machines, the images, and the product-specific recipes.

The **build environment is a separate repository**,
[`yocto-jetson-buildenv`](https://github.com/RenMurakami/yocto-jetson-buildenv)
(Docker image, `site.conf`, shared caches). Nothing in this layer assumes a
particular host — it only assumes the layer stack below.

| | |
|---|---|
| Yocto release | 6.0 "wrynose" (`LAYERSERIES_COMPAT_sloth`) |
| BSP | OE4T `meta-tegra` wrynose → Jetson Linux R39.2.1 / JetPack 7.2.1 |
| Target | Jetson Orin Nano Super devkit (p3768), booting from the NVMe SSD (product) or microSD |
| Layer priority | `60` |

## Dependencies

`LAYERDEPENDS_sloth` requires these collections:

    core  tegra  tegrademo  openembedded-layer  meta-python  networking-layer

`tegrademo` is there because `sloth.conf` starts with
`require conf/distro/include/tegrademo.inc`. In practice that is an OE4T
`tegra-demo-distro` checkout on the `wrynose` branch, which carries all of them.
The exact commits are pinned in
[`kas/sloth-orin-nano-fetch.yml`](kas/sloth-orin-nano-fetch.yml). This layer is added to such a tree from
outside it — it is never copied in. The reference build has it in
`build-sd/conf/bblayers.conf` as a path alongside the demo's own layers.

## What this layer provides

### Distro — `sloth`

[`conf/distro/sloth.conf`](conf/distro/sloth.conf) builds on the demo's
`tegrademo.inc` and then narrows it:

- `DISTRO_FEATURES = "pam overlayfs"` — no `virtualization`; we ship no containers.
  `overlayfs` is not in `DISTRO_FEATURES_DEFAULTS`, so it is stated explicitly.
- `DISTRO_FEATURES_OPTED_OUT` drops x11, wayland, vulkan, bluetooth, 3g, nfc,
  nfs, zeroconf, ptest and debuginfod. **Never opt out of `opengl`** —
  `tensorrt-core` has `REQUIRED_DISTRO_FEATURES = "opengl"`.
- `EXTRA_IMAGE_FEATURES = ""` — the demo's passwordless root is a development
  convenience, not a product setting. `sloth-image-dev` puts it back deliberately.
- `USE_REDUNDANT_FLASH_LAYOUT_DEFAULT = "1"` — A/B from the first flash, because
  retrofitting it later means repartitioning.
- `INHERIT:remove = "tegra-support-sanity distro_layer_buildinfo"` is
  **mandatory**: `tegra-support-sanity` rewrites `bblayers.conf` from
  `templates/<DISTRO>/`, and `templates/sloth/` does not exist.

### Machines

| Machine | Boots from | Rootfs slots | Status |
|---|---|---|---|
| [`sloth-orin-nano-nvme`](conf/machine/sloth-orin-nano-nvme.conf) | NVMe SSD (`jetson-orin-nano-devkit-nvme`) | 2 × 48 GiB | Product layout. Running on the bench board since 2026-11-22, rescue system included. |
| [`sloth-orin-nano-sd`](conf/machine/sloth-orin-nano-sd.conf) | microSD (`jetson-orin-nano-devkit`) | 2 × 14 GiB (meta-tegra default) | The original bring-up machine. Verified 2026-11-11. |

The product layout on the NVMe machine (1 TB SSD):

| Partition | Size | Holds |
|---|---|---|
| `APP`, `APP_b` | 48 GiB each | rootfs slot A and B (`ROOTFSPART_SIZE_DEFAULT`, which meta-tegra halves for A/B) |
| `/data` | 48 GiB (`SLOTH_DATA_SIZE`) | `/etc` overlays (SSH host keys, Wi-Fi, Tailscale), OTA staging `/data/ota` |
| `/rec` | the rest | recordings |
| `recovery` | 160 MiB (`TEGRA_RECOVERY_KERNEL_PART_SIZE`) | the rescue system (`sloth-rescue-image`), `recovery-dtb` next to it holds the kernel DTB |
| other NVIDIA partitions | about 1.5 GiB | kernel, kernel-dtb and esp, each A and B; `recovery_alt`, `UDA`, `reserved` (empty) |

**Rescue system.** When both rootfs slots are marked unbootable, L4TLauncher
boots the `recovery` partition instead of `APP`. On the NVMe machine that
partition holds [`sloth-rescue-image`](recipes-core/images/sloth-rescue-image.bb):
the kernel plus a small initramfs that runs from RAM and brings up wired
Ethernet, SSH (keys only) and login prompts on the serial console and HDMI. It
does not touch any disk by itself. No SD card is involved, so it also works on
production modules, which have no SD slot.

- NVIDIA's template gives `recovery` 80 MiB; the kernel alone is about 42 MiB.
  The machine config raises it to 160 MiB, which grows `recovery_alt` too, and
  [`tegra-storage-layout_%.bbappend`](recipes-bsp/tegra-binaries/tegra-storage-layout_%.bbappend)
  shrinks the `reserved` partition in front of `APP` by the same amount, so
  `APP`, `APP_b`, `/data` and `/rec` keep their offsets.
- The tegraflash bundle carries the rescue boot image as `sloth-rescue.img`,
  and a flash writes it. An OTA never rewrites `recovery`.
- To try it without breaking a slot, boot it once through a UEFI entry that
  passes `bootmode=recovery` to L4TLauncher. The entry stays out of
  `BootOrder`; `reboot` in the rescue system returns to the normal boot.

  ```
  efibootmgr -C -d /dev/nvme0n1 -p 11 -L "Sloth rescue (test)" \
      -l '\EFI\BOOT\bootaa64.efi' -u bootmode=recovery
  efibootmgr -n <its Boot number> && reboot
  # dropbear makes a new host key on every rescue boot:
  ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null root@<board-ip>
  ```

Partition sizes are fixed in the GPT at flash time. Changing them means a
USB-recovery reflash, which wipes `/data` and `/rec`.

Both machines pull in
[`include/sloth-orin-nano-common.inc`](conf/machine/include/sloth-orin-nano-common.inc),
which holds everything they share:

- **Ethernet:** the p3768 devkit's RJ45 is a Realtek RTL8168 on PCIe, not the
  Tegra EQOS. meta-tegra only `RRECOMMENDS` the module, so the `.inc` promotes
  it to `MACHINE_ESSENTIAL_EXTRA_RDEPENDS` and autoloads `r8168`. Getting this
  wrong is what the network bring-up investigation (D8) was about.
- **overlayfs:** `OVERLAYFS_MOUNT_POINT[data] = "/data"` — required in the
  machine config by `overlayfs.bbclass`.
- Bluetooth removed from `MACHINE_FEATURES`.
- `panic=10` on the kernel command line: the kernel is built with
  `CONFIG_PANIC_TIMEOUT=0`, and A/B fallback only happens across reboots.
- `TNSPEC_MACHINE = "jetson-orin-nano-devkit-super"`, or no bootloader capsule
  ever applies.

### Images

| Recipe | Purpose |
|---|---|
| [`sloth-image.bb`](recipes-core/images/sloth-image.bb) | Production. Key-based root SSH only. CUDA + TensorRT + trimmed OpenCV, can-utils, v4l-utils, i2c-tools, libgpiod, tegrastats. |
| [`sloth-image-dev.bb`](recipes-core/images/sloth-image-dev.bb) | Bench work. Everything above **plus** passwordless root, serial autologin, `tools-debug`, strace, gdb, python3-opencv. Never ship it. |
| [`sloth-rescue-image.bb`](recipes-core/images/sloth-rescue-image.bb) | The rescue system for the `recovery` partition (`cpio.gz.cboot`: kernel + initramfs, about 47 MiB). busybox, dropbear (keys only, `SLOTH_SSH_AUTHORIZED_KEYS` is mandatory), the PCIe, NVMe and r8168 modules. Console login uses `SLOTH_ROOT_PASSWORD_HASH_FILE`. Built and bundled automatically by the NVMe machine's images. |

**Login policy (architecture D5).** `sloth-image` installs
`${ROOT_HOME}/.ssh/authorized_keys` (`/root/.ssh/authorized_keys`) from `SLOTH_SSH_AUTHORIZED_KEYS`, an absolute
path on the *build host* that you set in `local.conf`:

```
SLOTH_SSH_AUTHORIZED_KEYS = "/work/sloth-authorized_keys"
```

It is **mandatory** for `sloth-image` — unset, `do_rootfs` calls `bbfatal`,
because an image with no password login and no key is a board you cannot reach.
`sloth-image.bb` also writes an sshd drop-in
(`PermitRootLogin prohibit-password`, `PasswordAuthentication no`) rather than
relying on sshd's compiled-in defaults, which oe-core leaves commented out.

Listing any of the four passwordless login features flips `SLOTH_LOGIN_IS_OPEN`,
which downgrades the key requirement to a warning and skips the sshd
hardening — that is how `sloth-image-dev` stays usable on the bench.

### Recipes

| Path | Purpose |
|---|---|
| [`recipes-core/sloth-network/`](recipes-core/sloth-network/) | `20-wired.network` for systemd-networkd: DHCP on `en*`/`eth*`. `can0` comes later. |
| [`recipes-core/base-files/base-files_%.bbappend`](recipes-core/base-files/base-files_%.bbappend) | Adds `x-systemd.growfs` to the `/` entry in fstab so the rootfs grows to fill its slot on first boot. `bbfatal`s if the fstab pattern stops matching. |
| [`recipes-core/sloth-data/`](recipes-core/sloth-data/) | `systemd-repart` creates `/data` (`SLOTH_DATA_SIZE`; 8G by default, 48G on the NVMe machine) and `/rec` (the rest) on first boot. Also the `/etc` overlays on `/data`, and `/data/ota` for OTA staging. |
| [`recipes-core/sloth-bootdisk/`](recipes-core/sloth-bootdisk/) | `/dev/disk/sloth-boot/<partition name>` links for the boot disk. The other disk uses the same partition names, and so does a second disk with a Sloth layout (`sloth-data`, `sloth-rec`). The OTA, the ESP mount, and the `/data` and `/rec` mounts all go through these links. |
| [`recipes-core/sloth-watchdog/`](recipes-core/sloth-watchdog/) | `RuntimeWatchdogSec=30s`: the hardware watchdog resets the board if systemd stops responding. |
| [`recipes-core/sloth-rescue/`](recipes-core/sloth-rescue/) | The rescue system's `/init` (PID 1: modules, DHCP, dropbear, getty on `ttyTCU0` and `tty1`), its udhcpc hook and login banner. |
| [`recipes-bsp/tegra-binaries/tegra-storage-layout_%.bbappend`](recipes-bsp/tegra-binaries/tegra-storage-layout_%.bbappend) | NVMe layout: bigger `recovery`/`recovery_alt`, smaller `reserved`, and file names for `recovery` (`sloth-rescue.img`) and `recovery-dtb` (the kernel DTB). Only with `SLOTH_RESCUE_IMAGE` set. |
| [`recipes-bsp/sloth-boot-order/`](recipes-bsp/sloth-boot-order/) | UEFI `DefaultBootPriority = "nvme,sd"` overlay for the NVMe machine, and the `edk2-firmware-tegra` bbappend that deploys it. |
| [`recipes-devtools/cuda/sloth-cuda-env_1.0.bb`](recipes-devtools/cuda/) | CUDA environment setup on the device. |
| `recipes-core/images/tegra-*-initramfs.bbappend` | Initramfs adjustments for this machine. |

The rootfs is built small, sized from its content plus
`IMAGE_ROOTFS_EXTRA_SPACE = "524288"`, and grown at boot. That keeps `dd` fast
and swupdate payloads small. It stops at the partition edge, and the rest of
the disk is `/data` and `/rec`. `IMAGE_ROOTFS_MAXSIZE` is the slot size, so a
rootfs that no longer fits its slot stops the build instead of failing an OTA
halfway through.

## Building

All commands run inside the build container from
[`yocto-jetson-buildenv`](https://github.com/RenMurakami/yocto-jetson-buildenv)
(`docker/yocto-shell.sh`), with `/work` holding `meta-sloth` and, for the
local-clone variant, `tegra-demo-distro`.

### Root SSH key — required for `sloth-image`

`sloth-image` has no password login. It installs the public key file named by
`SLOTH_SSH_AUTHORIZED_KEYS` as root's `authorized_keys`, and stops the build if
the variable is unset or the file does not exist. The path is read **on the
build host**, so inside the container it must be under `/work`:

```bash
cp ~/.ssh/id_ed25519.pub ~/yocto/sloth-authorized_keys   # on the WSL host, once
```

No key file is shipped in this repository; every developer brings their own.
`sloth-image-dev` builds without one (it allows passwordless root).

### With kas (preferred)

| Config | Layers come from |
|---|---|
| [`kas/sloth-orin-nano.yml`](kas/sloth-orin-nano.yml) | the existing `/work/tegra-demo-distro` checkout — no network |
| [`kas/sloth-orin-nano-fetch.yml`](kas/sloth-orin-nano-fetch.yml) | cloned by kas at pinned commits — for a clean machine |

```bash
cd /work
export SLOTH_SSH_AUTHORIZED_KEYS=/work/sloth-authorized_keys

kas build meta-sloth/kas/sloth-orin-nano.yml                         # sloth-image
kas build meta-sloth/kas/sloth-orin-nano.yml --target sloth-image-dev # bench image

# clean machine: kas clones everything into its own work dir
KAS_WORK_DIR=/work/kas-fetch kas build meta-sloth/kas/sloth-orin-nano-fetch.yml

# NVMe product machine: add the overlay after either file
kas build meta-sloth/kas/sloth-orin-nano.yml:meta-sloth/kas/machine-nvme.yml
```

`machine:` defaults to `sloth-orin-nano-sd`. The overlay
[`kas/machine-nvme.yml`](kas/machine-nvme.yml) switches it to
`sloth-orin-nano-nvme`. Both can share one build directory.

Shared settings live in
[`kas/include/sloth-common.yml`](kas/include/sloth-common.yml). kas does not run
tegra-demo-distro's `setup-env`, so the cache locations (`DL_DIR`,
`SSTATE_DIR`, `BB_HASHSERVE_DB_DIR`) and the thread limits are set there as
`env:` defaults; export any of them to override.

### Without kas

```bash
cd /work/tegra-demo-distro
. ./setup-env --machine sloth-orin-nano-sd build-sd
bitbake-layers add-layer /work/meta-sloth   # once per build dir
bitbake sloth-image          # production
bitbake sloth-image-dev      # bench
```

`local.conf` must set `DISTRO = "sloth"`, `MACHINE = "sloth-orin-nano-sd"` and
`SLOTH_SSH_AUTHORIZED_KEYS`.

### Getting the image onto the board

- **NVMe machine:** USB recovery mode. Put the board in recovery (FC REC to
  GND), connect it to the host over USB-C, extract the
  `.tegraflash-tar.zst` bundle and run `sudo ./initrd-flash` from that
  directory. Run it on the host, not inside the build container, which has no
  USB access. This writes the QSPI boot firmware and the whole NVMe. From
  WSL the board has to be passed through with usbipd-win
  (`usbipd attach --wsl --busid <id> --auto-attach`); that path is untested,
  and a native x86-64 Linux host is the fallback.
- **SD machine:** the SD card is written with `dd` from the Jetson itself
  while it runs JetPack from the NVMe (the SD slot is `mmcblk0` there).

### Build pipeline

[`scripts/sloth-pipeline`](scripts/sloth-pipeline) is phase A of the
validation pipeline. Each build gets an ID, a board-free check (Gate 1) and
its own folder in an archive, so any build can be found and installed again.
The gates on the board (boot check, tests, reboot) are phase B.

Run it on the build host, not inside the container. The container does not see
the archive.

```bash
export SLOTH_ARCHIVE=/mnt/r/sloth/builds    # once, e.g. in ~/.bashrc
~/yocto/meta-sloth/scripts/sloth-pipeline build                           # sloth-image and sloth-image-dev
~/yocto/meta-sloth/scripts/sloth-pipeline build --image prod --tegraflash # also keep the flash bundle
~/yocto/meta-sloth/scripts/sloth-pipeline list
~/yocto/meta-sloth/scripts/sloth-pipeline check <ID>                      # Gate 1 again on an archived build
```

- **Build ID:** `YYYYMMDD-HHMM-<meta-sloth commit>`, plus `-dirty` when
  meta-sloth or tegra-demo-distro has uncommitted changes. The ID goes into
  the image as `SLOTH_BUILD_ID` in `/etc/buildinfo`, together with the commit
  of every layer. Run `cat /etc/buildinfo` on the board to see which build it
  runs. A dirty build is allowed, and its uncommitted changes are saved next to
  it.
- **Secrets:** the pipeline needs both files from
  [Secrets for a build](#secrets-for-a-build). By default it takes them from
  `~/yocto`.
- **Archive:** each build gets `<ID>/` with these files:
  - `build.json` (commits, status, Gate 1 results)
  - `build.log`
  - `gate1-*.txt`
  - the `.swu`, manifest, SPDX and testdata of each image
  - `SHA256SUMS`

  A build with both images takes about 3.3 GB and about 6 minutes when only
  meta-sloth changed. The tegraflash bundle (2.5 GB per image) is only kept
  with `--tegraflash`. On WSL, keep the archive on a
  Windows drive: the WSL disk does not shrink when old builds are deleted.
  Every `.swu` holds root's password hash, so keep the archive private.

Gate 1 reads the `.swu` itself, the same file the board will install:

| Check | Fails when |
|---|---|
| `.swu` contents | the rootfs, capsule, ESP (L4TLauncher) or Lua script is missing |
| sw-description | it is for another machine, or the slots are not `/dev/disk/sloth-boot` (F4), or a sha256 does not match |
| build ID | `/etc/buildinfo` is missing, or it comes from another build |
| root password | it is empty (only a warning when locked) |
| root `authorized_keys` | it is missing (`sloth-image`), its mode is not 600, or it differs from the key file |
| sshd policy | the hardening drop-in is missing (`sloth-image`) |
| F2, F3 | `TNSPEC_MACHINE` is not `jetson-orin-nano-devkit-super`, or `TEGRA_MINIMAL_BOOT` is not `0` |
| F4, F5 | `61-sloth-bootdisk.rules` or `rtl8822_setting.bin` is missing |
| hwrevision | it does not name the machine, so the next OTA would be refused |
| no bench tools | `gdb` or `strace` is in `sloth-image` |
| `.swu` size | only a warning: it changed by more than 15% since the last build |

## Documentation

This repository is public, so the working documentation lives in `docs/` on
each developer's machine and is not committed (see `.gitignore`): the
one-page image reference, the network bring-up notes (D8, referred to from
recipe comments) and the on-target verification reports. They record site
details - host names, addresses, SSIDs - that differ per developer and do not
belong in a public tree. Share them directly, not through git.

The on-device tools are shared and live in [`scripts/`](scripts/):

| Script | What it does |
|---|---|
| `sloth-target-check.sh` | PASS/FAIL check of a running board: boot disk and slot, rootfs grown, `/data` and `/rec`, panic and watchdog reboot, UEFI boot order, network, SSH policy, CUDA, TensorRT, camera path. Copy it to the board first, then run it; usage is in the script header. |
| `sloth-net-debug.sh` | Network report generator from the D8 bring-up; writes `/tmp/sloth-net-debug.txt` on the board. |

Their output files are gitignored: they record site details.

## Status

`DISTRO_VERSION = "0.1"`. The board boots from microSD, brings up DHCP on the
RTL8168 NIC and on Wi-Fi (RTL8822CE), joins a Tailscale tailnet, accepts
key-based root SSH (plus password SSH from the LAN and the tailnet on
`sloth-image`), keeps `/data` and `/rec` across updates, and updates itself A/B
over the air with SWUpdate. Verified on hardware on 2026-11-11 with four OTA
updates in both directions. (The verification reports are kept out of this
public repository; see Documentation above.)

### Secrets for a build

Nothing secret lives in this layer. Two files on the build host, passed in
through the environment (`kas/include/sloth-common.yml`):

```bash
export SLOTH_SSH_AUTHORIZED_KEYS=/work/sloth-authorized_keys        # public key(s) for root
export SLOTH_ROOT_PASSWORD_HASH_FILE=/work/sloth-root-password.hash # openssl passwd -6 > that file
```

Without the second one, `sloth-image` has no root password (keys only) and
`sloth-image-dev` an empty one.

### OTA update

```bash
# build: rootfs + bootloader capsule + L4TLauncher + Lua postinst in one .swu
kas shell meta-sloth/kas/sloth-orin-nano.yml -c "bitbake sloth-image-dev-swupdate"   # or sloth-image-swupdate
# on the board: install into the other slot, then reboot (UEFI applies the
# capsule and boots the other slot; about two minutes)
scp ~/yocto/build-sloth/tmp/deploy/images/<machine>/sloth-image-dev-<machine>.swu root@<ip>:/data/ota/
ssh root@<ip> 'swupdate-client /data/ota/sloth-image-dev-<machine>.swu && reboot'
ssh root@<ip> 'nvbootctrl dump-slots-info'      # slot changed, "Capsule update status: 1"
# once the new slot has passed validation (or been rolled back):
ssh root@<ip> 'rm /data/ota/*.swu'
```

`/data/ota` holds one `.swu` at a time. The rootfs streams straight into the
inactive slot, so the `.swu` is the only disk space an update needs. Each
update goes into the other slot, and the previous build stays in the slot it
left as the on-board fallback.

L4TLauncher falls back to a plain `APP` partition when slot B is active and
the disk has no `APP_b` (edk2-nvidia `FindPartitionInfo`), so a JetPack disk
should boot from either slot. This has been read in the source but not tested
on the board.

### Wi-Fi

Credentials never enter an image or git. On the board, once:
`sloth-wifi-setup <SSID>` (asks for the passphrase; stores it under
`/etc/wpa_supplicant`, an overlay on `/data`).

### Tailscale

`tailscaled` runs in both images with its state in `/var/lib/tailscale`, an
overlay on `/data`, so the board stays the same tailnet node across OTA
updates. Join once, on the board: `tailscale up --accept-dns=false`, then open
the printed login URL. In the admin console, disable key expiry for the board.

Known open items:

- `can0` is not configured; the CAN device tree overlay
  (`recipes-bsp/sloth-overlays`) does not exist yet. The hooks are commented out
  in `sloth-orin-nano-common.inc`.
- OTA: `.swu` files are not signed, and there is no hawkBit/suricatta server
  yet.
- Automatic rollback is untested on hardware. A panic or a hung systemd now
  ends in a reboot (`panic=10`, `sloth-watchdog`). A boot that stops in
  emergency mode, or waits forever while systemd is still alive, does not; a
  boot-finished check in front of `nvbootctrl verify` is planned for that.
- Rescue system: it boots, and is reachable over SSH, but cannot repair
  anything yet. Planned: SWUpdate to rewrite a slot from a `.swu` (USB stick or
  upload), then resetting the slot status to normal (L4TLauncher clears it on
  the way into recovery). It also has no RTC driver, so its clock reads 1970.
  The automatic path (both slots unbootable) is untested; only the
  `bootmode=recovery` entry has been tried.
- The ESP is a single copy that the OTA rewrites in place. The rescue system
  depends on it too, because L4TLauncher lives there. `esp_alt` exists but is
  not used.
- The root password lives on the rootfs, so an OTA update resets it to the
  hash in `SLOTH_ROOT_PASSWORD_HASH_FILE`.
