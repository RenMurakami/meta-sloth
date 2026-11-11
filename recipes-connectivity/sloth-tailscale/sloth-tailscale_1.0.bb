SUMMARY = "Sloth ECU remote access over Tailscale"
DESCRIPTION = "tailscaled (meta-networking) with its node state on /data, so the \
board keeps its tailnet identity across OTA updates.  Join the tailnet once, \
on the device: tailscale up --accept-dns=false"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

SRC_URI = "file://10-sloth-tailscale.conf"
S = "${UNPACKDIR}"

inherit allarch

do_install() {
    install -D -m 0644 ${S}/10-sloth-tailscale.conf \
        ${D}${systemd_system_unitdir}/tailscaled.service.d/10-sloth-tailscale.conf
    # Lower directory of the /data overlay (sloth-data).  Empty on purpose.
    install -d -m 0700 ${D}${localstatedir}/lib/tailscale
}

FILES:${PN} = " \
    ${systemd_system_unitdir}/tailscaled.service.d \
    ${localstatedir}/lib/tailscale \
"

# tailscale: the daemon and CLI; tailscaled: its systemd unit (enabled).
RDEPENDS:${PN} = "tailscale tailscaled"
