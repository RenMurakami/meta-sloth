SUMMARY = "Hardware watchdog for systemd, so a hung Sloth reboots"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://${COMMON_LICENSE_DIR}/MIT;md5=0835ade698e0bcf8506ecda2f7b4f302"

# A/B fallback counts reboots.  UEFI lowers a slot's retry count on each boot
# and switches slots, then falls back to the rescue system in the recovery
# partition (recipes-core/images/sloth-rescue-image.bb), only when
# the board actually restarts.  A board that hangs and never reboots never
# falls back.  Two pieces turn a hang into a reboot:
#
#   panic=10           kernel argument (conf/machine/include/
#                      sloth-orin-nano-common.inc).  The kernel is built with
#                      CONFIG_PANIC_TIMEOUT=0, so without it a panic halts for
#                      good.
#   RuntimeWatchdogSec this file.  systemd arms /dev/watchdog0 and pets it.  If
#                      PID 1 stops (a kernel lockup or a wedged systemd), the
#                      watchdog resets the board after 30 s.  The default
#                      (0) leaves the watchdog off.  RebootWatchdogSec keeps its
#                      default of 10 min, which covers a reboot that hangs.
#
# Not covered: a boot that stops in emergency mode or waits on a unit forever
# while PID 1 is still alive.  That needs a check that the boot finished, which
# is the planned sloth-health gate in front of nvbootctrl verify.

SRC_URI = "file://10-sloth-watchdog.conf"
S = "${UNPACKDIR}"

inherit allarch

do_install() {
    install -d ${D}${systemd_unitdir}/system.conf.d
    install -m 0644 ${S}/10-sloth-watchdog.conf ${D}${systemd_unitdir}/system.conf.d/
}

FILES:${PN} = "${systemd_unitdir}/system.conf.d"
RDEPENDS:${PN} = "systemd"
