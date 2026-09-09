# The flashing initramfs has no /data mount, and legitimately never will.
OVERLAYFS_QA_SKIP[data] = "mount-configured"
