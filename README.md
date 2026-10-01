# meta-deer

Yocto layer for the **Deer Detection ECU** — a thermal-camera deer detector on a
Jetson Orin Nano Super devkit. This repository is the product software stack:
the `deer` distro, the `deer-orin-nano-sd` machine, the images, and the
product-specific recipes.

The **build environment is a separate repository**,
[`yocto-jetson-buildenv`](https://github.com/RenMurakami/yocto-jetson-buildenv)
(Docker image, `site.conf`, shared caches). Nothing in this layer assumes a
particular host — it only assumes the layer stack below.

| | |
|---|---|
| Yocto release | 6.0 "wrynose" (`LAYERSERIES_COMPAT_deer`) |
| BSP | OE4T `meta-tegra` wrynose → Jetson Linux R39.2.1 / JetPack 7.2.1 |
| Target | Jetson Orin Nano Super devkit (p3768), booting from microSD |
| Layer priority | `60` |

## Dependencies

`LAYERDEPENDS_deer` requires these collections:

    core  tegra  openembedded-layer  meta-python  networking-layer

In practice that is an OE4T `tegra-demo-distro` checkout on the `wrynose`
branch, which carries all of them. This layer is added to such a tree from
outside it — it is never copied in. The reference build has it in
`build-sd/conf/bblayers.conf` as a path alongside the demo's own layers.

## What this layer provides

### Distro — `deer`

[`conf/distro/deer.conf`](conf/distro/deer.conf) builds on the demo's
`tegrademo.inc` and then narrows it:

- `DISTRO_FEATURES = "pam overlayfs"` — no `virtualization`; we ship no containers.
  `overlayfs` is not in `DISTRO_FEATURES_DEFAULTS`, so it is stated explicitly.
- `DISTRO_FEATURES_OPTED_OUT` drops x11, wayland, vulkan, bluetooth, 3g, nfc,
  nfs, zeroconf, ptest and debuginfod. **Never opt out of `opengl`** —
  `tensorrt-core` has `REQUIRED_DISTRO_FEATURES = "opengl"`.
- `EXTRA_IMAGE_FEATURES = ""` — the demo's passwordless root is a development
  convenience, not a product setting. `deer-image-dev` puts it back deliberately.
- `USE_REDUNDANT_FLASH_LAYOUT_DEFAULT = "1"` — A/B from the first flash, because
  retrofitting it later means repartitioning.
- `INHERIT:remove = "tegra-support-sanity distro_layer_buildinfo"` is
  **mandatory**: `tegra-support-sanity` rewrites `bblayers.conf` from
  `templates/<DISTRO>/`, and `templates/deer/` does not exist.

### Machine — `deer-orin-nano-sd`

[`conf/machine/deer-orin-nano-sd.conf`](conf/machine/deer-orin-nano-sd.conf)
extends `jetson-orin-nano-devkit` and pulls in
[`include/deer-orin-nano-common.inc`](conf/machine/include/deer-orin-nano-common.inc),
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
| [`deer-image.bb`](recipes-core/images/deer-image.bb) | Production. Key-based root SSH only. CUDA + TensorRT + trimmed OpenCV, can-utils, v4l-utils, i2c-tools, libgpiod, tegrastats. |
| [`deer-image-dev.bb`](recipes-core/images/deer-image-dev.bb) | Bench work. Everything above **plus** passwordless root, serial autologin, `tools-debug`, strace, gdb, python3-opencv. Never ship it. |

**Login policy (architecture D5).** `deer-image` installs
`/home/root/.ssh/authorized_keys` from `DEER_SSH_AUTHORIZED_KEYS`, an absolute
path on the *build host* that you set in `local.conf`:

```
DEER_SSH_AUTHORIZED_KEYS = "/work/deer-authorized_keys"
```

It is **mandatory** for `deer-image` — unset, `do_rootfs` calls `bbfatal`,
because an image with no password login and no key is a board you cannot reach.
`deer-image.bb` also writes an sshd drop-in
(`PermitRootLogin prohibit-password`, `PasswordAuthentication no`) rather than
relying on sshd's compiled-in defaults, which oe-core leaves commented out.

Listing any of the four passwordless login features flips `DEER_LOGIN_IS_OPEN`,
which downgrades the key requirement to a warning and skips the sshd
hardening — that is how `deer-image-dev` stays usable on the bench.

### Recipes

| Path | Purpose |
|---|---|
| [`recipes-core/deer-network/`](recipes-core/deer-network/) | `20-wired.network` for systemd-networkd: DHCP on `en*`/`eth*`. `can0` comes later. |
| [`recipes-core/base-files/base-files_%.bbappend`](recipes-core/base-files/base-files_%.bbappend) | Adds `x-systemd.growfs` to the `/` entry in fstab so the rootfs grows to its 14 GiB partition on first boot. `bbfatal`s if the fstab pattern stops matching. |
| [`recipes-devtools/cuda/deer-cuda-env_1.0.bb`](recipes-devtools/cuda/) | CUDA environment setup on the device. |
| `recipes-core/images/tegra-*-initramfs.bbappend` | Initramfs adjustments for this machine. |

The rootfs is built small (`IMAGE_ROOTFS_EXTRA_SPACE = "524288"`) and grown at
boot, which keeps `dd` fast and swupdate payloads small, and makes a rootfs
written to `APP_b` by an OTA grow itself the same way. It stops at the partition
edge — the card's remaining space needs `systemd-repart`, which is D8 `/data`
work.

## Building

From the build container (see `yocto-jetson-buildenv`):

```bash
cd /work/tegra-demo-distro
. ./setup-env --machine deer-orin-nano-sd build-sd
bitbake deer-image          # production
bitbake deer-image-dev      # bench
```

`local.conf` must set `DISTRO = "deer"`, `MACHINE = "deer-orin-nano-sd"` and
`DEER_SSH_AUTHORIZED_KEYS`. `bblayers.conf` must list this layer.

Flashing happens on a **native x86-64 Linux host** — never from WSL2, a VM, or
Docker Desktop.

## Documentation

[`docs/net-debug/`](docs/net-debug/) records the network bring-up
investigation, which is the most load-bearing history in this layer:

| File | Contents |
|---|---|
| `NETWORK-DEBUG-2026-09-09.md` | The investigation: why the board had no network. |
| `meta-deer-net-APPLY.md` | The fix, as applied to this layer. |
| `D8-VERIFICATION-2026-09-13.md` | What the rebuild measured, and which gaps are still open. |
| `deer-net-debug.sh` | On-device report generator; writes `/tmp/deer-net-debug.txt`. |

## Status

`DISTRO_VERSION = "0.1"`. The board boots from microSD, brings up DHCP on the
RTL8168 NIC, and accepts key-based root SSH.

Known open items, all tracked in `D8-VERIFICATION-2026-09-13.md`:

- `/data` is not yet a real partition — `OVERLAYFS_QA_SKIP[data]` papers over it
  until `systemd-repart` lands (`TODO(M2)` in `deer-image.bb`).
- `can0` is not configured; the CAN device tree overlay
  (`recipes-bsp/deer-overlays`) does not exist yet. The hooks are commented out
  in `deer-orin-nano-common.inc`.
- `layer.conf` declares `BBFILES_DYNAMIC` for `meta-swupdate`, but
  `dynamic-layers/` is not created yet. Harmless until swupdate work starts.
