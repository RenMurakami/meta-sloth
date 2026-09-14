# Grow the rootfs to fill its partition on first boot.
#
# The image is sized to content + IMAGE_ROOTFS_EXTRA_SPACE (~4 GiB of ext4)
# but the APP partition is 14 GiB, fixed by flash_t234_qspi_sd_rootfs_ab.xml,
# so ~10 GiB was unreachable - D8-VERIFICATION-2026-09-13.md gap 1 measured
# 2.0 G available on a 14 GiB partition.
#
# Growing at boot rather than baking a 14 GiB rootfs keeps the .img small
# (fast dd, small swupdate payloads) and makes a fresh rootfs written to APP_b
# by an OTA grow itself the same way.  It stops at the partition edge: the
# card's remaining ~200 GB needs systemd-repart, which is D8 /data work.
#
# systemd-fstab-generator turns x-systemd.growfs on "/" into
# systemd-growfs-root.service, and systemd-growfs(8) resizes ext4 through the
# EXT4_IOC_RESIZE_FS ioctl, so this does not depend on resize2fs being
# installed.  resize2fs is installed anyway, for repair by hand on the device.
#
# Patching the stock fstab instead of shipping our own keeps the other entries
# tracking oe-core.
do_install:append() {
    sed -i -e 's|^\(/dev/root[[:space:]]\+/[[:space:]]\+auto[[:space:]]\+defaults\)\([[:space:]]\)|\1,x-systemd.growfs\2|' ${D}${sysconfdir}/fstab
    grep -q '^/dev/root[[:space:]].*x-systemd\.growfs' ${D}${sysconfdir}/fstab || \
        bbfatal "base-files: the / entry in fstab did not match; x-systemd.growfs was not applied"
}
