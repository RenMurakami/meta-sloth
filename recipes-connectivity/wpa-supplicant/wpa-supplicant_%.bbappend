# Wi-Fi (W2): one wpa_supplicant per interface, bound to wlan0, with
# systemd-networkd doing DHCP on top (sloth-network/25-wireless.network).
# Upstream enables nothing and lists the D-Bus-style wpa_supplicant.service.
# The instance starts only once sloth-wifi-setup has written its config
# (ConditionPathExists in sloth-wifi's drop-in).
SYSTEMD_SERVICE:${PN} = "wpa_supplicant-nl80211@wlan0.service"
SYSTEMD_AUTO_ENABLE:${PN} = "enable"
