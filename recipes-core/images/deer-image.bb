SUMMARY = "Deer detection ECU production image"
LICENSE = "MIT"

require recipes-core/images/core-image-minimal.bb

# debug-tweaks = passwordless root; development only.  Remove for production
# and replace with key-based SSH (architecture D5).
IMAGE_FEATURES += "ssh-server-openssh allow-empty-password empty-root-password allow-root-login serial-autologin-root"

IMAGE_INSTALL:append = " \
    kernel-modules \
    nv-kernel-module-r8168 \
    deer-network \
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
    pciutils \
    usbutils \
"

IMAGE_ROOTFS_EXTRA_SPACE = "2097152"

# TODO(M2): replace with a real /data mount (systemd-repart + data.mount)
OVERLAYFS_QA_SKIP[data] = "mount-configured"
