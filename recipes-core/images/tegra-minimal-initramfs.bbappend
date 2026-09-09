# /data lives on the rootfs, never in the boot initramfs.  The overlayfs QA
# check fires on every image once OVERLAYFS_MOUNT_POINT[data] is set in the
# machine config, so tell it this image legitimately has no /data mount.
OVERLAYFS_QA_SKIP[data] = "mount-configured"
