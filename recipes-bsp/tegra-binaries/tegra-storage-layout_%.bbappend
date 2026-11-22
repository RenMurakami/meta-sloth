# External (NVMe) layout changes for the Sloth rescue system
# (recipes-core/images/sloth-rescue-image.bb).  Both only apply when the
# machine config sets SLOTH_RESCUE_IMAGE (sloth-orin-nano-nvme.conf).
#
# 1. Bigger recovery partitions.  TEGRA_RECOVERY_KERNEL_PART_SIZE (RECSIZE in
#    NVIDIA's template) sizes both "recovery" and "recovery_alt".  The template
#    keeps a "reserved" partition right before APP for this case - its size
#    carries the note "Recalculate the size if RECSIZE changed".  Shrinking it
#    by twice the growth keeps APP, APP_b and everything after them (/data,
#    /rec) at the same offsets.
#
# 2. Contents for "recovery" and "recovery-dtb".  The template has RECFILE and
#    RECDTB-FILE placeholders, which image_types_tegra.bbclass deletes.  Naming
#    the files here instead keeps them: the rescue boot image, copied into the
#    tegraflash bundle by sloth-image.bb, and the kernel DTB (DTB_FILE, which
#    tegra-flash-helper.sh fills in as it does for kernel-dtb).

SLOTH_RESCUE_IMAGE ??= ""
NVIDIA_RECSIZE = "83886080"
NVIDIA_RESERVED_SIZE = "502792192"

def sloth_reserved_size(d):
    recsize = int(d.getVar('TEGRA_RECOVERY_KERNEL_PART_SIZE') or d.getVar('NVIDIA_RECSIZE'))
    growth = recsize - int(d.getVar('NVIDIA_RECSIZE'))
    reserved = int(d.getVar('NVIDIA_RESERVED_SIZE')) - 2 * growth
    if growth % (1024 * 1024):
        bb.fatal("TEGRA_RECOVERY_KERNEL_PART_SIZE = %d: use a whole number of MiB" % recsize)
    if reserved < 64 * 1024 * 1024:
        bb.fatal("TEGRA_RECOVERY_KERNEL_PART_SIZE = %d leaves only %d bytes of the reserved partition" % (recsize, reserved))
    return str(reserved)

SLOTH_RESERVED_SIZE = "${@sloth_reserved_size(d)}"

do_compile:append() {
    if [ -z "${SLOTH_RESCUE_IMAGE}" ] || [ -z "${PARTITION_LAYOUT_EXTERNAL}" ]; then
        return 0
    fi
    # Each pattern has to match exactly once, or NVIDIA's template changed and
    # the arithmetic above no longer holds.
    for pattern in '<size> ${NVIDIA_RESERVED_SIZE} </size>' \
                   '<filename> RECFILE </filename>' \
                   '<filename> RECDTB-FILE </filename>'; do
        n=$(grep -c -F "$pattern" external-flash.xml || true)
        [ "$n" = "1" ] || bbfatal "external-flash.xml: expected '$pattern' once, found it $n times; NVIDIA's NVMe template changed"
    done
    sed -i \
        -e 's,<size> ${NVIDIA_RESERVED_SIZE} </size>,<size> ${SLOTH_RESERVED_SIZE} </size>,' \
        -e 's,<filename> RECFILE </filename>,<filename> sloth-rescue.img </filename>,' \
        -e 's,<filename> RECDTB-FILE </filename>,<filename> DTB_FILE </filename>,' \
        external-flash.xml
}
do_compile[vardeps] += "SLOTH_RESCUE_IMAGE TEGRA_RECOVERY_KERNEL_PART_SIZE SLOTH_RESERVED_SIZE"
