# Settings shared by every sloth-orin-nano machine variant.
MACHINE_FEATURES:remove = "bluetooth"

# Camera is USB (Tiny1C-USB); only CAN needs a device tree overlay.
# Enable once meta-sloth/recipes-bsp/sloth-overlays exists:
# PREFERRED_PROVIDER_virtual/dtbo = "sloth-overlays"
# UBOOT_EXTLINUX_FDTOVERLAYS = "sloth-can.dtbo"

# overlayfs.bbclass requires the mount point to be declared in the MACHINE
# config (the demo declares it in tegrademo-extended.conf).  /data is the
# writable partition; see architecture D8.
OVERLAYFS_MOUNT_POINT[data] = "/data"

# /data lives on the rootfs only.  Every initramfs image legitimately has no
# /data mount, so the overlayfs QA check has nothing to verify there.

# The p3768 devkit's RJ45 is a Realtek RTL8168 on PCIe, not the Tegra EQOS
# (meta-tegra p3768.inc: NVIDIA_ETHERNET = "nv-kernel-module-r8168").
# meta-tegra only RRECOMMENDS it; require it and autoload it.
MACHINE_ESSENTIAL_EXTRA_RDEPENDS += "nv-kernel-module-r8168"
KERNEL_MODULE_AUTOLOAD += "r8168"

# Wi-Fi (W1): the devkit's RTL8822CE with NVIDIA's out-of-tree driver.
# p3768.inc only lists these in MACHINE_EXTRA_RRECOMMENDS, which
# core-image-minimal never installs - the same trap as r8168 above.
# The chip firmware is built into the driver, but NVIDIA's build
# (CONFIG_HEXFILE_POWER_LIMIT) reads the TX power-limit table from
# /lib/firmware/rtl8822_setting.bin at probe time.  Without that file the
# probe ends in "power init fail" and no wlan0 appears.  The file comes from
# tegra-firmware-rtl8822.
MACHINE_ESSENTIAL_EXTRA_RDEPENDS += "nv-kernel-module-rtl8822ce tegra-firmware-rtl8822"

# OTA capsules.  UEFI only applies a capsule payload whose TnSpec matches the
# TegraPlatformCompatSpec variable, comparing the '-'-separated fields one by
# one (edk2-nvidia FwPackageLib).  That variable is write-once and was set by
# the JetPack (SDK Manager) flash that put the QSPI firmware on this board:
#   3767--0005--1--jetson-orin-nano-devkit-super-
# The machine name is part of it, so capsules built under the default
# TNSPEC_MACHINE (= MACHINE = sloth-orin-nano-sd) would never match and every
# bootloader update - and with it every A/B slot switch - would fail.
TNSPEC_MACHINE = "jetson-orin-nano-devkit-super"
