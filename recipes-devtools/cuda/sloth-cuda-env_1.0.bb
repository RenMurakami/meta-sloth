SUMMARY = "/usr/local/cuda compatibility symlink and CUDA_HOME for the deer image"
DESCRIPTION = "meta-tegra installs the toolkit only under /usr/local/cuda-${CUDA_VERSION}. \
Anything that defaults to CUDA_HOME=/usr/local/cuda - CMake's FindCUDAToolkit, \
PyTorch, most NVIDIA sample build systems - fails on a stock image. \
See docs/net-debug/D8-VERIFICATION-2026-09-13.md gap 4."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

COMPATIBLE_MACHINE = "(cuda)"

# Content is CUDA_VERSION-dependent, and CUDA_VERSION comes from the machine
# include (meta-tegra tegra-common.inc), so this cannot be allarch.
PACKAGE_ARCH = "${MACHINE_ARCH}"

do_install() {
    install -d ${D}${prefix}/local
    # Relative, so it stays correct under a sysroot or an OTA-mounted rootfs.
    ln -snf cuda-${CUDA_VERSION} ${D}${prefix}/local/cuda

    install -d ${D}${sysconfdir}/profile.d
    cat > ${D}${sysconfdir}/profile.d/deer-cuda.sh <<'PROFILE'
# CUDA toolkit location for interactive shells.  /usr/local/cuda is a symlink
# to the versioned directory, so this survives a toolkit upgrade.
export CUDA_HOME=/usr/local/cuda
export CUDA_PATH=/usr/local/cuda
# The target image carries the runtime, not nvcc; only extend PATH if the
# toolkit's bin/ is actually present.
if [ -d /usr/local/cuda/bin ]; then
    export PATH="$PATH:/usr/local/cuda/bin"
fi
PROFILE
    chmod 0644 ${D}${sysconfdir}/profile.d/deer-cuda.sh
}

FILES:${PN} = "${prefix}/local/cuda ${sysconfdir}/profile.d/deer-cuda.sh"

# The symlink is dangling unless the toolkit it points at is in the image.
RDEPENDS:${PN} = "cuda-cudart"
