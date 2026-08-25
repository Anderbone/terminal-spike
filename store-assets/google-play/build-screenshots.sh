#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
phone_sources="$root_dir/sources/phone"
tablet_sources="$root_dir/sources/tablet-unfolded"
phone_output="$root_dir/screenshots/phone"
tablet_7_output="$root_dir/screenshots/tablet-7"
tablet_10_output="$root_dir/screenshots/tablet-10"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT

command -v magick >/dev/null || {
  echo "ImageMagick 7 (magick) is required." >&2
  exit 1
}

mkdir -p "$phone_output" "$tablet_7_output" "$tablet_10_output"

# Keep the phone app bar and session tabs visible. Cropping from the bottom removes only
# excess keyboard/blank space and preserves the source pixels at their native 1080px width.
phone_shot() {
  local source="$1"
  local destination="$2"
  magick "$source" -crop 1080x1920+0+0 +repage -strip "PNG24:$destination"
}

phone_shot "$phone_sources/terminal-colour.jpg" "$phone_output/01-colour-terminal.png"
phone_shot "$phone_sources/tmux-session-picker.jpg" "$phone_output/02-tmux-session-picker.png"
phone_shot "$phone_sources/connections-dark.jpg" "$phone_output/03-connections.png"
phone_shot "$phone_sources/settings-dark.jpg" "$phone_output/04-settings.png"
phone_shot "$phone_sources/add-host-mosh.jpg" "$phone_output/05-add-host.png"

# The supplied unfolded-device captures are close to square. Preserve every UI pixel inside a
# Play-compliant 16:9 canvas, using a darkened blur of the same capture as non-semantic fill.
tablet_shot() {
  local source="$1"
  local destination="$2"
  local stem
  stem="$(basename "$destination" .png)"

  magick "$source" \
    -resize '1920x1080^' -gravity center -crop 1920x1080+0+0 +repage \
    -blur 0x34 -modulate 48,112,100 "$work_dir/$stem-background.png"
  magick "$source" -resize x1000 -bordercolor '#72e0d0' -border 2x2 \
    "$work_dir/$stem-foreground.png"
  magick "$work_dir/$stem-background.png" \
    \( "$work_dir/$stem-foreground.png" -background '#000000' -shadow 55x14+0+12 \) \
    -gravity center -compose over -composite \
    "$work_dir/$stem-foreground.png" -gravity center -compose over -composite \
    -strip "PNG24:$destination"
}

# Lead with a readable, edge-to-edge terminal detail. This is a crop, not reconstructed UI.
tablet_terminal_lead() {
  local source="$1"
  local destination="$2"
  magick "$source" -crop 2256x1269+0+0 +repage -resize 1920x1080! \
    -strip "PNG24:$destination"
}

build_tablet_set() {
  local output_dir="$1"
  tablet_terminal_lead "$tablet_sources/terminal-colour.jpg" "$output_dir/01-colour-terminal.png"
  tablet_shot "$tablet_sources/terminal-input.jpg" "$output_dir/02-terminal-input.png"
  tablet_shot "$tablet_sources/tmux-session-picker.jpg" "$output_dir/03-tmux-session-picker.png"
  tablet_shot "$tablet_sources/connections.jpg" "$output_dir/04-connections.png"
  tablet_shot "$tablet_sources/settings.jpg" "$output_dir/05-settings.png"
}

build_tablet_set "$tablet_7_output"
build_tablet_set "$tablet_10_output"

echo "Built five phone screenshots and five screenshots for each tablet class."
