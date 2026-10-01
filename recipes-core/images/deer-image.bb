SUMMARY = "Deer detection ECU production image"
LICENSE = "MIT"

require recipes-core/images/core-image-minimal.bb

# ---------------------------------------------------------------------------
# Login policy (architecture D5)
# ---------------------------------------------------------------------------
# Production is key-based root SSH only: no empty passwords, no root autologin
# on the serial console.  D8-VERIFICATION-2026-09-13.md gap 3 measured what the
# bring-up settings actually meant - `ssh -o BatchMode=yes root@192.168.1.128`
# succeeded from anywhere on the LAN with no credential of any kind.
#
# Bench work uses deer-image-dev.bb, which puts those features back.
IMAGE_FEATURES += "ssh-server-openssh"

# Absolute path on the build host to the public key(s) allowed to log in as
# root, e.g. DEER_SSH_AUTHORIZED_KEYS = "/home/you/.ssh/id_ed25519.pub" in
# local.conf.  Mandatory for this image: without it there is no way in at all.
DEER_SSH_AUTHORIZED_KEYS ?= ""

# 1 when the image still has a passwordless way in - i.e. it is a dev image.
DEER_LOGIN_IS_OPEN = "${@bb.utils.contains_any('IMAGE_FEATURES', \
    ['allow-empty-password', 'empty-root-password', 'allow-root-login', 'serial-autologin-root'], \
    '1', '0', d)}"

deer_install_authorized_keys() {
    if [ -z "${DEER_SSH_AUTHORIZED_KEYS}" ]; then
        if [ "${DEER_LOGIN_IS_OPEN}" = "1" ]; then
            bbwarn "DEER_SSH_AUTHORIZED_KEYS is unset: no root authorized_keys installed. Acceptable here only because this image still allows passwordless login."
            return 0
        fi
        bbfatal "DEER_SSH_AUTHORIZED_KEYS is unset. ${PN} has no password login and no key, so the flashed board would be unreachable. Set it to a .pub file in local.conf, or build deer-image-dev for bench work."
    fi
    install -d -m 0700 ${IMAGE_ROOTFS}/home/root/.ssh
    install -m 0600 ${DEER_SSH_AUTHORIZED_KEYS} ${IMAGE_ROOTFS}/home/root/.ssh/authorized_keys
    chown -R 0:0 ${IMAGE_ROOTFS}/home/root/.ssh
}

# oe-core leaves PermitRootLogin/PermitEmptyPasswords commented out, so sshd
# falls back to its compiled-in defaults.  State the policy instead of
# inheriting it.  sshd_config Includes sshd_config.d/*.conf on its line 13,
# ahead of every commented default, and first value wins in sshd_config.
deer_harden_sshd() {
    if [ "${DEER_LOGIN_IS_OPEN}" = "1" ]; then
        return 0
    fi
    install -d ${IMAGE_ROOTFS}${sysconfdir}/ssh/sshd_config.d
    cat > ${IMAGE_ROOTFS}${sysconfdir}/ssh/sshd_config.d/10-deer-hardening.conf <<'SSHD_EOF'
# Deer ECU production policy.  Keys only; see meta-deer/recipes-core/images/deer-image.bb
PermitRootLogin prohibit-password
PermitEmptyPasswords no
PasswordAuthentication no
KbdInteractiveAuthentication no
SSHD_EOF
    chmod 0644 ${IMAGE_ROOTFS}${sysconfdir}/ssh/sshd_config.d/10-deer-hardening.conf
}

ROOTFS_POSTPROCESS_COMMAND += "deer_install_authorized_keys deer_harden_sshd"

# Re-run do_rootfs when the key file itself changes.
do_rootfs[file-checksums] += "${@'%s:True' % d.getVar('DEER_SSH_AUTHORIZED_KEYS') if d.getVar('DEER_SSH_AUTHORIZED_KEYS') else ''}"
do_rootfs[vardeps] += "DEER_SSH_AUTHORIZED_KEYS DEER_LOGIN_IS_OPEN"

IMAGE_INSTALL:append = " \
    kernel-modules \
    nv-kernel-module-r8168 \
    deer-network \
    deer-cuda-env \
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

# TODO(M2): replace with a real /data mount (systemd-repart + data.mount)
OVERLAYFS_QA_SKIP[data] = "mount-configured"

# --- /usr/local/cuda -> /usr/local/cuda-<ver> ----------------------------
# meta-tegra installs only the versioned dir; CUDA_HOME-based builds need this.
deer_cuda_symlink() {
    for d in ${IMAGE_ROOTFS}${prefix}/local/cuda-*; do
        [ -d "$d" ] || continue
        ln -sfn "$(basename $d)" ${IMAGE_ROOTFS}${prefix}/local/cuda
        break
    done
}
ROOTFS_POSTPROCESS_COMMAND += "deer_cuda_symlink;"
