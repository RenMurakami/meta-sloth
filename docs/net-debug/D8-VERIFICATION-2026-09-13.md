# D8 verification run — 2026-09-13

First session with working SSH (`root@192.168.1.128`), so every check below was
run remotely instead of typed on the HDMI console. Clears the whole
"未確認" list from `session-log-2026-09-09.md` §7.

> Note: the deer image now answers on **192.168.1.128** (the address JetPack
> used to hold). Same NIC MAC, same DHCP lease. `ssh-keygen -R 192.168.1.128`
> is needed once per client because the host key differs from JetPack's.

## Results

| Check | Result |
|---|---|
| Root really the SD? | ✅ `/dev/mmcblk0p1` ext4 rw — NVMe enumerated but untouched |
| Kernel | `6.8.12-l4t-r39.2.1-1021.21` |
| Failed units | ✅ zero |
| Boot time (D6/T1 baseline) | ✅ **12.851 s** = 9.643 kernel + 3.208 userspace |
| NIC PCI ID | ✅ `0008:01:00.0 [10ec:8168]` RTL8111/8168 rev 15 |
| `r8168` loaded | ✅ `r8168  512000  0` |
| Network | ✅ `eth0 192.168.1.128/24 metric 1024` |
| TensorRT | ✅ `libnvinfer.so.10.16.2` 861 MB + `libnvinfer_builder_resource_sm86` 180 MB |
| CUDA | ✅ `libcudart.so.13` and `libcuda.so.1` both resolved by `ldconfig` |
| GPU node | ✅ `/dev/nvgpu/igpu0` |
| Memory | 7.7 GB total, 194 MB used at idle |

Slowest units: `dev-mmcblk0p1.device` 1.456 s, `systemd-random-seed` 610 ms,
`systemd-tmpfiles-setup` 564 ms, `systemd-udev-trigger` 563 ms.

The initrd picked the **SD's** `APP` even though the NVMe also has an `APP`
partition. Good, but it does not remove the need for `boot.slot_suffix=` when
NVMe dual-boot is actually attempted — this was SD-only boot.

## Gaps found

> **Gaps 1-4 are fixed in commit `79fbba0`** ("close the three D8 gaps in one
> rebuild"), and `deer-image-dev` has been rebuilt and its rootfs inspected:
> `/etc/fstab` carries `defaults,x-systemd.growfs`, `resize2fs`/`tune2fs` are
> installed, `/usr/local/cuda -> cuda-13.2` resolves to a directory that is
> really there, and the dev image keeps `PermitRootLogin yes` /
> `PermitEmptyPasswords yes` / `root::` with no hardening drop-in - so bench
> SSH is unchanged from this run.
>
> **Not booted.** Everything below is confirmed in the built filesystem, not on
> hardware. The card image is built (`flash/sd/deer-sdcard.img.zst`) but
> nothing has been written to a microSD: WSL2 has no raw block-device or USB
> access, so writing the card is a Windows-side step.

### 1. ~10 GiB of the root partition is unused

`mmcblk0p1` is **14 GiB** (14680064 blocks) but the ext4 inside is **4.0 G,
46% used, 2.0 G available**. `IMAGE_ROOTFS_EXTRA_SPACE = "2097152"` sizes the
filesystem to content + 2 GiB and nothing ever grows it.

Fix: grow on first boot (`x-systemd.growfs` on `/`) rather than baking a
14 GiB rootfs. Keeps the `.img` small, keeps `dd` fast, keeps future swupdate
payloads small, and a fresh rootfs written to `APP_b` by an OTA grows itself.

**This only reaches the 14 GiB partition boundary.** The partition itself is
fixed at 14 GiB by the flash XML (`flash_t234_qspi_sd_rootfs_ab.xml`); growfs
does not move partition edges.

**Fixed** - `recipes-core/base-files/base-files_%.bbappend` adds
`x-systemd.growfs` to the `/` entry. `IMAGE_ROOTFS_EXTRA_SPACE` also drops
from 2 GiB to 512 MiB, since padding the image is pointless once it grows at
boot. To confirm on the next boot: `df -h /` should read ~14 G, and
`systemctl status systemd-growfs-root` should show it ran once.

Correction to "keeps `dd` fast" above, and to the message of commit `79fbba0`:
shrinking `IMAGE_ROOTFS_EXTRA_SPACE` does **not** speed up writing the card.
`deer-sdcard.img` is a whole-disk image fixed at 32 G by `dosdcard.sh -s 32G`,
and APP/APP_b are 14 GiB each regardless (`<size> 15032385536 </size>` in
`flash.xml.in`), so the bytes written to the card are the same either way.
What actually shrinks is the `*.rootfs.ext4` artefact - roughly 4.7 G to 3.2 G
- which is what an OTA ships, so the swupdate half of the claim stands. The
`.img.zst` shrinks only marginally, because the space removed was empty and
already compressed to almost nothing.

Measured after the rebuild, which settles it: the rootfs ext4 went
4 663 996 416 -> 3 053 674 496 bytes (-1.6 G), while `deer-sdcard.img.zst` went
1 908 098 558 -> 1 910 411 704 bytes - **2.3 MB larger**, because the freed
space was zeros and the image also gained resize2fs, tune2fs and
deer-cuda-env. The card still takes the same 31 680 000 000 bytes to write.

### 2. `resize2fs` is missing despite `e2fsprogs` being installed

Yocto splits `e2fsprogs` into per-tool packages. Need
`e2fsprogs-resize2fs` (and `e2fsprogs-tune2fs`) explicitly. Until then the
above cannot even be fixed by hand on the device.

**Fixed** - both added to `IMAGE_INSTALL`. Note this is belt-and-braces:
`systemd-growfs(8)` resizes ext4 with the `EXT4_IOC_RESIZE_FS` ioctl and never
calls `resize2fs`, so gap 1 does not depend on gap 2.

### 3. Passwordless root SSH is open to the whole LAN

```
PermitRootLogin yes
PermitEmptyPasswords yes
```

Login succeeds with `ssh -o BatchMode=yes` — i.e. **no credential of any
kind**. Acceptable for bring-up, but it makes the `deer-image-dev` /
`deer-image` split (session log §8 item 3) a security task, not a tidy-up.

**Fixed** - `deer-image` is now the production image (keys only, via
`DEER_SSH_AUTHORIZED_KEYS`, which is a hard build error if unset) and
`deer-image-dev` carries the bring-up features. **Bench work must now build
`deer-image-dev`**; `deer-image` without a key will not build, by design.

### 4. No `/usr/local/cuda` symlink

Only `/usr/local/cuda-13.2` exists. Anything defaulting to
`CUDA_HOME=/usr/local/cuda` will fail.

**Fixed** - the `deer-cuda-env` recipe ships the symlink plus a `profile.d`
setting `CUDA_HOME`/`CUDA_PATH`.

### 5. Wi-Fi chip present, no driver

`0001:01:00.0 [10ec:c822]` RTL8822CE is on the bus with no driver installed.
Expected — not wired up yet, listed so it is not rediscovered as a surprise.

**Still open, deliberately.** Unlike 1-4 this is a decision (which driver and
firmware package, and whether the product wants Wi-Fi at all), not a defect in
what already ships.

## Trap waiting in the `/data` work (D8)

The SD is 233 GB and the image is 32 GB, so ~200 GB is unallocated. But the
`.img` was `dd`'d as a whole, so the **GPT secondary header sits at the 32 GB
mark**, not at the end of the card. `systemd-repart` will not see the tail as
free space until that header is moved to the end of the disk (`sgdisk -e` or
equivalent).

The `partprobe` warning recorded in session log §6 — *"Not all of the space
appears to be used"* — is this exact symptom, not a harmless message.
