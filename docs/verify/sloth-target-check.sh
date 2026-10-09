#!/bin/sh
# On-target health check for a board running sloth-image or sloth-image-dev.
#
# Run from the WSL host.  Copy first, then run: piping the script into
# `sh -s` lets any command that reads stdin eat the rest of the script, and
# scp needs an sftp-server the production image does not carry.
#   ssh root@<board-ip> 'cat > /tmp/sloth-target-check.sh' < meta-sloth/docs/verify/sloth-target-check.sh
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

echo "=== boot medium (C11: must be the SD card, not the NVMe APP)"
root=$(findmnt -n -o SOURCE /)
case "$root" in
    /dev/mmcblk0p*) pass "root is on SD: $root" ;;
    *)              fail "root is not on SD: $root" ;;
esac
info "root size $(df -h / | awk 'NR==2 {print $2 " total, " $4 " free"}') (growfs should have grown it to ~14G)"

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
    check "root authorized_keys present" test -s /home/root/.ssh/authorized_keys
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
