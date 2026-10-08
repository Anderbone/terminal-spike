#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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

# English marketing artwork uses real, privacy-reviewed S23 captures and short headlines.
python3 "$root_dir/build-phone-artwork.py"

command -v rsvg-convert >/dev/null || {
  echo "librsvg (rsvg-convert) is required." >&2
  exit 1
}
rsvg-convert "$root_dir/graphics/feature-graphic.svg" -o "$work_dir/feature-graphic.png"
magick "$work_dir/feature-graphic.png" -strip "PNG24:$root_dir/graphics/feature-graphic-1024x500.png"

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

# Review artifact only: never upload this collage as an app screenshot.
magick montage "$phone_output"/*.png -thumbnail 270x480 -tile 4x2 \
  -geometry +12+12 -background '#0b1110' "$root_dir/preview-phone.jpg"

echo "Built phone artwork, feature graphic, and five screenshots for each tablet class."
