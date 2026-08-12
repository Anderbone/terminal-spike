status: complete
created_at: 2026-08-08T11:14:08.829Z
updated_at: 2026-08-10T04:48:21+01:00
done_at: 2026-08-10T04:48:21+01:00
independent: no
dependencies: docs/polish-implementation-plan.md

# Terminal palette and font licensing

## Decisions

- Named presets are project-authored terminal mappings. The names and colour specifications retain
  their upstream provenance, but no upstream UI, logo, screenshot, executable code, or binary theme
  asset is packaged.
- Gruvbox Dark remains included. The immutable upstream 2.0.0 source does not contain a standalone
  licence file, but both its `package.json` and README authoritatively declare MIT/MIT-X11 and the
  package identifies Pavel Pertsev as author. The packaged notice records that exception without
  inventing a dated copyright line and reproduces the standard MIT terms.
- A new named preset is not release-cleared until its immutable input, checksum, licence evidence,
  attribution, packaged notice, and exact catalogue ID have been reviewed together.

## Palette decisions

Use names only to identify presets, copy no logos/assets, and keep the adapted Android ARGB values
as project source. These immutable inputs and hashes are also recorded in `docs/DEPENDENCIES.md`.

| Preset | Immutable palette input | Licence evidence and release decision |
|---|---|---|
| Ayu Dark | Exact terminal input: stable alacritty-themes 6.0.2 commit [`809b6382`](https://github.com/rajasegar/alacritty-themes/blob/809b638240a28b9ccfc0ba715c113ea7f3b92145/themes/Ayu-Dark.toml), file SHA-256 `2feb7a7b20eae601b2a30aebd443bf189b487d0b7ace1ef28bf77f0d182ffd83`. Original Ayu palette: stable 8.0.1 commit [`60fdf5d3`](https://github.com/ayu-theme/ayu-colors/tree/60fdf5d39c5ef36c081b081c1fb90b5e7cc24f4d), `src/dark.ts` SHA-256 `adae149f592a7a1084f9d5c26037740bb9ca6d6d71993740819a607f31230056`. | MIT, Konstantin Pschera and Rajasegar Chandran. Licence-file SHA-256 values `0e516038e9348d278dd886a2f07826b78e851f6e5f002c62096529d508c55be1` and `a0aefd3047cedabe2025d2c792ccfa213f17cfe8809f3280c30400bbcc8c4e39`. Cleared with both notices. |
| One Dark | Stable 1.8.4 commit [`9c96f445`](https://github.com/atom/one-dark-syntax/blob/9c96f4454362267ac45322063e193ccf9d2debb1/styles/colors.less), file SHA-256 `3f060ae1d0f2ebb27ccb60cf5088bd68ddaa8f38288569bf4ac47015b4595438`. | MIT, Copyright 2016 GitHub Inc.; licence SHA-256 `e1bd6bab503e4d7990504df1e646f6e7465a9096648304335e4c0b55c88f1f54`. Cleared as “One Dark”; no “One Dark Pro” asset or claim. |
| Dracula | Official iTerm commit [`e5749bcd`](https://github.com/dracula/iterm/blob/e5749bcd07764ad6e154038892b118ea45f88cc0/Dracula.itermcolors), as pinned by the official theme collection; file SHA-256 `5fefc1d659b2020803e66224d33364b853ec52a54d209ef53c3d7c9e2127fd20`. | MIT, Copyright 2013-present Dracula Theme; licence SHA-256 `21ab5b51be129196203d9e9cd1a2d51451fe913bc972fc1462a2fe11633692a1`. Cleared without PRO assets or endorsement. |
| Nord | Commit [`1cef7160`](https://github.com/nordtheme/nord/blob/1cef71605416a222e57225b544540ce0fcec18d4/src/nord.css), file SHA-256 `b931ac3732582b2066b2d6cadec02d9820ba7081e6e3e404c31cb62d9315a962`. | MIT, Copyright 2016-present Sven Greb; licence SHA-256 `25ac8188d670bd2ad2ce2f4f55ab88573010ee9f7a4502543cb1eea1e2274f8a`. Cleared. |
| Solarized Dark/Light | Commit [`62f656a0`](https://github.com/altercation/solarized/tree/62f656a02f93c5190a8753159e34b385588d5ff3/iterm2-colors-solarized), dark/light iTerm file SHA-256 values `b17fd6ccd78663088e3c396a3cdc894e41e2db5acad2f51f6c6114506c33fb2c` and `8c6875470be3038f96ceacd89c2ba60f02b0395595d93556547b0707f91ddfc2`. | MIT, Copyright 2011 Ethan Schoonover; licence SHA-256 `494aefdabf86acce06bd63001ad8aedad4ee38da23509d3f917d95aa3368b9a6`. Both variants cleared from the same pinned source. |
| Gruvbox Dark | Stable 2.0.0 commit [`5d15b276`](https://github.com/morhetz/gruvbox/blob/5d15b2765f59754d7ac263c88a0f6e3e58124951/colors/gruvbox.vim), file SHA-256 `55116926ba2b625837d9ae89349a5688d60d0b32acdbd8887e1c0d225f079c3d`. | Upstream `package.json` (SHA-256 `394c454f753fd4d1dbfe20248bdc56aeb1e9edd9866f6f5e7b786de4b1b2b958`) declares author Pavel Pertsev and MIT; README (SHA-256 `9abbac70c04ff250b4f19e5591dc6362008e253187c8991acf9ceefac3f3ea18`) declares MIT/X11. Cleared under the documented no-standalone-file exception with full terms packaged. |
| Tokyo Night | Stable 1.1.2 commit [`7c0f11ea`](https://github.com/enkia/tokyo-night-vscode-theme/blob/7c0f11eaef322f293621ca7befe462214b7ea468/tokyo-night.itermcolors), file SHA-256 `7d8285ff85a4dd10404981c3331ae139056c3e24930a068d4966122c8e1f3071`. | MIT, Copyright 2018-present Enkia; licence SHA-256 `e3f5d0d772cda0f4f67405696f15a0f335460a2b3944eff40f00675aadacb31b`. Cleared. |
| Catppuccin Mocha | Official Alacritty commit [`f6cb5a5c`](https://github.com/catppuccin/alacritty/blob/f6cb5a5c2b404cdaceaff193b9c52317f62c62f7/catppuccin-mocha.toml), file SHA-256 `949cef17c3ec50420b82c70b22e3aa2ea0a5d27dfcc13284192bd5284b5db2c5`; generated from palette 1.8.0 commit [`07d02aa1`](https://github.com/catppuccin/palette/blob/07d02aa110ef9eb7e7427afca5c73ba9cf7f8ebd/palette.json), file SHA-256 `4bc114bb6b3c9a9c9e156564aa84625aef32c5da514d9dd431cf1fcad433a05f`. | MIT, Copyright 2021 Catppuccin; licence SHA-256 `814096d2c34cc216c624738a49356f32b7237733b4f7edb0685f4e50ef5074ba`. Cleared. |
| High Contrast / Custom / Current | Project-owned source mapping. | No third-party palette asset or additional notice. Cleared. |

## Text font decisions

Prefer unmodified stable-release binaries. Subsetting/modification creates an OFL derivative and can trigger reserved-font-name renaming, so do not subset merely to save APK size without a separate review.

| Font | Canonical source | Licence / obligation | Decision |
|---|---|---|---|
| Source Code Pro | [Adobe stable release 2.042R-u/1.062R-i/1.026R-vf](https://github.com/adobe-fonts/source-code-pro/releases/tag/2.042R-u/1.062R-i/1.026R-vf) | OFL-1.1; reserved name “Source”; package full OFL/copyright | Unmodified static Regular/Bold TTFs bundled and hash-pinned |
| JetBrains Mono | [JetBrains stable release v2.304](https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304) | OFL-1.1; package full OFL/copyright | Unmodified static Regular/Bold TTFs bundled and hash-pinned |
| IBM Plex Mono | [IBM stable package @ibm/plex-mono@2.5.0](https://github.com/IBM/plex/releases/tag/%40ibm%2Fplex-mono%402.5.0) | OFL-1.1; reserved name “Plex”; package full OFL/copyright | Unmodified font-version-2.005 Regular/Bold TTFs bundled and hash-pinned |
| Cascadia Mono | [Microsoft stable release v2407.24](https://github.com/microsoft/cascadia-code/releases/tag/v2407.24) | OFL-1.1; reserved “Cascadia Code”; plain Mono is the no-ligature variant | Unmodified static Regular/Bold Mono TTFs bundled and hash-pinned |
| System monospace | Android platform | No bundled font binary | Always available |
| User custom TTF/OTF | SAF-selected user file | User-controlled local use; exclude from backup by default | Supported without redistribution claim |

OFL fonts may be embedded in a paid proprietary APK; the font remains OFL and its complete notice must be easily viewable. The app itself does not become OFL.

## Symbols Nerd Font Mono

Canonical source: [Nerd Fonts stable release v3.5.0](https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.5.0), source commit [`bbb2db23a131139161d66a9b526a6fdc79875c92`](https://github.com/ryanoasis/nerd-fonts/tree/bbb2db23a131139161d66a9b526a6fdc79875c92). The exact unmodified `SymbolsNerdFontMono-Regular.ttf` is bundled at 2,564,060 bytes with SHA-256 `2dc316f2505a0cbfbcf6060a1b4ba85b0a2974189e30c0037cdedc436a25a4ff`. It was extracted from `NerdFontsSymbolsOnly.tar.xz`, whose SHA-256 `b7ef2283462b435f1fe91d729dc412d5dbe34269dd2c7f4e1d803e4105c8d883` agrees with the upstream release checksum file.

The release archive's SymbolsOnly `LICENSE` contains only Ryan McIntyre's MIT notice. That is not a complete characterization of the compiled glyph font. The pinned Nerd Fonts [glyph inventory](https://github.com/ryanoasis/nerd-fonts/blob/bbb2db23a131139161d66a9b526a6fdc79875c92/src/glyphs/README.md) and [licence audit](https://github.com/ryanoasis/nerd-fonts/blob/bbb2db23a131139161d66a9b526a6fdc79875c92/license-audit.md) identify MIT, CC BY 4.0, Apache-2.0, OFL-1.1, and public-domain/Unlicense inputs. Inspection of the exact vendored glyph sources also requires the Bitstream Vera notice for Hack-derived `extraglyphs.sfd`.

The component inventory is reproduced in `THIRD_PARTY_NOTICES.md`, including creator/source/version, CC BY licence links and Nerd Fonts change statement, MIT notices, Apache text, OFL copyrights/RFNs/text, Unlicense text, Bitstream Vera terms, and trademark/non-endorsement language. One upstream table entry is stale: Nerd Fonts v3.5.0 says Font Logos 1.3.0, but its vendored font identifies as 1.4.0 and is byte-identical to the official [Font Logos v1.4.0](https://github.com/lukas-w/font-logos/releases/tag/v1.4.0) TTF (SHA-256 `25d0d6ba1c1ea4700c861c29f18022aa759079fc919b2baa01bd99f377712857`). The licence remains the Unlicense.

The Font Awesome Extension `v.0.0.3` tag predates its repository's owner-authored MIT `LICENCE` file. The later licence grant and the pinned Nerd Fonts audit both classify that component as MIT; the packaged notice records this chronology rather than implying that the old tag itself contained a licence file.

Do not describe the compiled Nerd Fonts binary as MIT-only. Brand and product-name glyphs remain trademarks of their owners; they are present only so remote terminal content can render them. No owner sponsors, endorses, or is affiliated with Terminal Spike, and the glyphs must not be used to imply otherwise.

## Repository and APK obligations

For every bundled asset:

1. Record immutable source revision, local filename, SHA-256, purpose, licence, and obligations in `docs/DEPENDENCIES.md`.
2. Add required copyright/licence/attribution text to `THIRD_PARTY_NOTICES.md`.
3. Package notices into the APK and expose them through Settings > About > Open-source licences.
4. Verify the exact bundled file is an upstream stable artifact and not alpha/beta/RC/nightly output.
5. Test light/dark previews, fixed cell geometry, bold/italic, CJK/emoji fallback, Powerline/Nerd symbols, R8/resource shrinking, and APK size.

All currently shipped named palettes are now provenance-verified, immutable-source-pinned, covered
by the packaged notices, and locked by the exact preset-ID inventory test. The four text families
and Symbols Nerd Font Mono are likewise provenance-verified, hash-pinned, and covered by packaged
notices; System monospace and private SAF-imported fonts add no redistributed binary. This asset
audit does not by itself prove unrelated renderer or broader release-readiness obligations.
