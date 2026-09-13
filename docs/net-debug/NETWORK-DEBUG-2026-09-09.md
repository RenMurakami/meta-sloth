# Debug log — no `eth0` on the first `deer-image` SD boot (2026-09-09)

**Status:** root cause identified and fixed; verified on hardware 2026-09-13 (commit `e164021`).
**Board:** Jetson Orin Nano Super Devkit (DTB model string reads *"NVIDIA Jetson Orin Nano Engineering Reference Developer Kit Super"*).
**Image:** `deer-image` / `deer-orin-nano-sd`, booted from microSD, kernel `6.8.12-l4t-r39.2.1-1021.21`.

## What happened

The image boots, `Deer Detection ECU 0.1 deer-orin-nano-sd tty1` appears, root
logs in — but `ip link show` lists only `lo`, so there is no way to reach the
board from WSL and every check has to be typed on the HDMI console.

## Evidence collected on the device

| Observation | Reading |
|---|---|
| `ip link show` → only `lo` | no ethernet interface at all |
| `modprobe nvethernet; echo $?` → `exit=0`, but `lsmod \| grep nvethernet` → empty | busybox `modprobe` returns 0 without doing anything useful |
| `insmod .../nvethernet.ko` → `ERROR: could not insert module ...: File exists` | the module *is* already in the kernel |
| `dmesg \| grep -i ether` → only `hns3` (Hisilicon) and `igb` (Intel) | no Tegra ethernet driver ever probed |
| `cat /proc/device-tree/bus@0/ethernet@6800000/status` → `disabled` | MGBE0 off |
| `cat /proc/device-tree/bus@0/ethernet@2310000/status` → `disabled` | **EQOS off as well** |
| `cat /proc/device-tree/model` → *…Developer Kit Super* | the DTB matches the board; not a DTB mismatch |

## Root cause

**The devkit's RJ45 is not a Tegra MAC at all.** It is a **Realtek RTL8168 on
PCIe**. meta-tegra states this in the carrier-board include:

```
# meta-tegra/conf/machine/include/p3768.inc
LINUX_ETHERNET  = "kernel-module-r8169"
NVIDIA_ETHERNET = "nv-kernel-module-r8168"
DEVKIT_ETHERNET_RRECOMMENDS ?= "... kernel-module-realtek linux-firmware-rtl8168"
MACHINE_EXTRA_RRECOMMENDS += " ... ${DEVKIT_ETHERNET_RRECOMMENDS}"
```

and the module is packaged as `${KERNEL_MODULE_PACKAGE_PREFIX}kernel-module-r8168`
(`recipes-kernel/nvidia-kernel-oot/nvidia-kernel-oot.inc` L370).

So `ethernet@2310000` (EQOS) and `ethernet@6800000` (MGBE0) being `disabled` is
**correct and expected** on this board — the whole `nvethernet` line of enquiry
was aimed at hardware this carrier does not wire up. `nvethernet.ko` loads
fine and then finds no enabled device, which is exactly why `dmesg` shows no
probe.

## Two things fixed

1. **The driver was only a *recommendation*.** meta-tegra puts r8168 in
   `MACHINE_EXTRA_RRECOMMENDS`, not a hard dependency. Made explicit in
   `meta-deer` and autoloaded, instead of relying on modalias/udev.
2. **Nothing configured the link.** `core-image-minimal` ships no
   `/etc/systemd/network/*.network`, so even once the interface exists there is
   no DHCP client — `ip -4 addr` would still show only `lo`. The `deer-network`
   recipe fixes this and is where `can0` will live later (M3).

Both are written up as copy-paste commands in `meta-deer-net-APPLY.md`
(same directory).

## Confirmed on hardware (2026-09-13)

```
0008:01:00.0 Ethernet controller [0200]: Realtek RTL8111/8168/8211/8411 [10ec:8168] (rev 15)
r8168                 512000  0
eth0             UP             192.168.1.128/24 metric 1024
```

Note a second Realtek device is on the bus — `0001:01:00.0 [10ec:c822]`
RTL8822CE Wi-Fi — with no driver installed. Expected; not wired up yet.

## Lesson for the architecture document

`deer-image.bb` derives from `core-image-minimal`, which deliberately omits
almost everything. Two classes of thing therefore have to be added back by
hand, and both bit us in one session:

- **BSP recommendations** — `MACHINE_EXTRA_RRECOMMENDS` (ethernet driver, wifi,
  firmware). Recommendations can silently not be installed.
- **Basic system configuration** — network config, `/data` mount, anything the
  demo distro provided via `meta-tegrademo`.

Worth adding to architecture §3.5 as a checklist row: *"what does
`demo-image-base` give you that `core-image-minimal` does not?"*

## Fallback if the fix ever regresses

If `eth0`/`enP*` does not appear after a rebuild, the question moves from
*driver* to *PCIe enumeration*. Run `deer-net-debug.sh` and read section 2:

- `lspci` lists a `10ec:8168` device → driver/config problem, keep digging there.
- `lspci` lists nothing → the NIC is not on the bus; look at the PCIe
  controller in the DTB and at `dmesg | grep -i pcie`.

Interim workarounds that avoid needing the network at all:

- **USB tethering** — plug the Jetson's USB-C into the PC; the L4T
  `l4t-usb-device-mode` gadget gives `192.168.55.1`. Not present in this image
  (it is a meta-tegra recipe we did not install), but it can be added.
- **Read reports off the SD** — `deer-net-debug.sh` copies its output to the
  UDA partition (`/dev/mmcblk0p15`), which JetPack can mount, so console
  typing is not needed for the long dumps.
- **Serial console** — a USB-UART on the button header (`ttyTCU0`, 115200 8N1)
  gives scrollable, copy-pasteable output for boot-time failures. Still the
  best investment for this kind of bring-up.
