SUMMARY = "UEFI boot order overlay for Sloth: NVMe first, then the SD card"
DESCRIPTION = "Bootloader DTB overlay that sets the UEFI DefaultBootPriority \
to nvme,sd.  Listed in TEGRA_BOOTCONTROL_OVERLAYS by the NVMe machine."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

# Why a Sloth overlay and not NVIDIA's BootOrderNvme.dtbo:
#
# - The NVMe has to come first, so that an SD card or USB stick left in the
#   board never takes over the boot.  (The SD card was once planned as a
#   JetPack rescue OS; the rescue system now lives in the NVMe's recovery
#   partition, which L4TLauncher on the NVMe boots by itself.  "sd" second is
#   kept so an SD card still sorts ahead of network boot and the UEFI shell.)
# - BootOrderNvme.dtbo only says "nvme".  Every other class keeps the same
#   lowest priority, so where the SD card lands is left to chance.
# - Without any overlay, UEFI uses its built-in "boot.img,usb,sd,emmc,ufs":
#   the SD card goes before the NVMe.  That is why today's SD-card Sloth boots
#   ahead of the JetPack install on the NVMe.
#
# UEFI applies DefaultBootPriority only once.  It sorts BootOrder the first
# time it finds no PlatformBootOrderSet variable, which means right after the
# UEFI variable store has been cleared (edk2-nvidia PlatformBootOrderLib
# SetBootOrder()).  Boot entries that appear later are appended to the end.
# Check the order on the board after flashing with `efibootmgr`.
# sloth-target-check does this.

COMPATIBLE_MACHINE = "(tegra)"

DEPENDS = "dtc-native"
SRC_URI = "file://SlothBootOrder.dts"
S = "${UNPACKDIR}"

inherit deploy

do_configure[noexec] = "1"
do_install[noexec] = "1"

do_compile() {
    dtc -I dts -O dtb -o ${B}/SlothBootOrder.dtbo ${S}/SlothBootOrder.dts
}

# The tegraflash bundle and the bootloader capsule copy every
# TEGRA_BOOTCONTROL_OVERLAYS entry from DEPLOY_DIR_IMAGE.  The
# edk2-firmware-tegra bbappend in this layer makes its do_deploy wait for this
# one, the same way meta-tegra chains l4t-launcher-rootfs-ab-config.
do_deploy() {
    install -D -m 0644 -t ${DEPLOYDIR} ${B}/SlothBootOrder.dtbo
}

addtask deploy before do_build after do_compile

PACKAGE_ARCH = "${MACHINE_ARCH}"
