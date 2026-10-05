SUMMARY = "Deer ECU network configuration (wired DHCP; can0 comes later)"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://20-wired.network"
S = "${UNPACKDIR}"

inherit systemd allarch

do_install() {
    install -d ${D}${systemd_unitdir}/network
    install -m 0644 ${S}/20-wired.network ${D}${systemd_unitdir}/network/20-wired.network
}

FILES:${PN} += "${systemd_unitdir}/network"
RDEPENDS:${PN} = "systemd"
