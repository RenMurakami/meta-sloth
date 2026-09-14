SUMMARY = "Deer detection ECU bring-up image - passwordless root, never ship this"
LICENSE = "MIT"

require recipes-core/images/deer-image.bb

# Everything deer-image deliberately dropped, put back for bench work: this is
# the image to flash while bringing hardware up, and it is exactly what was on
# the board for the D8 verification run on 2026-09-13.
#
# oe-core no longer has a working "debug-tweaks" umbrella feature - it was
# split into the four below - so they are listed one by one.
IMAGE_FEATURES += " \
    allow-empty-password \
    empty-root-password \
    allow-root-login \
    serial-autologin-root \
"

# Listing any of those flips DEER_LOGIN_IS_OPEN in deer-image.bb, which turns
# the authorized-keys requirement into a warning and skips the sshd hardening
# drop-in.  Setting DEER_SSH_AUTHORIZED_KEYS still works here and is worth
# doing - key auth keeps working once the passwords are gone.
