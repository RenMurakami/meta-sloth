# swupdate-genconfig (run before swupdate.service starts) mounts the other
# rootfs slot to read its sw-versions, by /dev/disk/by-partlabel/APP{,_b}.
# On this board that name can resolve to JetPack's NVMe; use the boot-disk
# links from recipes-core/sloth-bootdisk instead.
do_compile:append() {
    sed -i -e 's,/dev/disk/by-partlabel/,/dev/disk/sloth-boot/,' ${B}/swupdate-genconfig.sh
    if grep -q 'by-partlabel' ${B}/swupdate-genconfig.sh; then
        bbfatal "swupdate-genconfig still refers to /dev/disk/by-partlabel"
    fi
}

RDEPENDS:${PN} += "sloth-bootdisk"
