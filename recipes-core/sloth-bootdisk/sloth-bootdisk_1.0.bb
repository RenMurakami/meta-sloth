SUMMARY = "Stable /dev/disk/sloth-boot/<partition name> links for the boot disk"
DESCRIPTION = "udev rule naming the partitions of the disk the rootfs boots from. \
by-partlabel is ambiguous because JetPack's NVMe uses the same partition names."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://61-sloth-bootdisk.rules.in"
S = "${UNPACKDIR}"
B = "${WORKDIR}/build"

def sloth_bootdisk(d):
    import re
    return re.sub(r'p[0-9]+$', '', d.getVar('TNSPEC_BOOTDEV'))

# mmcblk0p1 -> mmcblk0, nvme0n1p1 -> nvme0n1
SLOTH_BOOTDISK = "${@sloth_bootdisk(d)}"

do_compile() {
    sed -e 's,@BOOTDISK@,${SLOTH_BOOTDISK},g' \
        ${S}/61-sloth-bootdisk.rules.in > ${B}/61-sloth-bootdisk.rules
}

do_install() {
    install -D -m 0644 ${B}/61-sloth-bootdisk.rules \
        ${D}${nonarch_base_libdir}/udev/rules.d/61-sloth-bootdisk.rules
}

FILES:${PN} = "${nonarch_base_libdir}/udev/rules.d"
RDEPENDS:${PN} = "udev"

# TNSPEC_BOOTDEV comes from the machine configuration.
PACKAGE_ARCH = "${MACHINE_ARCH}"
