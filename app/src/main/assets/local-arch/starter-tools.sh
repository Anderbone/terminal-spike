#!/bin/bash
# Signed official Arch ARM repositories only. No AUR or account setup.
set -euo pipefail
packages=(git bash-completion zoxide ripgrep fd fzf bat eza jq nano less unzip zip curl openssh rsync base-devel npm)
# Keep an existing user's Node choice; fresh environments get Cable Flow's Node 24 LTS.
if ! command -v node >/dev/null 2>&1; then
    packages+=(nodejs-lts-krypton)
fi
pacman -Syu --needed --noconfirm "${packages[@]}"
for tool in git zoxide rg fd fzf bat eza jq nano less unzip zip curl ssh rsync make gcc node npm; do
    command -v "$tool"
done
# Own a separate profile fragment, leaving existing shell customizations intact.
profile=/etc/profile.d/terminal-spike-tools.sh
if [[ ! -e "$profile" && ! -L "$profile" ]]; then
    (set -o noclobber; cat > "$profile" <<'PROFILE'
# Terminal Spike starter tools: interactive Bash navigation and completion.
if [ -n "${BASH_VERSION:-}" ]; then
    case $- in
        *i*)
            if [ -r /usr/share/bash-completion/bash_completion ]; then
                . /usr/share/bash-completion/bash_completion
            fi
            if command -v zoxide >/dev/null 2>&1; then
                eval "$(zoxide init bash)"
            fi
            ;;
    esac
fi
PROFILE
    )
fi
pacman -Q "${packages[@]}"
node --version
printf '\nLOCAL_ARCH_STARTER_TOOLS_OK\n'
