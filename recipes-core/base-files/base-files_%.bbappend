# Grow the rootfs to fill its partition on first boot.
#
# The image is sized to its content plus IMAGE_ROOTFS_EXTRA_SPACE (see
# sloth-image.bb).  The APP partition is ROOTFSPART_SIZE (14 GiB on the SD
# machine, 48 GiB on the NVMe one).  Before this, most of the partition was
# unreachable: D8-VERIFICATION-2026-09-13.md gap 1 measured 2.0 G available
# on a 14 GiB partition.
#
# Growing at boot rather than baking a full-slot rootfs keeps the .img small
# (fast dd, small swupdate payloads) and makes a fresh rootfs written to APP_b
# by an OTA grow itself the same way.  It stops at the partition edge: the
# rest of the disk becomes /data and /rec, which systemd-repart creates on
# first boot (recipes-core/sloth-data).
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
