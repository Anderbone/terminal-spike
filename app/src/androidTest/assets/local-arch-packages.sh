set -eu
export MAKEFLAGS=-j2
pacman -Syu --noconfirm
pacman -S --noconfirm --needed git nodejs-lts-krypton npm python make gcc pkgconf
pacman -Q glibc openssl pacman git nodejs-lts-krypton npm python make gcc
node --version
npm --version
node -e 'if (process.platform !== "linux" || process.arch !== "arm64") process.exit(1); console.log(process.platform, process.arch, process.versions)'
printf '\nARCH_PACKAGES_OK\n'
