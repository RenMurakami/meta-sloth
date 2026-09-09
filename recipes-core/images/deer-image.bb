SUMMARY = "Deer detection ECU production image"
LICENSE = "MIT"

require recipes-core/images/core-image-minimal.bb

IMAGE_FEATURES += "ssh-server-openssh"

IMAGE_INSTALL:append = " \
    tensorrt-core \
    cuda-cudart \
    libopencv-core413 \
    libopencv-imgproc413 \
    libopencv-imgcodecs413 \
    can-utils \
    iproute2 \
    v4l-utils \
    i2c-tools \
    libgpiod-tools \
    tegra-tools-tegrastats \
    systemd-analyze \
"

IMAGE_ROOTFS_EXTRA_SPACE = "2097152"

# TODO(M2): replace with a real /data mount (systemd-repart + data.mount,
# modelled on meta-tegrademo's data-partition recipe) once the SD image boots.
# Until then the overlayfs QA check has nothing to verify.
OVERLAYFS_QA_SKIP[data] = "mount-configured"
