#!/bin/bash
# Signed Arch ARM packages and latest stable upstream tools. No account setup.
set -Eeuo pipefail
# PTY setup output must stay readable and retain failures instead of npm spinners.
export npm_config_progress=false npm_config_color=false
trap 'status=$?; printf "\nStarter tools failed (exit %s), line %s: %s\n" "$status" "$LINENO" "$BASH_COMMAND" >&2; exit "$status"' ERR
packages=(bubblewrap fish sudo shadow util-linux git github-cli bash-completion zoxide ripgrep fd fzf bat eza jq nano less unzip zip curl openssh rsync base-devel npm)
# Keep an existing user's Node choice; fresh environments get Cable Flow's Node 24 LTS.
if ! command -v node >/dev/null 2>&1; then
    packages+=(nodejs-lts-krypton)
fi
pacman -Syu --needed --noconfirm "${packages[@]}"
for tool in bwrap fish sudo useradd usermod runuser git gh zoxide rg fd fzf bat eza jq nano less unzip zip curl ssh rsync make gcc node npm; do
    command -v "$tool"
done
setup_tmp=$(mktemp -d)
trap 'rm -rf "$setup_tmp"' EXIT
# Resolve stable tags at the user's explicit install/update, then install that exact
# response. Prereleases are never accepted even if an upstream mislabels latest.
install_npm_cli() {
    local package=$1 native=${2:-false} version
    version=$(npm view --registry=https://registry.npmjs.org "$package" dist-tags.latest)
    [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf 'No stable release for %s: %s\n' "$package" "$version" >&2; return 1; }
    case "$package" in
        @anthropic-ai/claude-code) claude_version=$version ;;
        @opencode/cli) opencode_version=$version ;;
    esac
    printf '\nInstalling %s %s (latest stable)\n' "$package" "$version"
    local options=(--global --registry=https://registry.npmjs.org)
    if [[ "$native" == true ]]; then
        options+=(--include=optional --ignore-scripts=false "--allow-scripts=$package")
    fi
    npm install "${options[@]}" "$package@$version"
}
install_npm_cli @openai/codex
install_npm_cli @anthropic-ai/claude-code true
install_npm_cli @opencode/cli true
install_npm_cli @earendil-works/pi-coding-agent

# npm postinstall hardlinks can become dangling .l2s links under PRoot during
# an upgrade. Verify after ALL global installs, then repair from the exact same
# official, integrity-checked npm platform archive as a regular executable.
ensure_native_cli() {
    local command=$1 version=$2 platform=$3 member=$4 current directory archive license
    # A temporary emulated hardlink may work now but break after PRoot exits.
    # Only reuse our own regular file, never a package's native hardlink chain.
    if [[ -f "/usr/local/bin/$command" && ! -L "/usr/local/bin/$command" ]] &&
        current=$("/usr/local/bin/$command" --version 2>/dev/null); then
        current=${current#opencode v}
        current=${current%% *}
        if [[ "$current" == "$version" ]]; then return; fi
    fi
    printf '\nRepairing %s %s native executable (PRoot-safe copy)\n' "$command" "$version"
    directory="$setup_tmp/$command"
    mkdir -p "$directory" /usr/local/bin "/usr/local/share/licenses/$command"
    archive=$(npm pack --registry=https://registry.npmjs.org --ignore-scripts --json \
        --pack-destination "$directory" "$platform@$version" | jq -er --arg name "$platform" 'if type == "array" then select(length == 1) | .[0] else .[$name] // . end | .filename | select(type == "string")')
    [[ "$archive" =~ ^[a-zA-Z0-9._-]+\.tgz$ ]] || return 1
    tar -xzf "$directory/$archive" -C "$directory" --no-same-owner
    install -m 755 "$directory/package/$member" "$directory/executable"
    mv -fT "$directory/executable" "/usr/local/bin/$command"
    for license in LICENSE LICENSE.md; do
        if [[ -f "$directory/package/$license" ]]; then
            install -m 644 "$directory/package/$license" "/usr/local/share/licenses/$command/$license"
        fi
    done
    hash -r
    "$command" --version
}
ensure_native_cli claude "$claude_version" @anthropic-ai/claude-code-linux-arm64 claude
ensure_native_cli opencode "$opencode_version" @opencode/cli-linux-arm64 bin/opencode

# GitHub's stable release endpoint excludes prereleases. Also validate the tag,
# expected ARM64 asset, official download origin and SHA-256 before execution.
release_tmp="$setup_tmp/releases"
mkdir -p "$release_tmp"
fetch_release() {
    local repo=$1 pattern=$2 output=$3 metadata tag asset name url digest
    printf '\nChecking latest stable %s\n' "$repo"
    metadata=$(curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
        "https://api.github.com/repos/$repo/releases/latest")
    tag=$(jq -er 'select(.draft == false and .prerelease == false) | .tag_name' <<< "$metadata")
    [[ "$tag" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf 'No stable release for %s\n' "$repo" >&2; return 1; }
    asset=$(jq -cer --arg pattern "$pattern" '[.assets[] | select(.name | test($pattern))] | select(length == 1) | .[0]' <<< "$metadata")
    name=$(jq -er '.name' <<< "$asset")
    url=$(jq -er '.browser_download_url' <<< "$asset")
    digest=$(jq -er '.digest' <<< "$asset")
    [[ "$url" == "https://github.com/$repo/releases/download/$tag/$name" && "$digest" =~ ^sha256:[a-f0-9]{64}$ ]] || return 1
    printf '\nDownloading %s %s (latest stable)\n' "$repo" "$tag"
    curl --fail --show-error --location --proto '=https' --proto-redir '=https' --output "$output" "$url"
    printf '%s  %s\n' "${digest#sha256:}" "$output" | sha256sum --check
    mkdir -p "/usr/local/share/licenses/${repo##*/}"
    curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
        "https://raw.githubusercontent.com/$repo/$tag/LICENSE" -o "/usr/local/share/licenses/${repo##*/}/LICENSE"
    release_tag=$tag
}
fetch_release Jguer/yay '^yay_[0-9]+\.[0-9]+\.[0-9]+_aarch64\.tar\.gz$' "$release_tmp/yay.tar.gz"
mkdir -p "$release_tmp/yay" /usr/local/lib/terminal-spike /usr/local/bin /usr/local/share/fish/vendor_completions.d
archive_root="yay_${release_tag#v}_aarch64"
tar -xzf "$release_tmp/yay.tar.gz" -C "$release_tmp/yay" --strip-components=1 --no-same-owner "$archive_root/yay" "$archive_root/fish"
install -m 755 "$release_tmp/yay/yay" /usr/local/lib/terminal-spike/yay
install -m 644 "$release_tmp/yay/fish" /usr/local/share/fish/vendor_completions.d/yay.fish
fetch_release herdrdev/herdr '^herdr-linux-aarch64$' "$release_tmp/herdr"
install -m 755 "$release_tmp/herdr" /usr/local/bin/herdr

# makepkg refuses root. Delegate yay to a dedicated guest-only build user; sudo
# elevation stays within PRoot's emulated guest identity, never Android root.
if ! id -u aur >/dev/null 2>&1; then useradd --create-home --shell /usr/bin/fish aur; fi
mkdir -p /etc/sudoers.d
if [[ ! -e /etc/sudoers.d/terminal-spike-aur && ! -L /etc/sudoers.d/terminal-spike-aur ]]; then
    printf 'aur ALL=(ALL:ALL) NOPASSWD: ALL\n' > /etc/sudoers.d/terminal-spike-aur
    chmod 440 /etc/sudoers.d/terminal-spike-aur
fi
cat > /usr/local/bin/yay <<'YAY'
#!/bin/bash
if [[ $(id -u) == 0 ]]; then
    exec runuser -u aur -- /usr/local/lib/terminal-spike/yay "$@"
fi
exec /usr/local/lib/terminal-spike/yay "$@"
YAY
chmod 755 /usr/local/bin/yay
usermod --shell /usr/bin/fish root
mkdir -p /etc/fish/conf.d
fish_profile=/etc/fish/conf.d/terminal-spike.fish
if [[ ! -e "$fish_profile" && ! -L "$fish_profile" ]]; then
    (set -o noclobber; cat > "$fish_profile" <<'FISH'
# Terminal Spike: fish already includes highlighting, suggestions and completion.
if status is-interactive
    zoxide init fish | source
end
FISH
    )
fi
# App-owned documentation, passed as data rather than evaluated shell source.
mkdir -p /usr/local/share/terminal-spike
printf '%s\n' "$1" > /usr/local/share/terminal-spike/cable-flow-phone.md
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
printf '\nLOCAL_ARCH_TOOL_VERSIONS_BEGIN\n'
printf 'bubblewrap: '; bwrap --version
printf 'fish: '; fish --version
printf 'yay: '; yay --version
printf 'herdr: '; herdr --version
printf 'GitHub CLI: '; gh --version
printf 'Codex: '; codex --version
printf 'Claude Code: '; claude --version
printf 'OpenCode: '; opencode --version
printf 'Pi: '; pi --version
printf 'Node: '; node --version
printf 'npm: '; npm --version
printf '\nLOCAL_ARCH_TOOL_VERSIONS_END\n'
printf '\nLOCAL_ARCH_STARTER_TOOLS_OK\n'
