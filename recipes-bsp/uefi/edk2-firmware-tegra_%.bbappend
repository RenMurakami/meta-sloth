# The image's tegraflash bundle and tegra-uefi-capsules both wait for
# virtual/bootloader:do_deploy (this recipe), then copy every
# TEGRA_BOOTCONTROL_OVERLAYS entry from DEPLOY_DIR_IMAGE.  When a machine
# lists SlothBootOrder.dtbo, deploy it first.  This uses the same chaining that
# meta-tegra uses for l4t-launcher-rootfs-ab-config.  Machines that do not
# list it (the SD card machine) are unchanged.
do_deploy[depends] += "${@'sloth-boot-order:do_deploy' if 'SlothBootOrder.dtbo' in (d.getVar('TEGRA_BOOTCONTROL_OVERLAYS') or '').split() else ''}"
