SUMMARY = "Sloth rescue system: kernel + initramfs for the NVMe recovery partition"
DESCRIPTION = "L4TLauncher boots this Android-style boot image (kernel + \
initramfs) from the boot disk's 'recovery' partition when both rootfs slots \
are unbootable.  It runs from RAM and brings up wired Ethernet and SSH."
LICENSE = "MIT"

# Kept small on purpose: the kernel alone is about 42 MiB, and the whole boot
# image has to fit TEGRA_RECOVERY_KERNEL_PART_SIZE.
#   phy-tegra194-p2u, pcie-tegra194  PCIe; the NVMe and the r8168 sit behind it
#   nvme                             the boot disk
#   nv-kernel-module-r8168           wired Ethernet (out-of-tree driver)
PACKAGE_INSTALL = " \
    sloth-rescue-init \
    base-files \
    base-passwd \
    busybox \
    dropbear \
    kmod \
    kernel-module-phy-tegra194-p2u \
    kernel-module-pcie-tegra194 \
    kernel-module-nvme \
    nv-kernel-module-r8168 \
    ${ROOTFS_BOOTSTRAP_INSTALL} \
"

IMAGE_FEATURES = ""
IMAGE_LINGUAS = ""
COPY_LIC_MANIFEST = "0"
COPY_LIC_DIRS = "0"
COMPATIBLE_MACHINE = "(tegra)"
IMAGE_NAME_SUFFIX = ""

# cpio.gz.cboot (image_types_cboot.bbclass in meta-tegra) wraps the kernel and
# this initramfs into an Android boot image, the format L4TLauncher reads from
# the recovery partition.  Its command line is KERNEL_ARGS.
IMAGE_FSTYPES = "cpio.gz.cboot"
# kas adds tar.gz to every image (sloth-common.yml); not needed here.
IMAGE_FSTYPES:remove = "tar.gz"

inherit core-image

# The normal system turns the EFI framebuffer off because nvidia-drm drives
# the display.  The rescue system has no display driver, so it keeps the
# UEFI framebuffer: that gives a login prompt on HDMI (tty1) as well as on
# the serial console.
KERNEL_ARGS:remove = "video=efifb:off"

# /data is never mounted here.  The overlayfs QA check fires on every image
# once the machine config sets OVERLAYFS_MOUNT_POINT[data].
OVERLAYFS_QA_SKIP[data] = "mount-configured"

# Logins.  SSH is keys only (dropbear -s in /init), so the key file is
# mandatory.  The root password (same hash file as sloth-image) is for the
# console prompts; without it the console login stays locked.
SLOTH_SSH_AUTHORIZED_KEYS ?= ""
SLOTH_ROOT_PASSWORD_HASH_FILE ?= ""

sloth_rescue_logins() {
    if [ -z "${SLOTH_SSH_AUTHORIZED_KEYS}" ] || [ ! -f "${SLOTH_SSH_AUTHORIZED_KEYS}" ]; then
        bbfatal "SLOTH_SSH_AUTHORIZED_KEYS = '${SLOTH_SSH_AUTHORIZED_KEYS}' is not a readable file. The rescue system takes SSH keys only, so without it nobody could log in over the network."
    fi
    install -d -m 0700 ${IMAGE_ROOTFS}${ROOT_HOME}/.ssh
    install -m 0600 ${SLOTH_SSH_AUTHORIZED_KEYS} ${IMAGE_ROOTFS}${ROOT_HOME}/.ssh/authorized_keys
    chown -R 0:0 ${IMAGE_ROOTFS}${ROOT_HOME}/.ssh

    if [ -n "${SLOTH_ROOT_PASSWORD_HASH_FILE}" ]; then
        pwhash=$(cat "${SLOTH_ROOT_PASSWORD_HASH_FILE}")
        case "$pwhash" in
            '$'*) ;;
            *) bbfatal "SLOTH_ROOT_PASSWORD_HASH_FILE must hold a single crypt(3) hash, as printed by 'openssl passwd -6'" ;;
        esac
        if [ -f ${IMAGE_ROOTFS}${sysconfdir}/shadow ]; then
            pwfile=${IMAGE_ROOTFS}${sysconfdir}/shadow
        else
            pwfile=${IMAGE_ROOTFS}${sysconfdir}/passwd
        fi
        awk -F: -v OFS=: -v h="$pwhash" '$1 == "root" { $2 = h } { print }' "$pwfile" > "$pwfile.new"
        cat "$pwfile.new" > "$pwfile"
        rm "$pwfile.new"
    fi

    echo sloth-rescue > ${IMAGE_ROOTFS}${sysconfdir}/hostname
    install -m 0644 ${IMAGE_ROOTFS}${sysconfdir}/sloth-rescue/issue ${IMAGE_ROOTFS}${sysconfdir}/issue
}
ROOTFS_POSTPROCESS_COMMAND += "sloth_rescue_logins"
do_rootfs[file-checksums] += "${@'%s:True' % d.getVar('SLOTH_SSH_AUTHORIZED_KEYS') if d.getVar('SLOTH_SSH_AUTHORIZED_KEYS') else ''}"
do_rootfs[file-checksums] += "${@'%s:True' % d.getVar('SLOTH_ROOT_PASSWORD_HASH_FILE') if d.getVar('SLOTH_ROOT_PASSWORD_HASH_FILE') else ''}"
do_rootfs[vardeps] += "SLOTH_SSH_AUTHORIZED_KEYS SLOTH_ROOT_PASSWORD_HASH_FILE"
