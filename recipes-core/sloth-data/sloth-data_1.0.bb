SUMMARY = "Sloth /data and /rec partitions (created by systemd-repart on first boot)"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://50-sloth-data.conf \
    file://60-sloth-rec.conf \
    file://data.mount \
    file://rec.mount \
    file://10-sloth-data-overlays.conf \
    file://sloth-data-tmpfiles.conf \
"
S = "${UNPACKDIR}"

# Size of /data, in systemd-repart's SizeMinBytes/SizeMaxBytes syntax.  /rec
# takes the rest of the disk.  The machine config sets it:
# sloth-orin-nano-nvme uses 48G.  The 8G default is what the SD-card boards
# were first set up with.  systemd-repart cannot grow /data on a board where
# it already exists, because /rec sits right behind it.  So changing this for a
# machine that is already deployed means re-creating /data and /rec.
SLOTH_DATA_SIZE ?= "8G"

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

python () {
    import re
    if not re.fullmatch(r'[0-9]+[KMGT]', d.getVar('SLOTH_DATA_SIZE') or ''):
        bb.fatal("SLOTH_DATA_SIZE = '%s': expected a size such as 48G" % d.getVar('SLOTH_DATA_SIZE'))
}

do_install() {
    install -d ${D}${nonarch_libdir}/repart.d
    sed -e 's,@SLOTH_DATA_SIZE@,${SLOTH_DATA_SIZE},g' \
        ${S}/50-sloth-data.conf > ${D}${nonarch_libdir}/repart.d/50-sloth-data.conf
    chmod 0644 ${D}${nonarch_libdir}/repart.d/50-sloth-data.conf
    if grep -q '@SLOTH_DATA_SIZE@' ${D}${nonarch_libdir}/repart.d/50-sloth-data.conf; then
        bbfatal "50-sloth-data.conf: @SLOTH_DATA_SIZE@ was not substituted"
    fi
    install -m 0644 ${S}/60-sloth-rec.conf ${D}${nonarch_libdir}/repart.d/

    install -d ${D}${nonarch_libdir}/tmpfiles.d
    install -m 0644 ${S}/sloth-data-tmpfiles.conf ${D}${nonarch_libdir}/tmpfiles.d/sloth-data.conf

    install -d ${D}${systemd_system_unitdir}
    install -m 0644 ${S}/data.mount ${D}${systemd_system_unitdir}/
    install -m 0644 ${S}/rec.mount ${D}${systemd_system_unitdir}/

    install -d ${D}${systemd_system_unitdir}/sshdgenkeys.service.d
    install -m 0644 ${S}/10-sloth-data-overlays.conf ${D}${systemd_system_unitdir}/sshdgenkeys.service.d/

    install -d ${D}/data ${D}/rec
}

FILES:${PN} += " \
    ${nonarch_libdir}/repart.d \
    ${nonarch_libdir}/tmpfiles.d \
    ${systemd_system_unitdir}/sshdgenkeys.service.d \
    /data \
    /rec \
"
# sloth-bootdisk provides the /dev/disk/sloth-boot/ links data.mount and
# rec.mount use.
RDEPENDS:${PN} = "systemd e2fsprogs-mke2fs sloth-bootdisk"
