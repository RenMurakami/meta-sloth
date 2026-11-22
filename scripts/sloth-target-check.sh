#!/bin/sh
# On-target health check for a board running sloth-image or sloth-image-dev.
#
# Run from the WSL host.  Copy first, then run: piping the script into
# `sh -s` lets any command that reads stdin eat the rest of the script, and
# scp needs an sftp-server the production image does not carry.
#   ssh root@<board-ip> 'cat > /tmp/sloth-target-check.sh' < meta-sloth/scripts/sloth-target-check.sh
#   ssh root@<board-ip> sh /tmp/sloth-target-check.sh | tee sloth-target-check.txt
#
# Every check prints PASS, FAIL or INFO with what it saw, so the output can be
# pasted into a verification record as-is.  Exit status is the FAIL count.
# Written for busybox sh: no bash-isms.

fails=0
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; fails=$((fails + 1)); }
info() { echo "INFO  $*"; }
check() {   # check <description> <command...>
    desc="$1"; shift
    if out=$("$@" 2>&1); then pass "$desc${out:+: $(echo "$out" | head -n 1)}"
    else fail "$desc${out:+: $(echo "$out" | head -n 1)}"; fi
}

echo "=== identity"
info "kernel   $(uname -r)"
info "image    $(grep -E '^(PRETTY_NAME|VERSION_ID)=' /etc/os-release | tr '\n' ' ')"
info "uptime   $(uptime)"

echo "=== boot disk and A/B slot"
# The root must be one of the two rootfs slots of the disk the machine config
# boots from (TNSPEC_BOOTDEV), as named by sloth-bootdisk.  This works for
# both the SD machine and the NVMe machine.
root=$(readlink -f "$(findmnt -n -o SOURCE /)")
app=$(readlink -f /dev/disk/sloth-boot/APP 2>/dev/null)
app_b=$(readlink -f /dev/disk/sloth-boot/APP_b 2>/dev/null)
if [ -n "$app" ] && [ "$root" = "$app" ]; then
    pass "root is APP (slot A) of the boot disk: $root"
elif [ -n "$app_b" ] && [ "$root" = "$app_b" ]; then
    pass "root is APP_b (slot B) of the boot disk: $root"
else
    fail "root $root is neither sloth-boot/APP (${app:-missing}) nor APP_b (${app_b:-missing})"
fi
case "$root" in
    /dev/nvme*)   info "boot disk: NVMe" ;;
    /dev/mmcblk*) info "boot disk: SD card" ;;
esac
if command -v nvbootctrl >/dev/null 2>&1; then
    info "nvbootctrl current rootfs slot: $(nvbootctrl -t rootfs get-current-slot 2>&1)"
    nvbootctrl -t rootfs dump-slots-info 2>&1 | sed 's/^/INFO    /'
fi
# growfs (base-files bbappend) or the OTA's diskformat should leave ext4
# covering the whole slot.  df reports a few percent less than the partition
# because of ext4 metadata.
part_bytes=$(awk '{print $1 * 512}' "/sys/class/block/$(basename "$root")/size" 2>/dev/null)
fs_kb=$(df -k / | awk 'NR==2 {print $2}')
if [ -n "$part_bytes" ] && awk -v f="$fs_kb" -v p="$part_bytes" 'BEGIN { exit !(f * 1024 >= 0.9 * p) }'; then
    pass "rootfs fills its slot: $(awk -v f="$fs_kb" -v p="$part_bytes" 'BEGIN { printf "%.1f GiB filesystem on a %.1f GiB partition", f / 1048576, p / 1073741824 }')"
else
    fail "rootfs does not fill its slot (growfs?): filesystem ${fs_kb} KiB, partition ${part_bytes:-?} bytes"
fi
info "root usage $(df -h / | awk 'NR==2 {print $3 " used, " $4 " free of " $2}')"

echo "=== storage (/data, /rec, OTA staging)"
# Both must come from the boot disk: another disk with a Sloth layout carries
# the same labels (recipes-core/sloth-data/files/data.mount).
for m in /data:sloth-data /rec:sloth-rec; do
    dir=${m%%:*}; part=${m#*:}
    src=$(readlink -f "$(findmnt -n -o SOURCE "$dir" 2>/dev/null)" 2>/dev/null)
    want=$(readlink -f "/dev/disk/sloth-boot/$part" 2>/dev/null)
    if [ -z "$src" ]; then
        fail "$dir not mounted"
    elif [ "$src" != "$want" ]; then
        fail "$dir is $src, not the boot disk's $part (${want:-missing})"
    else
        pass "$dir mounted from the boot disk ($src): $(df -h "$dir" | awk 'NR==2 {print $2 " total, " $4 " free"}')"
    fi
done
check "/data/ota exists (OTA staging)" test -d /data/ota
info "/data/ota holds: $(ls -A /data/ota 2>/dev/null | tr '\n' ' ')(empty between updates)"

echo "=== boot robustness (a hang or panic has to end in a reboot)"
check "kernel reboots on panic" sh -c 'p=$(cat /proc/sys/kernel/panic); [ "$p" -gt 0 ] && echo "kernel.panic=$p"'
check "systemd watchdog configured" sh -c 'v=$(systemctl show -p RuntimeWatchdogUSec --value); [ -n "$v" ] && [ "$v" != "0" ] && [ "$v" != "infinity" ] && echo "RuntimeWatchdogUSec=$v"'
# RuntimeWatchdogUSec is only the configured value: systemd reports it even when
# no watchdog device exists.  A ping timestamp proves it opened the hardware
# watchdog (watchdog-tegra-t18x on Orin) and is feeding it.
info "watchdog devices: $(ls /dev/watchdog* 2>/dev/null | tr '\n' ' ')$(cat /sys/class/watchdog/watchdog0/identity 2>/dev/null)"
check "systemd is pinging the hardware watchdog" sh -c 't=$(systemctl show -p WatchdogLastPingTimestampMonotonic --value); [ -n "$t" ] && [ "$t" != "0" ] && echo "$(systemctl show -p WatchdogDevice --value) last ping $(systemctl show -p WatchdogLastPingTimestamp --value)"'

echo "=== UEFI boot order"
# The NVMe machine needs the NVMe first, ahead of the SD card, USB, network
# boot and the UEFI shell.  UEFI sorts the order only once
# (recipes-bsp/sloth-boot-order), so it is checked on the board.  The rescue
# system is not a boot entry of its own: L4TLauncher on the NVMe falls back to
# the recovery partition (see "rescue system" below).
if command -v efibootmgr >/dev/null 2>&1; then
    efibootmgr 2>/dev/null | grep -E '^(BootCurrent|BootOrder)' | sed 's/^/INFO  /'
    classes=$(efibootmgr -v 2>/dev/null | awk '
        /^BootOrder:/ { n = split($2, order, ","); next }
        /^Boot[0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f][0-9A-Fa-f]/ {
            id = substr($1, 5, 4); c = "other"
            if ($0 ~ /NVMe\(/) c = "nvme"
            else if ($0 ~ /SD\(/) c = "sd"
            else if ($0 ~ /USB\(/) c = "usb"
            else if ($0 ~ /MAC\(|IPv4\(|IPv6\(|Uri\(/) c = "net"
            else if ($0 ~ /[Ss]hell/) c = "shell"
            cls[id] = c
        }
        END {
            # No "s (...)": busybox awk reads a name before "(" as a call.
            s = ""; sep = ""
            for (i = 1; i <= n; i++) {
                c = "?"; if (order[i] in cls) c = cls[order[i]]
                s = s sep c; sep = ","
            }
            print s
        }')
    info "boot order by device: $classes"
    case "$root" in
        /dev/nvme*)
            if echo "$classes" | grep -Eq '^nvme(,|$)'; then
                pass "the NVMe is first in the boot order"
            else
                fail "the first boot entry is not the NVMe: $classes (reorder with efibootmgr -o)"
            fi
            ;;
    esac
    # The rescue test entry ("bootmode=recovery", made with efibootmgr -C) is
    # meant for BootNext only.  In BootOrder it would start the rescue system
    # on every boot.
    rescue_ids=$(efibootmgr 2>/dev/null | sed -n 's/^Boot\([0-9A-Fa-f]\{4\}\)\*\{0,1\} Sloth rescue.*/\1/p')
    order=",$(efibootmgr 2>/dev/null | sed -n 's/^BootOrder: //p'),"
    for id in $rescue_ids; do
        case "$order" in
            *",$id,"*) fail "rescue test entry Boot$id is in BootOrder: every boot would start the rescue system (efibootmgr -o without it)" ;;
            *) info "rescue test entry Boot$id (BootNext only, not in BootOrder)" ;;
        esac
    done
else
    info "efibootmgr not installed: boot order not checked"
fi

echo "=== rescue system (recovery partition)"
# On the NVMe machine the recovery partition holds sloth-rescue-image, an
# Android boot image that L4TLauncher boots when both rootfs slots are
# unbootable.  Only a full flash writes it; an OTA does not.
rec=$(readlink -f /dev/disk/sloth-boot/recovery 2>/dev/null)
case "$root" in
    /dev/nvme*)
        if [ -b "$rec" ]; then
            info "recovery partition: $rec, $(( $(cat /sys/class/block/${rec##*/}/size) / 2048 )) MiB"
            magic=$(dd if="$rec" bs=8 count=1 2>/dev/null)
            if [ "$magic" = "ANDROID!" ]; then
                pass "recovery holds a boot image (the rescue system)"
            else
                fail "recovery partition is empty: no rescue system if both slots fail"
            fi
        else
            fail "no recovery partition on the boot disk"
        fi
        ;;
    *)
        info "not booted from the NVMe: rescue system not checked"
        ;;
esac

echo "=== systemd"
failed=$(systemctl --failed --no-legend --plain 2>/dev/null)
if [ -z "$failed" ]; then pass "no failed units"; else fail "failed units: $(echo "$failed" | awk '{print $1}' | tr '\n' ' ')"; fi
info "$(systemd-analyze 2>/dev/null | head -n 1)"
systemd-analyze blame 2>/dev/null | head -n 5 | sed 's/^/INFO    blame /'

echo "=== network"
check "r8168 loaded"            sh -c 'lsmod | grep -w r8168'
check "eth0 has IPv4"           sh -c 'ip -4 -o addr show eth0 | grep -o "inet [0-9./]*"'
check "default route"           sh -c 'ip route | grep ^default'

echo "=== ssh policy"
if [ -f /etc/ssh/sshd_config.d/10-sloth-hardening.conf ]; then
    info "production image: sshd hardening drop-in present"
    # root's home from /etc/passwd (/root since oe-core moved it; sshd reads
    # only that one), not a hard-coded /home/root.
    root_home=$(awk -F: '$1 == "root" {print $6}' /etc/passwd)
    check "root authorized_keys present ($root_home)" test -s "$root_home/.ssh/authorized_keys"
    check "sshd: no password auth" sh -c 'sshd -T 2>/dev/null | grep -x "passwordauthentication no"'
else
    info "dev image: no sshd hardening drop-in (expected for sloth-image-dev only)"
fi

echo "=== CUDA / TensorRT (C7)"
check "/usr/local/cuda symlink"  sh -c 'readlink /usr/local/cuda'
check "symlink target exists"    test -d /usr/local/cuda/
check "CUDA_HOME in login env"   sh -c '. /etc/profile.d/sloth-cuda.sh && echo "$CUDA_HOME"'
check "libcudart present"        sh -c 'ls /usr/local/cuda/lib*/libcudart.so.* 2>/dev/null || ls /usr/lib/libcudart.so.*'
check "libnvinfer present"       sh -c 'ls /usr/lib/libnvinfer.so.*'
check "GPU device nodes"         sh -c 'ls /dev/nvgpu/igpu0/ctrl 2>/dev/null || ls /dev/nvhost-ctrl-gpu'
if command -v trtexec >/dev/null 2>&1; then info "trtexec: $(command -v trtexec)"; else info "trtexec: not installed (on-target engine build needs the builder API or trtexec)"; fi
if command -v python3 >/dev/null 2>&1; then
    check "GPU visible to CUDA runtime" python3 - <<'PY'
import ctypes, glob, sys
libs = sorted(glob.glob('/usr/local/cuda/lib*/libcudart.so.*') + glob.glob('/usr/lib/libcudart.so.*'))
if not libs:
    sys.exit("libcudart not found")
rt = ctypes.CDLL(libs[0])
n = ctypes.c_int(0)
err = rt.cudaGetDeviceCount(ctypes.byref(n))
if err != 0 or n.value < 1:
    sys.exit(f"cudaGetDeviceCount err={err} count={n.value}")
print(f"{n.value} device(s) via {libs[0]}")
PY
else
    info "python3 not in this image: CUDA runtime check skipped (sloth-image-dev has it)"
fi

echo "=== camera path (C6, C8)"
check "uvcvideo module installed" sh -c 'find /lib/modules/$(uname -r) -name "uvcvideo.ko*" | head -n 1'
check "uvcvideo loads"            sh -c 'modprobe uvcvideo && lsmod | grep -w uvcvideo'
info "video devices: $(ls /dev/video* 2>/dev/null | tr '\n' ' ' || echo none) (none is expected without the camera plugged in)"
info "OpenCV libs: $(ls /usr/lib/libopencv_*.so.* 2>/dev/null | xargs -n1 basename | sed 's/\.so\..*//' | sort -u | tr '\n' ' ')"
if command -v python3 >/dev/null 2>&1; then
    check "python3 cv2 imports" python3 -c 'import cv2; print(cv2.__version__)'
fi

echo "=== CAN (M3 not started: modules only)"
info "can modules loaded: $(lsmod | awk '/can/ {print $1}' | tr '\n' ' ')"
info "can interfaces: $(ip -o link show type can 2>/dev/null | awk -F': ' '{print $2}' | tr '\n' ' ')"

echo "=== dev tooling (sloth-image-dev only)"
for t in gdb strace; do
    if command -v $t >/dev/null 2>&1; then info "$t present"; else info "$t absent"; fi
done

echo "=== summary: $fails FAIL"
exit $fails
