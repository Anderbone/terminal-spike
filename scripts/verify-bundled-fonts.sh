#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_root=$(cd -- "$script_dir/.." && pwd)
font_dir="$project_root/app/src/main/res/font"

expected_files=(
  cascadia_mono_bold.ttf
  cascadia_mono_regular.ttf
  ibm_plex_mono_bold.ttf
  ibm_plex_mono_regular.ttf
  jetbrains_mono_bold.ttf
  jetbrains_mono_regular.ttf
  source_code_pro_bold.ttf
  source_code_pro_regular.ttf
  symbols_nerd_font_mono_regular.ttf
)

mapfile -t actual_files < <(
  find "$font_dir" -maxdepth 1 -type f -name '*.ttf' -printf '%f\n' | LC_ALL=C sort
)

if [[ ${#actual_files[@]} -ne ${#expected_files[@]} ]]; then
  printf 'Expected %d bundled TTF files, found %d.\n' \
    "${#expected_files[@]}" "${#actual_files[@]}" >&2
  printf 'Actual files: %s\n' "${actual_files[*]:-(none)}" >&2
  exit 1
fi

for index in "${!expected_files[@]}"; do
  if [[ ${actual_files[$index]} != "${expected_files[$index]}" ]]; then
    printf 'Bundled TTF inventory differs from the reviewed manifest.\n' >&2
    printf 'Expected: %s\n' "${expected_files[*]}" >&2
    printf 'Actual:   %s\n' "${actual_files[*]}" >&2
    exit 1
  fi
done

cd -- "$project_root"
sha256sum --check --strict <<'CHECKSUMS'
b22cb603ed23cac36e8444846e1841caca21719a5e852780a8d37ae7a49b0a36  app/src/main/res/font/cascadia_mono_bold.ttf
06520d032ec274fa5040b22c6f4a1d829081b24ba40b2da56dae89bf10c7b481  app/src/main/res/font/cascadia_mono_regular.ttf
74e5eedcfa4596497d34e19023cabdabd3a8c852b903007a5654a59591a72ffb  app/src/main/res/font/ibm_plex_mono_bold.ttf
7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43  app/src/main/res/font/ibm_plex_mono_regular.ttf
5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb  app/src/main/res/font/jetbrains_mono_bold.ttf
a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f  app/src/main/res/font/jetbrains_mono_regular.ttf
b2095e0d657e6d28dc32444a9dacabab0c9241d0bf39d96371756cc9bdbc3a5f  app/src/main/res/font/source_code_pro_bold.ttf
74bd80d3e42a08517cd7e1108ba3d86f2da29ac0f3065be95e0357956ab9db37  app/src/main/res/font/source_code_pro_regular.ttf
2dc316f2505a0cbfbcf6060a1b4ba85b0a2974189e30c0037cdedc436a25a4ff  app/src/main/res/font/symbols_nerd_font_mono_regular.ttf
CHECKSUMS

printf 'Verified %d bundled font binaries.\n' "${#expected_files[@]}"
