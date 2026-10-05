#!/bin/sh
# deer-net-debug.sh - collect network bring-up evidence on the Yocto (deer) image.
#
# Run ON THE JETSON, booted into the deer image:
#     sh /path/to/deer-net-debug.sh
#
# It writes /tmp/deer-net-debug.txt and, if the UDA partition is present,
# copies the report to the SD card so it can be read later from JetPack or WSL
# without retyping anything from the screen.
#
# Background: the Orin Nano devkit's RJ45 is a Realtek RTL8168 on PCIe
# (meta-tegra: conf/machine/include/p3768.inc -> NVIDIA_ETHERNET =
# "nv-kernel-module-r8168").  The Tegra-internal EQOS (ethernet@2310000) and
# MGBE (ethernet@6800000) nodes are legitimately "disabled" on this board, so
# nvethernet is the wrong driver to chase.

OUT=/tmp/deer-net-debug.txt
KREL=$(uname -r)

exec 3>&1
exec >"$OUT" 2>&1

sec() { echo; echo "==================== $* ===================="; }

echo "deer-net-debug  $(date -u 2>/dev/null)  kernel=$KREL"

sec "1. identity / rootfs (is this really the SD?)"
cat /etc/os-release 2>/dev/null
echo "--- model:"; cat /proc/device-tree/model 2>/dev/null; echo
echo "--- mounted root:"
if command -v findmnt >/dev/null 2>&1; then findmnt / ; else mount | grep " / " ; fi
echo "--- expect /dev/mmcblk0p1 (SD).  /dev/nvme0n1p1 means the initrd grabbed"
echo "    JetPack's APP partition instead - see runbook 9.6 boot.slot_suffix="

sec "2. PCIe - is the Realtek NIC even enumerated?"
if command -v lspci >/dev/null 2>&1; then
    lspci -nn
    echo "--- realtek only:"; lspci -nn | grep -i -e 10ec -e realtek
else
    echo "lspci not installed"
fi
echo "--- PCI devices in sysfs:"; ls /sys/bus/pci/devices/ 2>/dev/null

sec "3. is the r8168 module present on disk?"
find /lib/modules/"$KREL" -name 'r816*' -o -name '*realtek*' 2>/dev/null
echo "--- modules.alias entries for realtek pci ids:"
grep -i -e '10ec' -e 'r8168' -e 'r8169' /lib/modules/"$KREL"/modules.alias 2>/dev/null | head -20

sec "4. loaded modules"
echo "--- /proc/modules line count: $(wc -l < /proc/modules 2>/dev/null)"
cat /proc/modules 2>/dev/null | sort | head -60
echo "--- lsmod:"; lsmod 2>/dev/null | head -40

sec "5. try loading it by hand"
for m in r8168 r8169 realtek; do
    echo "--- modprobe $m"; modprobe "$m" 2>&1; echo "exit=$?"
done
echo "--- interfaces after modprobe:"; ip link show

sec "6. network interfaces / addresses / routes"
ip link show
ip -4 addr show
ip route show 2>/dev/null
echo "--- sysfs net:"; ls /sys/class/net/

sec "7. does anything configure the link? (systemd-networkd)"
ls -l /etc/systemd/network/ 2>/dev/null || echo "NO /etc/systemd/network/ - nothing will DHCP"
systemctl is-enabled systemd-networkd 2>/dev/null
systemctl status systemd-networkd --no-pager 2>/dev/null | head -20
systemctl is-enabled systemd-resolved 2>/dev/null

sec "8. failed units"
systemctl --failed --no-pager 2>/dev/null

sec "9. dmesg - pcie, realtek, eth, phy"
dmesg 2>/dev/null | grep -i -e pcie -e pci -e realtek -e r816 -e eth -e phy -e link | tail -60

sec "10. dmesg tail (raw)"
dmesg 2>/dev/null | tail -40

sec "11. inference stack sanity (unrelated to net, cheap to grab)"
ls -l /usr/lib/libnvinfer.so.10 /usr/lib/libnvinfer_builder_resource_sm86.so.* 2>/dev/null
ls -l /usr/lib/libcudart* 2>/dev/null
command -v tegrastats >/dev/null 2>&1 && echo "tegrastats present"

sec "12. boot time"
command -v systemd-analyze >/dev/null 2>&1 && systemd-analyze 2>/dev/null

exec 1>&3
echo "report written to $OUT"

# Stash a copy on the SD's UDA partition so it survives a reboot and can be
# read from JetPack (mount /dev/mmcblk0p15) or copied to WSL.
for p in /dev/mmcblk0p15 /dev/mmcblk0p14; do
    [ -b "$p" ] || continue
    mkdir -p /mnt/uda 2>/dev/null
    if mount "$p" /mnt/uda 2>/dev/null; then
        cp "$OUT" /mnt/uda/ 2>/dev/null && echo "copy saved to $p:/deer-net-debug.txt"
        sync; umount /mnt/uda 2>/dev/null
        break
    fi
done

echo
echo "Next: read it here with 'less $OUT', or reboot to JetPack and run:"
echo "  sudo mount /dev/mmcblk0p15 /mnt && cat /mnt/deer-net-debug.txt"
