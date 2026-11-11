SUMMARY = "Sloth ECU Wi-Fi (RTL8822CE, wpa_supplicant + systemd-networkd)"
DESCRIPTION = "Driver options, wpa_supplicant unit ordering and the \
sloth-wifi-setup helper.  The DHCP side is 25-wireless.network in \
sloth-network; the driver comes from the machine configuration."
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = " \
    file://rtl8822ce.conf \
    file://10-sloth-wifi.conf \
    file://sloth-wifi-setup \
"
S = "${UNPACKDIR}"

inherit allarch

do_install() {
    install -D -m 0644 ${S}/rtl8822ce.conf ${D}${sysconfdir}/modprobe.d/rtl8822ce.conf
    install -D -m 0644 ${S}/10-sloth-wifi.conf \
        ${D}${systemd_system_unitdir}/wpa_supplicant-nl80211@.service.d/10-sloth-wifi.conf
    install -D -m 0755 ${S}/sloth-wifi-setup ${D}${sbindir}/sloth-wifi-setup
    # Lower directory of the /data overlay (sloth-data).  Empty on purpose.
    install -d -m 0755 ${D}${sysconfdir}/wpa_supplicant
}

FILES:${PN} += " \
    ${systemd_system_unitdir}/wpa_supplicant-nl80211@.service.d \
    ${sysconfdir}/wpa_supplicant \
"

# wpa-supplicant's bbappend enables wpa_supplicant-nl80211@wlan0.service.
# The /etc/wpa_supplicant overlay itself is declared in sloth-data.
RDEPENDS:${PN} = "wpa-supplicant wpa-supplicant-cli iw util-linux-findmnt"
