# Mount the ESP of the disk Sloth booted from, found through the
# /dev/disk/sloth-boot links (recipes-core/sloth-bootdisk) instead of
# /dev/disk/by-partlabel/esp, which can resolve to JetPack's NVMe.
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"

RDEPENDS:${PN}-service += "sloth-bootdisk"
