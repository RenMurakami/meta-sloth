SUMMARY = "Init for the Sloth rescue system (NVMe recovery partition)"
DESCRIPTION = "PID 1 script, udhcpc hook and login banner for sloth-rescue-image."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://init \
    file://udhcpc.script \
    file://issue \
"
S = "${UNPACKDIR}"

COMPATIBLE_MACHINE = "(tegra)"

do_install() {
    install -m 0755 ${S}/init ${D}/init
    install -d ${D}${sysconfdir}/sloth-rescue
    install -m 0755 ${S}/udhcpc.script ${D}${sysconfdir}/sloth-rescue/udhcpc.script
    install -m 0644 ${S}/issue ${D}${sysconfdir}/sloth-rescue/issue
    install -m 0555 -d ${D}/proc ${D}/sys
    install -m 0755 -d ${D}/dev ${D}/run ${D}/mnt
    install -m 1777 -d ${D}/tmp
}

FILES:${PN} = "/init ${sysconfdir}/sloth-rescue /proc /sys /dev /run /mnt /tmp"
RDEPENDS:${PN} = "busybox dropbear kmod"
