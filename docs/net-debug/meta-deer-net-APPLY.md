# Network fix for `deer-image` — what was added to `meta-deer`

> **Status: APPLIED.** Landed as commit `e164021` and verified on hardware
> 2026-09-13. Kept as the record of *why* each piece exists.

Two separate problems. Both had to be fixed, or the interface appears and
still has no IP.

## Problem 1 — the driver was never guaranteed to be in the image

The devkit's RJ45 is a **Realtek RTL8168 on PCIe**, not the Tegra EQOS/MGBE
(`meta-tegra/conf/machine/include/p3768.inc`:
`NVIDIA_ETHERNET = "nv-kernel-module-r8168"`, and the module is packaged by
`recipes-kernel/nvidia-kernel-oot/nvidia-kernel-oot.inc` L370
`${KERNEL_MODULE_PACKAGE_PREFIX}kernel-module-r8168`).

So `ethernet@2310000/status = disabled` and `ethernet@6800000/status = disabled`
are **correct** for this board — chasing `nvethernet` was the wrong track.

meta-tegra only puts r8168 in `MACHINE_EXTRA_RRECOMMENDS`
(via `DEVKIT_ETHERNET_RRECOMMENDS`), i.e. a *recommendation*. Made explicit
so it can never be dropped.

## Problem 2 — nothing brings the link up

`core-image-minimal` + systemd ships **no** `/etc/systemd/network/*.network`,
so even with the driver loaded there is no DHCP client and no IP. That is why
`ip -4 addr` would still show only `lo`.

---

## What was applied

`conf/machine/include/deer-orin-nano-common.inc`:

```bitbake
# The p3768 devkit's RJ45 is a Realtek RTL8168 on PCIe, not the Tegra EQOS
# (meta-tegra p3768.inc: NVIDIA_ETHERNET = "nv-kernel-module-r8168").
# meta-tegra only RRECOMMENDS it; require it and autoload it.
MACHINE_ESSENTIAL_EXTRA_RDEPENDS += "nv-kernel-module-r8168"
KERNEL_MODULE_AUTOLOAD += "r8168"
```

`recipes-core/deer-network/files/20-wired.network`:

```ini
[Match]
Name=en* eth*

[Network]
DHCP=yes

[DHCPv4]
UseDNS=yes
```

`recipes-core/deer-network/deer-network_1.0.bb` installs it into
`${systemd_unitdir}/network`, and `deer-image.bb` pulls in `kernel-modules`,
`nv-kernel-module-r8168` and `deer-network`.

`can0` joins here in M3:

```ini
# 30-can0.network
[Match] Name=can0
[CAN]   BitRate=500K
```

## Verify in the built image (before writing the SD)

```bash
cd /work/tegra-demo-distro/build-sd
M=tmp/deploy/images/deer-orin-nano-sd/deer-image-deer-orin-nano-sd.rootfs.manifest
grep -E "r8168|realtek|rtl8168|deer-network" $M      # driver + config present
grep -c . $M                                          # package count
```

Then rebuild the SD image exactly as in the runbook §7/§10:

```bash
cd /work/flash/sd && rm -f deer-sdcard.img deer-sdcard.img.zst
tar xf /work/tegra-demo-distro/build-sd/tmp/deploy/images/deer-orin-nano-sd/deer-image-deer-orin-nano-sd.rootfs.tegraflash-tar.zst
./dosdcard.sh -s 32G deer-sdcard.img && zstd -T0 -3 deer-sdcard.img -o deer-sdcard.img.zst
```

## If the interface ever stops appearing

Then the driver is not the issue — PCIe enumeration is. Run
`deer-net-debug.sh` on the device and look at section 2 (`lspci`): if no
`10ec:` device is listed, the NIC is not on the bus at all, which points at the
PCIe controller node in the DTB rather than at the Ethernet driver.
