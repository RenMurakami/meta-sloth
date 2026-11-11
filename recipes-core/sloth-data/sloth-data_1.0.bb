SUMMARY = "Sloth /data and /rec partitions (created by systemd-repart on first boot)"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://50-sloth-data.conf \
    file://60-sloth-rec.conf \
    file://data.mount \
    file://rec.mount \
    file://10-sloth-data-overlays.conf \
"
S = "${UNPACKDIR}"

inherit systemd overlayfs

# OVERLAYFS_MOUNT_POINT comes from the machine config, so this is machine-specific.
PACKAGE_ARCH = "${MACHINE_ARCH}"

# /etc paths kept on /data across OTA updates:
#   /etc/ssh             host keys - the board keeps its SSH identity through an OTA
#   /etc/wpa_supplicant  Wi-Fi credentials written by sloth-wifi-setup (W5)
#   /var/lib/tailscale   tailnet identity and login (sloth-tailscale)
OVERLAYFS_WRITABLE_PATHS[data] = "/etc/ssh /etc/wpa_supplicant /var/lib/tailscale"

SYSTEMD_SERVICE:${PN} = "data.mount rec.mount"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"

do_install() {
    install -d ${D}${nonarch_libdir}/repart.d
    install -m 0644 ${S}/50-sloth-data.conf ${D}${nonarch_libdir}/repart.d/
    install -m 0644 ${S}/60-sloth-rec.conf ${D}${nonarch_libdir}/repart.d/

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${S}/data.mount ${D}${systemd_system_unitdir}/
    install -m 0644 ${S}/rec.mount ${D}${systemd_system_unitdir}/

    install -d ${D}${systemd_system_unitdir}/sshdgenkeys.service.d
    install -m 0644 ${S}/10-sloth-data-overlays.conf ${D}${systemd_system_unitdir}/sshdgenkeys.service.d/

    install -d ${D}/data ${D}/rec
}

FILES:${PN} += " \
    ${nonarch_libdir}/repart.d \
    ${systemd_system_unitdir}/sshdgenkeys.service.d \
    /data \
    /rec \
"
RDEPENDS:${PN} = "systemd e2fsprogs-mke2fs"
