# SWUpdate package (.swu) for a Sloth image.
#
# OE4T's swupdate-image-tegra-common.inc does the work: it packs the image's
# rootfs tarball, the bootloader capsule (tegra-bl.cap), the ESP archive
# (L4TLauncher, because TEGRA_MINIMAL_BOOT = "0") and the Lua postinst that
# arms the capsule.  The rootfs image is taken from the recipe name:
# sloth-image-swupdate -> sloth-image, sloth-image-dev-swupdate -> sloth-image-dev.
#
# files/sw-description is OE4T's, unchanged: slot_a (running on APP) writes
# APP_b, slot_b writes APP.  swupdate-genconfig picks the slot at boot.
require dynamic-layers/meta-swupdate/recipes-demo/images/swupdate-image-tegra-common.inc

# Where sw-description finds APP and APP_b.  The default, by-partlabel, can
# resolve to the APP partition of JetPack's NVMe - an update from slot B
# would then overwrite JetPack.  See recipes-core/sloth-bootdisk.
ROOTFS_DEVICE_PATH = "/dev/disk/sloth-boot"
