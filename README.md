# meta-sloth

Yocto layer for **Sloth** — a thermal-camera deer detection ECU on a
Jetson Orin Nano Super devkit. This repository is the product software stack:
the `sloth` distro, the `sloth-orin-nano-sd` machine, the images, and the
product-specific recipes.

The **build environment is a separate repository**,
[`yocto-jetson-buildenv`](https://github.com/RenMurakami/yocto-jetson-buildenv)
(Docker image, `site.conf`, shared caches). Nothing in this layer assumes a
particular host — it only assumes the layer stack below.

| | |
|---|---|
| Yocto release | 6.0 "wrynose" (`LAYERSERIES_COMPAT_sloth`) |
| BSP | OE4T `meta-tegra` wrynose → Jetson Linux R39.2.1 / JetPack 7.2.1 |
| Target | Jetson Orin Nano Super devkit (p3768), booting from microSD |
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

### Machine — `sloth-orin-nano-sd`

[`conf/machine/sloth-orin-nano-sd.conf`](conf/machine/sloth-orin-nano-sd.conf)
extends `jetson-orin-nano-devkit` and pulls in
[`include/sloth-orin-nano-common.inc`](conf/machine/include/sloth-orin-nano-common.inc),
which holds everything shared by future variants:

- **Ethernet:** the p3768 devkit's RJ45 is a Realtek RTL8168 on PCIe, not the
  Tegra EQOS. meta-tegra only `RRECOMMENDS` the module, so the `.inc` promotes
  it to `MACHINE_ESSENTIAL_EXTRA_RDEPENDS` and autoloads `r8168`. Getting this
  wrong is what the `docs/net-debug/` investigation was about.
- **overlayfs:** `OVERLAYFS_MOUNT_POINT[data] = "/data"` — required in the
  machine config by `overlayfs.bbclass`.
- Bluetooth removed from `MACHINE_FEATURES`.

### Images

| Recipe | Purpose |
|---|---|
| [`sloth-image.bb`](recipes-core/images/sloth-image.bb) | Production. Key-based root SSH only. CUDA + TensorRT + trimmed OpenCV, can-utils, v4l-utils, i2c-tools, libgpiod, tegrastats. |
| [`sloth-image-dev.bb`](recipes-core/images/sloth-image-dev.bb) | Bench work. Everything above **plus** passwordless root, serial autologin, `tools-debug`, strace, gdb, python3-opencv. Never ship it. |

**Login policy (architecture D5).** `sloth-image` installs
`/home/root/.ssh/authorized_keys` from `SLOTH_SSH_AUTHORIZED_KEYS`, an absolute
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
| [`recipes-core/base-files/base-files_%.bbappend`](recipes-core/base-files/base-files_%.bbappend) | Adds `x-systemd.growfs` to the `/` entry in fstab so the rootfs grows to its 14 GiB partition on first boot. `bbfatal`s if the fstab pattern stops matching. |
| [`recipes-devtools/cuda/sloth-cuda-env_1.0.bb`](recipes-devtools/cuda/) | CUDA environment setup on the device. |
| `recipes-core/images/tegra-*-initramfs.bbappend` | Initramfs adjustments for this machine. |

The rootfs is built small (`IMAGE_ROOTFS_EXTRA_SPACE = "524288"`) and grown at
boot, which keeps `dd` fast and swupdate payloads small, and makes a rootfs
written to `APP_b` by an OTA grow itself the same way. It stops at the partition
edge — the card's remaining space needs `systemd-repart`, which is D8 `/data`
work.

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
```

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

The SD card is written with `dd` from the Jetson itself while it runs JetPack
from NVMe (the SD slot is `mmcblk0` there), so WSL is fine for building and
`dosdcard.sh`. Only USB recovery-mode flashing (`initrd-flash`) needs a
native x86-64 Linux host.

## Documentation

[`docs/net-debug/`](docs/net-debug/) records the network bring-up
investigation, which is the most load-bearing history in this layer:

| File | Contents |
|---|---|
| `NETWORK-DEBUG-2026-09-09.md` | The investigation: why the board had no network. |
| `meta-sloth-net-APPLY.md` | The fix, as applied to this layer. |
| `D8-VERIFICATION-2026-09-13.md` | What the rebuild measured, and which gaps are still open. |
| `sloth-net-debug.sh` | On-device report generator; writes `/tmp/sloth-net-debug.txt`. |

## Status

`DISTRO_VERSION = "0.1"`. The board boots from microSD, brings up DHCP on the
RTL8168 NIC, and accepts key-based root SSH.

Known open items, all tracked in `D8-VERIFICATION-2026-09-13.md`:

- `/data` is not yet a real partition — `OVERLAYFS_QA_SKIP[data]` papers over it
  until `systemd-repart` lands (`TODO(M2)` in `sloth-image.bb`).
- `can0` is not configured; the CAN device tree overlay
  (`recipes-bsp/sloth-overlays`) does not exist yet. The hooks are commented out
  in `sloth-orin-nano-common.inc`.
- `layer.conf` declares `BBFILES_DYNAMIC` for `meta-swupdate`, but
  `dynamic-layers/` is not created yet. Harmless until swupdate work starts.
