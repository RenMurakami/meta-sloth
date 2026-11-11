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
"

# The rootfs grows to the 14 GiB partition on first boot (x-systemd.growfs, see
# recipes-core/base-files/base-files_%.bbappend), so the built image only needs
# enough slack to boot once.  Padding it here would only make the .img bigger
# and every dd and swupdate payload slower.
IMAGE_ROOTFS_EXTRA_SPACE = "524288"

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
