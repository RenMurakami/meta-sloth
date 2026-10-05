# Settings shared by every deer-orin-nano machine variant.
MACHINE_FEATURES:remove = "bluetooth"

# Camera is USB (Tiny1C-USB); only CAN needs a device tree overlay.
# Enable once meta-deer/recipes-bsp/deer-overlays exists:
# PREFERRED_PROVIDER_virtual/dtbo = "deer-overlays"
# UBOOT_EXTLINUX_FDTOVERLAYS = "deer-can.dtbo"

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
