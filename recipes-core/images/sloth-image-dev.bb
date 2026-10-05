SUMMARY = "Sloth detection ECU development image - passwordless root, never ship this"

require recipes-core/images/sloth-image.bb

# Everything sloth-image deliberately dropped, put back for bench work: this is
# the image to flash while bringing hardware up, and the four login features
# below are exactly what was on the board for the D8 verification run on
# 2026-09-13.
#
# oe-core no longer has a working "debug-tweaks" umbrella feature - it was
# split into the four below - so they are listed one by one.
#
# Listing any of those flips SLOTH_LOGIN_IS_OPEN in sloth-image.bb, which turns
# the authorized-keys requirement into a warning and skips the sshd hardening
# drop-in.  Setting SLOTH_SSH_AUTHORIZED_KEYS still works here and is worth
# doing - key auth keeps working once the passwords are gone.
IMAGE_FEATURES += " \
    allow-empty-password \
    empty-root-password \
    allow-root-login \
    serial-autologin-root \
    tools-debug \
"

# Bench tooling.  python3-opencv is the Python binding for the trimmed OpenCV
# libraries sloth-image already installs, so detection code can be prototyped on
# the board before it is a recipe.
IMAGE_INSTALL:append = " \
    python3-opencv \
    python3-core \
    strace \
    gdb \
"
