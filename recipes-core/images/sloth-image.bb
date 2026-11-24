SUMMARY = "Sloth detection ECU production image"
LICENSE = "MIT"

require recipes-core/images/core-image-minimal.bb

# ---------------------------------------------------------------------------
# Login policy (architecture D5)
# ---------------------------------------------------------------------------
# Production is key-based root SSH only: no empty passwords, no root autologin
# on the serial console.  D8-VERIFICATION-2026-09-13.md gap 3 measured what the
# bring-up settings actually meant - `ssh -o BatchMode=yes root@<board-ip>`
# succeeded from anywhere on the LAN with no credential of any kind.
#
# Bench work uses sloth-image-dev.bb, which puts those features back.
IMAGE_FEATURES += "ssh-server-openssh"

# Absolute path on the build host to the public key(s) allowed to log in as
# root, e.g. SLOTH_SSH_AUTHORIZED_KEYS = "/home/you/.ssh/id_ed25519.pub" in
# local.conf.  Mandatory for this image: without it there is no way in at all.
SLOTH_SSH_AUTHORIZED_KEYS ?= ""

# 1 when the image still has a passwordless way in - i.e. it is a dev image.
SLOTH_LOGIN_IS_OPEN = "${@bb.utils.contains_any('IMAGE_FEATURES', \
    ['allow-empty-password', 'empty-root-password', 'allow-root-login', 'serial-autologin-root'], \
    '1', '0', d)}"

sloth_install_authorized_keys() {
    if [ -z "${SLOTH_SSH_AUTHORIZED_KEYS}" ]; then
        if [ "${SLOTH_LOGIN_IS_OPEN}" = "1" ]; then
            bbwarn "SLOTH_SSH_AUTHORIZED_KEYS is unset: no root authorized_keys installed. Acceptable here only because this image still allows passwordless login."
            return 0
        fi
        bbfatal "SLOTH_SSH_AUTHORIZED_KEYS is unset. ${PN} has no password login and no key, so the flashed board would be unreachable. Set it to a .pub file in local.conf (or export it before running kas), or build sloth-image-dev for bench work."
    fi
    if [ ! -f "${SLOTH_SSH_AUTHORIZED_KEYS}" ]; then
        bbfatal "SLOTH_SSH_AUTHORIZED_KEYS = '${SLOTH_SSH_AUTHORIZED_KEYS}' is not a file on the build host. It must be an absolute path the build can read - inside the yocto-jetson-buildenv container that means somewhere under /work."
    fi
    # ROOT_HOME, not a literal /home/root: oe-core now defaults root's home to
    # /root, and sshd only reads the authorized_keys of the home in /etc/passwd.
    # (The first on-target test of sloth-image, 2026-11-11, found the key
    # installed under /home/root and ignored.)
    install -d -m 0700 ${IMAGE_ROOTFS}${ROOT_HOME}/.ssh
    install -m 0600 ${SLOTH_SSH_AUTHORIZED_KEYS} ${IMAGE_ROOTFS}${ROOT_HOME}/.ssh/authorized_keys
    chown -R 0:0 ${IMAGE_ROOTFS}${ROOT_HOME}/.ssh
}

# oe-core leaves PermitRootLogin/PermitEmptyPasswords commented out, so sshd
# falls back to its compiled-in defaults.  State the policy instead of
# inheriting it.  sshd_config Includes sshd_config.d/*.conf on its line 13,
# ahead of every commented default, and first value wins in sshd_config.
sloth_harden_sshd() {
    if [ "${SLOTH_LOGIN_IS_OPEN}" = "1" ]; then
        return 0
    fi
    install -d ${IMAGE_ROOTFS}${sysconfdir}/ssh/sshd_config.d
    cat > ${IMAGE_ROOTFS}${sysconfdir}/ssh/sshd_config.d/10-sloth-hardening.conf <<'SSHD_EOF'
# Sloth ECU production policy.  Keys only; see meta-sloth/recipes-core/images/sloth-image.bb
PermitRootLogin prohibit-password
PermitEmptyPasswords no
PasswordAuthentication no
KbdInteractiveAuthentication no
SSHD_EOF
    chmod 0644 ${IMAGE_ROOTFS}${sysconfdir}/ssh/sshd_config.d/10-sloth-hardening.conf

    # W3: password login (root included) from SLOTH_SSH_PASSWORD_NETWORK only
    # (the LAN and the tailnet).  A Match block in an Include'd file ends with
    # that file (OpenSSH servconf.c resets the Match state per included file),
    # and it overrides the global "keys only" lines above for matching clients.
    cat > ${IMAGE_ROOTFS}${sysconfdir}/ssh/sshd_config.d/20-sloth-lan-password.conf <<SSHD_EOF
# Sloth ECU: password login from the local network and the tailnet (W3).
# Everywhere else the keys-only policy of 10-sloth-hardening.conf applies.
Match Address ${SLOTH_SSH_PASSWORD_NETWORK}
    PasswordAuthentication yes
    PermitRootLogin yes
SSHD_EOF
    chmod 0644 ${IMAGE_ROOTFS}${sysconfdir}/ssh/sshd_config.d/20-sloth-lan-password.conf
}

# ---------------------------------------------------------------------------
# Password login (decision W3)
# ---------------------------------------------------------------------------
# Networks allowed to log in with a password on sloth-image (sshd "Match
# Address" syntax, comma-separated).  Keys keep working from anywhere.
#   192.168.1.0/24  the local network
#   100.64.0.0/10   Tailscale: every tailnet address is in this range, so
#                   devices on your tailnet can log in from any network
SLOTH_SSH_PASSWORD_NETWORK ?= "192.168.1.0/24,100.64.0.0/10"

# Root password, for BOTH images.  It never lives in this layer: the
# repository is public.  SLOTH_ROOT_PASSWORD_HASH_FILE is an absolute path on
# the build host to a file holding one crypt(3) hash, made with
#   openssl passwd -6 > /work/sloth-root-password.hash      (asks for the password)
# Like SLOTH_SSH_AUTHORIZED_KEYS, kas passes it through from the environment
# (kas/include/sloth-common.yml).  Unset, no password is set: sloth-image then
# has a locked root password (keys only) and sloth-image-dev an empty one.
# sloth-image-dev takes the password too: with Tailscale the board is
# reachable from outside the LAN, so an empty root password is not acceptable
# there.  (The dev image still allows root login, serial autologin and keys.)
# /etc/shadow is on the rootfs, so an OTA update resets the password to this
# file's value - a password changed on the device does not survive an update.
SLOTH_ROOT_PASSWORD_HASH_FILE ?= ""

def sloth_root_password_params(d):
    path = d.getVar('SLOTH_ROOT_PASSWORD_HASH_FILE')
    if not path:
        return ''
    try:
        with open(path) as f:
            pwhash = f.read().strip()
    except OSError as e:
        bb.fatal("SLOTH_ROOT_PASSWORD_HASH_FILE = '%s' cannot be read (%s). Inside the "
                 "yocto-jetson-buildenv container it must be under /work." % (path, e))
    if not pwhash.startswith('$') or any(c in pwhash for c in " '\"\n"):
        bb.fatal("SLOTH_ROOT_PASSWORD_HASH_FILE must hold a single crypt(3) hash, "
                 "as printed by 'openssl passwd -6'")
    # extrausers expands EXTRA_USERS_PARAMS inside a double-quoted shell
    # string, so every $ of the hash has to reach the shell as \$.
    return "usermod -p '%s' root;" % pwhash.replace('$', '\\$')

inherit extrausers
EXTRA_USERS_PARAMS = "${@sloth_root_password_params(d)}"
do_rootfs[file-checksums] += "${@'%s:True' % d.getVar('SLOTH_ROOT_PASSWORD_HASH_FILE') if d.getVar('SLOTH_ROOT_PASSWORD_HASH_FILE') else ''}"
do_rootfs[vardeps] += "SLOTH_ROOT_PASSWORD_HASH_FILE"

ROOTFS_POSTPROCESS_COMMAND += "sloth_install_authorized_keys sloth_harden_sshd"

# Re-run do_rootfs when the key file itself changes.
do_rootfs[file-checksums] += "${@'%s:True' % d.getVar('SLOTH_SSH_AUTHORIZED_KEYS') if d.getVar('SLOTH_SSH_AUTHORIZED_KEYS') else ''}"
do_rootfs[vardeps] += "SLOTH_SSH_AUTHORIZED_KEYS SLOTH_LOGIN_IS_OPEN SLOTH_SSH_PASSWORD_NETWORK"

IMAGE_INSTALL:append = " \
    kernel-modules \
    nv-kernel-module-r8168 \
    sloth-network \
    sloth-data \
    sloth-wifi \
    sloth-tailscale \
    sloth-bootdisk \
    sloth-watchdog \
    sloth-cuda-env \
    tensorrt-core \
    cuda-cudart \
    libopencv-core413 \
    libopencv-imgproc413 \
    libopencv-imgcodecs413 \
    can-utils \
    iproute2 \
    util-linux \
    v4l-utils \
    i2c-tools \
    libgpiod-tools \
    tegra-tools-tegrastats \
    systemd-analyze \
    e2fsprogs \
    e2fsprogs-resize2fs \
    e2fsprogs-tune2fs \
    pciutils \
    usbutils \
    efibootmgr \
"
# efibootmgr: read and fix the UEFI boot order (NVMe first; sloth-target-check
# reads it), and boot the rescue system once with BootNext (see the README).

# ---------------------------------------------------------------------------
# Rootfs size
# ---------------------------------------------------------------------------
# The rootfs grows to fill its slot (ROOTFSPART_SIZE: 14 GiB on the SD
# machine, 48 GiB on the NVMe one) on first boot (x-systemd.growfs, see
# recipes-core/base-files/base-files_%.bbappend).  An OTA formats the whole
# slot (sw-description diskformat).  So the built image only needs enough slack
# to boot once.  Padding it here would only make the .img bigger and every dd
# and swupdate payload slower.
IMAGE_ROOTFS_EXTRA_SPACE = "524288"

# Size the ext4 image from its content:
#   content x IMAGE_OVERHEAD_FACTOR (1.3) + IMAGE_ROOTFS_EXTRA_SPACE
# core-image-minimal.bb already sets IMAGE_ROOTFS_SIZE ?= "8192".  That only
# beats meta-tegra's IMAGE_ROOTFS_SIZE ?= <whole partition> because
# image_types_tegra is inherited late (inherit_defer).  Setting it here keeps
# the result from depending on that ordering.
IMAGE_ROOTFS_SIZE = "8192"

# Stop the build when the rootfs no longer fits its slot.  Without this, an
# oversized image only shows up on the board, as an OTA that fails halfway
# through writing the inactive slot.  It is checked against the computed image
# size above, so the 1.3 overhead factor makes it trip a little early.  If it
# trips, grow ROOTFSPART_SIZE_DEFAULT in the machine config.  On a board that
# is already deployed, that means a USB-recovery reflash.
IMAGE_ROOTFS_MAXSIZE = "${@int(d.getVar('ROOTFSPART_SIZE')) // 1024}"

# ---------------------------------------------------------------------------
# OTA (SWUpdate, A/B rootfs + bootloader capsule)
# ---------------------------------------------------------------------------
# swupdate          the update agent; swupdate-machine-config (meta-tegrademo)
#                   generates its config for the running slot at every start
# swupdate-client   installs a .swu by hand:  swupdate-client <file>.swu
# tegra-redundant-boot
#                   nvbootctrl, nv_update_verifier (marks a boot good once
#                   multi-user is reached) and, through setup-nv-boot-control,
#                   the /boot/efi mount where the capsule is dropped
# The .swu itself is built by sloth-image-swupdate / sloth-image-dev-swupdate
# (dynamic-layers/meta-swupdate).  The guard keeps the image buildable in a
# layer set without meta-swupdate.
SLOTH_OTA_PACKAGES = "swupdate swupdate-client tegra-redundant-boot"
IMAGE_INSTALL:append = " ${@bb.utils.contains('BBFILE_COLLECTIONS', 'swupdate', d.getVar('SLOTH_OTA_PACKAGES'), '', d)}"

# ---------------------------------------------------------------------------
# Build identity (/etc/buildinfo)
# ---------------------------------------------------------------------------
# image-buildinfo (oe-core) writes /etc/buildinfo: the variables below, plus
# the branch and commit of every layer ("-- modified" when the layer has
# uncommitted changes).  scripts/sloth-pipeline gives each build an ID and
# checks it in the .swu (Gate 1), so "which build is this?" has one answer,
# on the host and on the board.  A build outside the pipeline says "local".
inherit image-buildinfo
# L4T_VERSION is only set in recipes that inherit l4t_version (that class is
# just L4T_VERSION ?= "<version>"); without it /etc/buildinfo shows it empty.
inherit l4t_version
SLOTH_BUILD_ID ??= "local"
IMAGE_BUILDINFO_VARS:append = " SLOTH_BUILD_ID MACHINE IMAGE_BASENAME L4T_VERSION"

# image-buildinfo looks these variables up by name from a list, so bitbake
# does not count them as inputs of its "buildinfo" function (run in do_image).
# Without this line a new build ID does not re-run do_image, and the image
# keeps the previous ID.  The cost: each pipeline build re-runs do_image and
# the image types after it (tar.gz, tegraflash, .swu).
# On the function, not on do_image: image.bbclass appends IMAGE_TYPEDEP:<type>
# to do_image[vardeps] without a separating space, so a name added there
# fuses with one of those, in a different order on each parse ("basehash
# value changed ... metadata is not deterministic", 2026-11-23).
buildinfo[vardeps] += "SLOTH_BUILD_ID"

# ---------------------------------------------------------------------------
# Rescue system in the recovery partition
# ---------------------------------------------------------------------------
# On machines that set SLOTH_RESCUE_IMAGE (sloth-orin-nano-nvme.conf), the
# tegraflash bundle carries the rescue boot image as sloth-rescue.img, and the
# external layout writes it to the "recovery" partition
# (recipes-bsp/tegra-binaries/tegra-storage-layout_%.bbappend).  L4TLauncher
# boots it when both rootfs slots are unbootable.  An OTA never rewrites the
# recovery partition: the rescue system changes only with a full flash.
SLOTH_RESCUE_IMAGE ??= ""
SLOTH_RESCUE_BOOTIMG = "${DEPLOY_DIR_IMAGE}/${SLOTH_RESCUE_IMAGE}-${MACHINE}.cpio.gz.cboot"

tegraflash_custom_pre:append() {
    if [ -n "${SLOTH_RESCUE_IMAGE}" ]; then
        size=$(stat -L -c %s "${SLOTH_RESCUE_BOOTIMG}")
        if [ "$size" -gt "${TEGRA_RECOVERY_KERNEL_PART_SIZE}" ]; then
            bbfatal "${SLOTH_RESCUE_BOOTIMG} is $size bytes, larger than the recovery partition (TEGRA_RECOVERY_KERNEL_PART_SIZE = ${TEGRA_RECOVERY_KERNEL_PART_SIZE}). make-sdcard would write past the partition end."
        fi
        cp -L "${SLOTH_RESCUE_BOOTIMG}" ./sloth-rescue.img
    fi
}
do_image_tegraflash[depends] += "${@'%s:do_image_complete' % d.getVar('SLOTH_RESCUE_IMAGE') if d.getVar('SLOTH_RESCUE_IMAGE') else ''}"
do_image_tegraflash[vardeps] += "SLOTH_RESCUE_IMAGE TEGRA_RECOVERY_KERNEL_PART_SIZE"
