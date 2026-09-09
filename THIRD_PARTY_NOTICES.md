# Third-party notices

## Local Arch runtime source components

Local Arch implementation now includes checksum-verified upstream source archives
for PRoot 5.1.107.81, talloc 2.4.3 and libandroid-shmem 0.7. Their Android native
binaries are built and packaged in the application. Exact sources, digests and versions are
listed in `local-arch-runtime/sources.lock` and the source archives are under
`local-arch-runtime/third_party/distfiles`.

PRoot source retains its contributor copyright notices and GPL-2.0-or-later grants;
the complete GPL version 2 is in `local-arch-runtime/licenses/GPL-2.0.txt` and in
the original archive. The talloc library retains Andrew Tridgell's, Stefan
Metzmacher's and other contributors' notices and LGPL-3.0-or-later grants; its
licence is in `local-arch-runtime/licenses/LGPL-3.0.txt`. The full source archives
retain all file-specific licences. libandroid-shmem retains copyright (c) 2013
Sergii Pylypenko and (c) 2017 Fredrik Fornwall; its BSD conditions and disclaimer
are in `local-arch-runtime/licenses/libandroid-shmem.txt` and its source archive.

These licence texts are packaged as application assets. PRoot combined with
LGPLv3 talloc is distributed under GPL-3.0-or-later using the source's later-version
grant; source-file notices remain unchanged. Integration patches, the build script
and replacement instructions are documented in `local-arch-runtime/README.md`.
Binary distribution must provide access to these exact corresponding source/build
inputs. This entry makes no written source offer on another party's behalf.

The original JNI PTY bridge statically links LLVM libc++ from Android NDK r29
`29.0.14206865`, under Apache-2.0 with LLVM exception. Exact r29 NDK and toolchain
notices are packaged as `ANDROID-NDK-R29-NOTICE` and `LLVM-TOOLCHAIN-NOTICE`.

## Existing application notices

Local Arch's TAR/XZ extraction uses Apache Commons Compress 1.28.0 and XZ for Java
1.12. Commons Compress and its runtime dependencies Commons Codec 1.19.0, Commons
IO 2.20.0 and Commons Lang 3.18.0 retain the Apache Software Foundation copyrights
and Apache-2.0 terms, plus embedded component notices. Their complete unmodified
JAR licence/notice files are packaged as `COMMONS-COMPRESS-LICENSE.txt`,
`COMMONS-COMPRESS-NOTICE.txt`, `COMMONS-CODEC-LICENSE.txt`, `COMMONS-CODEC-NOTICE.txt`,
`COMMONS-IO-LICENSE.txt`, `COMMONS-IO-NOTICE.txt`, `COMMONS-LANG3-LICENSE.txt` and
`COMMONS-LANG3-NOTICE.txt`. XZ for Java retains its source distribution's attribution
and 0BSD terms in packaged `XZ-JAVA-COPYING` and `XZ-JAVA-0BSD.txt`.

Copyright 2026 Terminal Spike contributors. Terminal Spike's project-owned source is free
software under GPL-3.0-or-later, except where a component supplies its own licence. The `mosh-api`
module retains its Apache-2.0 licence in `mosh-api/LICENSE`. Third-party materials retain their
original licences and attribution. The full project GPL text is reproduced at the end of this file.

This project includes or resolves the following third-party software:

- Android Gradle Plugin, AndroidX Core, Activity, Lifecycle, Room, DataStore, ProfileInstaller, Compose Foundation, Compose UI, Compose Material 3, Compose tooling/testing, AndroidX Test, UI Automator, Macrobenchmark, and the Baseline Profile Gradle plugin — Apache License 2.0.
- Kotlin standard library, Kotlin Compose compiler plugin, Kotlin Symbol Processing, kotlinx.coroutines, and kotlinx.serialization — Apache License 2.0.
- JetBrains annotations, JSpecify annotations, and Guava's standalone `listenablefuture` compatibility artefact — Apache License 2.0.
- Protocol Buffers Kotlin Lite and compiler 4.32.1, and the Protocol Buffers Gradle plugin 0.9.5 — Revised BSD licences reproduced below.
- mwiede JSch 2.28.3, including its JZlib and jBCrypt portions — Revised BSD and ISC licences reproduced below.
- Bouncy Castle Provider 1.85 — Bouncy Castle Licence (MIT-style) reproduced below.
- Ayu Dark, One Dark, Dracula, Nord, Solarized Dark/Light, Gruvbox Dark, Tokyo Night, and Catppuccin Mocha named terminal palette inputs — MIT; immutable source details and attributions are reproduced below.
- Source Code Pro 2.042, JetBrains Mono 2.304, IBM Plex Mono 2.5.0/font 2.005, and Cascadia Mono 2407.24 — SIL Open Font License 1.1.
- Symbols Nerd Font Mono 3.5.0 — a mixed-licence compiled fallback font; component licences and attributions are reproduced below and must not be summarized as MIT-only.
- Gradle Wrapper — Apache License 2.0.
- JUnit 4 — Eclipse Public License 1.0.

Copyright remains with each upstream project and its contributors. The corresponding licence texts and notices supplied by upstream distributions must be preserved where the distribution terms require them. Detailed versions, purposes, and source locations are recorded in `docs/DEPENDENCIES.md`.

No source or binary from ConnectBot, Termux, Termius, LobiShell, libvterm or xterm.js is included.
The main APK includes the pinned Mosh native transport through the project-owned `mosh-core`
library. Mosh, Nettle and native Protocol Buffers notices follow below. Complete native licence
texts, author inventory, NDK notices and OCB terms are packaged alongside the main notices.

## Built-in Mosh transport

The built-in Mosh transport is distributed with Terminal Spike under GPL-3.0-or-later and
comes with **no warranty**. Complete corresponding source is in the unified Terminal Spike
repository. `scripts/create-source-bundle.sh` at the repository root produces the complete
source archive, including the application, API, native implementation and all build inputs.

### Mosh 1.4.0

Copyright 2012–2022 the Mosh authors, including Keith Winstein, Anders Kaseorg, Quentin Smith,
Richard Tibbetts, Keegan McAllister, John Hood, and contributors. Mosh is licensed under
GPL-3.0-or-later. Upstream also supplies an OpenSSL linking exception; this build retains that text
although it uses GNU Nettle instead of OpenSSL. The complete GPL text is packaged as
`MOSH-COPYING-GPL-3.0`; the upstream author list and OCB licence/patent grant are packaged as
`MOSH-AUTHORS` and `MOSH-OCB-LICENCE.html`.

Android/JNI adaptation files are Copyright 2026 Terminal Spike contributors, first modified on
2026-08-09, and licensed as part of this transport under GPL-3.0-or-later. Upstream archive files
are not edited in place.

Mosh is a registered trademark. Terminal Spike is not affiliated with or endorsed
by the Mosh project.

### GNU Nettle 3.10.2

Copyright the GNU Nettle authors, including Niels Möller and contributors. The AES implementation
is offered under LGPL-3.0-or-later or GPL-2.0-or-later; this GPL-3.0-or-later combined work selects a
GPL-compatible version. The supplied GPLv2, GPLv3 and LGPLv3 texts are packaged unchanged.

### Protocol Buffers 21.12

Copyright 2008 Google Inc. All rights reserved. Redistribution in source and binary forms, with or
without modification, is permitted subject to the three conditions and disclaimer in the packaged
`PROTOBUF-LICENSE`. Neither Google nor contributor names may endorse derived products without
specific prior written permission. The software is provided “AS IS”, without warranty.

### Android NDK, LLVM libc++, AndroidX, and Kotlin

Portions of LLVM libc++ are statically linked from the pinned Android NDK r29 toolchain. LLVM libc++,
AndroidX libraries, and Kotlin standard library are licensed under Apache License 2.0; libc++ also
uses the LLVM exception. The exact NDK and LLVM toolchain notices used for this build are packaged
as `ANDROID-NDK-R29-NOTICE` and `LLVM-TOOLCHAIN-NOTICE`. The extension dynamically uses Android's
platform `libz`; the NDK notice includes its zlib licence text.

## Named terminal palette inputs

Terminal Spike contains project-authored Android terminal mappings adapted from the following
named colour specifications. It packages no upstream theme binary, UI, logo, screenshot, or
executable code. Exact commits and SHA-256 values are recorded in `docs/DEPENDENCIES.md`.

- Ayu Dark: original palette by Konstantin Pschera, pinned from stable Ayu 8.0.1 commit
  [`60fdf5d3`](https://github.com/ayu-theme/ayu-colors/tree/60fdf5d39c5ef36c081b081c1fb90b5e7cc24f4d).
  The exact terminal colour arrangement is pinned from stable alacritty-themes 6.0.2 commit
  [`809b6382`](https://github.com/rajasegar/alacritty-themes/tree/809b638240a28b9ccfc0ba715c113ea7f3b92145),
  Copyright (c) 2020 Rajasegar Chandran. Both sources are MIT licensed.
- One Dark: Copyright 2016 GitHub Inc., stable one-dark-syntax 1.8.4 commit
  [`9c96f445`](https://github.com/atom/one-dark-syntax/tree/9c96f4454362267ac45322063e193ccf9d2debb1),
  MIT. This notice covers One Dark; no One Dark Pro asset is included.
- Dracula: Copyright (c) 2013-present Dracula Theme, official iTerm commit
  [`e5749bcd`](https://github.com/dracula/iterm/tree/e5749bcd07764ad6e154038892b118ea45f88cc0),
  MIT. No Dracula PRO asset or endorsement is included.
- Nord: Copyright (c) 2016-present Sven Greb, commit
  [`1cef7160`](https://github.com/nordtheme/nord/tree/1cef71605416a222e57225b544540ce0fcec18d4),
  MIT.
- Solarized Dark and Solarized Light: Copyright (c) 2011 Ethan Schoonover, commit
  [`62f656a0`](https://github.com/altercation/solarized/tree/62f656a02f93c5190a8753159e34b385588d5ff3),
  MIT.
- Gruvbox Dark: stable Gruvbox 2.0.0 commit
  [`5d15b276`](https://github.com/morhetz/gruvbox/tree/5d15b2765f59754d7ac263c88a0f6e3e58124951).
  That immutable source has no standalone licence file or dated copyright line. Its package metadata
  identifies Pavel Pertsev <morhetz@gmail.com> as author and declares `MIT`; its README declares
  `MIT/X11`. Those two upstream declarations are the licence evidence, and the standard MIT terms
  are reproduced below without inventing missing upstream metadata.
- Tokyo Night: Copyright (c) 2018-present Enkia, stable 1.1.2 commit
  [`7c0f11ea`](https://github.com/enkia/tokyo-night-vscode-theme/tree/7c0f11eaef322f293621ca7befe462214b7ea468),
  MIT.
- Catppuccin Mocha: Copyright (c) 2021 Catppuccin, official Alacritty commit
  [`f6cb5a5c`](https://github.com/catppuccin/alacritty/tree/f6cb5a5c2b404cdaceaff193b9c52317f62c62f7)
  generated from stable palette 1.8.0 commit
  [`07d02aa1`](https://github.com/catppuccin/palette/tree/07d02aa110ef9eb7e7427afca5c73ba9cf7f8ebd),
  MIT.

The following MIT terms apply to the palette sources above and their listed notices:

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the “Software”), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Bundled terminal text fonts

The following unmodified upstream font binaries are embedded in the main APK. The SIL Open Font
License 1.1 reproduced later in this file applies to the font software only; it does not place the
application or terminal output under the OFL.

- Source Code Pro Regular and Bold 2.042: © 2023 Adobe (<http://www.adobe.com/>), with Reserved
  Font Name “Source”. All Rights Reserved. Source is a trademark of Adobe in the United States
  and/or other countries.
- JetBrains Mono Regular and Bold 2.304: Copyright 2020 The JetBrains Mono Project Authors.
  JetBrains Mono is a trademark of JetBrains s.r.o.
- IBM Plex Mono Regular and Bold, package 2.5.0/font 2.005: Copyright © 2017 IBM Corp., with
  Reserved Font Name “Plex”. IBM Plex® is a trademark of IBM Corp.
- Cascadia Mono Regular and Bold 2407.24: Copyright © 2019–present Microsoft Corporation, with
  Reserved Font Name “Cascadia Code”. Cascadia Code is a trademark of the Microsoft group of
  companies.

Exact filenames, release assets, tag commits, sizes, and SHA-256 values are recorded in
`docs/DEPENDENCIES.md` and enforced by `scripts/verify-bundled-fonts.sh`.

## Symbols Nerd Font Mono 3.5.0

Terminal Spike redistributes the official, unmodified `SymbolsNerdFontMono-Regular.ttf` from Nerd
Fonts v3.5.0 as a fallback for symbols emitted by remote terminals. Its SymbolsOnly licence says
Copyright (c) 2014 Ryan L McIntyre; the font metadata says Copyright (c) 2016 Ryan McIntyre. The
SymbolsOnly release archive contains an MIT file, but the compiled font incorporates glyph inputs
under several licences. The complete relevant inventory is therefore recorded here.

| Glyph input | Upstream/version represented in Nerd Fonts v3.5.0 | Licence and attribution |
|---|---|---|
| Codicons | [Microsoft vscode-codicons](https://github.com/microsoft/vscode-codicons), 0.0.45 | CC BY 4.0; Microsoft |
| Devicons | [devicons/devicon](https://github.com/devicons/devicon), 2.17.0.custom | MIT; Copyright (c) 2015 konpa |
| Extra glyphs | [source-foundry/Hack](https://github.com/source-foundry/Hack), Hack 3.003-derived | MIT, Copyright (c) 2018 Source Foundry Authors; underlying Bitstream Vera terms and copyright also reproduced below |
| Font Awesome | [Font Awesome Free](https://github.com/FortAwesome/Font-Awesome), selected 6.5.1 icons plus the 7.3.1 solid volume icon | CC BY 4.0 for icon artwork; Copyright (c) Fonticons, Inc. The upstream desktop-font portions are also offered under OFL-1.1 with Reserved Font Name “Font Awesome” |
| Font Awesome Extension | [AndreLZGava/font-awesome-extension](https://github.com/AndreLZGava/font-awesome-extension), 0.0.3 | MIT; Copyright (c) 2017 André Luiz Gava. The 2016 `v.0.0.3` tag predates the repository’s owner-authored `LICENCE`; the later repository licence and Nerd Fonts audit both identify MIT |
| Font Logos | [lukas-w/font-logos](https://github.com/lukas-w/font-logos), 1.4.0 | Unlicense; Copyright (c) 2014–2026 Lukas W. Nerd Fonts’ v3.5.0 table says 1.3.0, but its vendored TTF identifies as and is byte-identical to the official 1.4.0 release TTF |
| Material Design Icons | [Pictogrammers MaterialDesign-Font](https://github.com/Templarian/MaterialDesign-Font), snapshot fetched 2022-10-06 | Pictogrammers Free License; desktop font and relevant icons under Apache License 2.0 |
| Octicons | [primer/octicons](https://github.com/primer/octicons), 18.3.0 | MIT; Copyright (c) 2023 GitHub Inc. |
| Seti and Nerd Fonts original source | [jesseweed/seti-ui](https://github.com/jesseweed/seti-ui), 0.8.1, plus Nerd Fonts changes | MIT; Copyright (c) 2014 Jesse Weed and Copyright (c) 2014 Ryan L McIntyre |
| Pomicons | [gabrielelana/pomicons](https://github.com/gabrielelana/pomicons), 1.001 | OFL-1.1; Copyright (c) 2021 Gabriele Lana, with Reserved Font Name “Pomicons”. The source font metadata additionally identifies Davide Bignotti as its designer and copyright holder |
| Powerline Extra Symbols | [ryanoasis/powerline-extra-symbols](https://github.com/ryanoasis/powerline-extra-symbols), Nerd Fonts-modified 1.200 | MIT; Copyright (c) 2016 Ryan L McIntyre |
| Powerline Symbols | [powerline/powerline](https://github.com/powerline/powerline), 1.000 | MIT; Copyright 2013 Kim Silkebækken and other contributors |
| IEC power symbols | [jloughry/Unicode](https://github.com/jloughry/Unicode), February 2015 font | MIT; Copyright (c) 2013 Joe Loughry |
| Weather Icons | [erikflowers/weather-icons](https://github.com/erikflowers/weather-icons), 2.0.10/font 1.100 | OFL-1.1; designed by Erik Flowers and Lukas Bischoff (v1 art) |

Nerd Fonts selected, re-encoded, normalized, scaled, and combined these glyphs into its symbol
font. Its pinned source records additional changes, including repairs to two Codicons, one Material
Design glyph, Powerline/Powerline Extra geometry, and the custom Font Awesome selection. Terminal
Spike has not changed the upstream compiled font; only the Android resource filename differs.

Codicons and Font Awesome icon artwork are provided under the
[Creative Commons Attribution 4.0 International licence](https://creativecommons.org/licenses/by/4.0/).
The preceding table supplies the creators, titles, versions, and source links; the paragraph above
describes the known changes. No upstream author or rights holder endorses Terminal Spike.

Brand and product glyphs, names, and logos remain trademarks of their respective owners and may
have use restrictions independent of copyright licences. They are included only to render text
chosen by a remote terminal. Their presence does not indicate sponsorship, endorsement, official
status, or affiliation. Do not use a brand glyph for anything other than referring to its owner,
company, product, or service.

### MIT licence for Nerd Fonts and MIT-licensed symbol inputs

The copyright notices to which the following licence applies are listed in the component table
above.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the “Software”), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

### Pictogrammers Free License notice

The Material Design icon collection is released as free, open source, and GPL friendly by the
Pictogrammers icon group. It may be used for commercial projects, open source projects, or other
purposes. The web and desktop fonts are distributed under Apache License 2.0. Some icons are
redistributed under Apache License 2.0; all other icons are either redistributed under their
respective licences or distributed under Apache License 2.0. The complete Apache License 2.0 is
reproduced later in this file. The vendored distribution contains no separate NOTICE file.

### Font Logos — Unlicense

This is free and unencumbered software released into the public domain.

Anyone is free to copy, modify, publish, use, compile, sell, or distribute this software, either
in source code form or as a compiled binary, for any purpose, commercial or non-commercial, and
by any means.

In jurisdictions that recognize copyright laws, the author or authors of this software dedicate
any and all copyright interest in the software to the public domain. We make this dedication for
the benefit of the public at large and to the detriment of our heirs and successors. We intend
this dedication to be an overt act of relinquishment in perpetuity of all present and future
rights to this software under copyright law.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

For more information, see <https://unlicense.org/>.

### Bitstream Vera licence for Hack-derived extra glyphs

Copyright (c) 2003 by Bitstream, Inc. All Rights Reserved. Bitstream Vera is a trademark of
Bitstream, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy of the fonts
accompanying this license (“Fonts”) and associated documentation files (the “Font Software”), to
reproduce and distribute the Font Software, including without limitation the rights to use, copy,
merge, publish, distribute, and/or sell copies of the Font Software, and to permit persons to whom
the Font Software is furnished to do so, subject to the following conditions:

The above copyright and trademark notices and this permission notice shall be included in all
copies of one or more of the Font Software typefaces.

The Font Software may be modified, altered, or added to, and in particular the designs of glyphs
or characters in the Fonts may be modified and additional glyphs or characters may be added to the
Fonts, only if the fonts are renamed to names not containing either the words “Bitstream” or the
word “Vera”.

This License becomes null and void to the extent applicable to Fonts or Font Software that has
been modified and is distributed under the “Bitstream Vera” names.

The Font Software may be sold as part of a larger software package but no copy of one or more of
the Font Software typefaces may be sold by itself.

THE FONT SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
BUT NOT LIMITED TO ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL BITSTREAM OR THE
GNOME FOUNDATION BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, INCLUDING ANY GENERAL,
SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM OTHER DEALINGS
IN THE FONT SOFTWARE.

Except as contained in this notice, the names of Gnome, the Gnome Foundation, and Bitstream Inc.
shall not be used in advertising or otherwise to promote the sale, use or other dealings in this
Font Software without prior written authorization from the Gnome Foundation or Bitstream Inc.,
respectively. For further information, contact: fonts at gnome dot org.

## SIL Open Font License 1.1

Version 1.1 - 26 February 2007

PREAMBLE

The goals of the Open Font License (OFL) are to stimulate worldwide development of collaborative
font projects, to support the font creation efforts of academic and linguistic communities, and
to provide a free and open framework in which fonts may be shared and improved in partnership with
others.

The OFL allows the licensed fonts to be used, studied, modified and redistributed freely as long
as they are not sold by themselves. The fonts, including any derivative works, can be bundled,
embedded, redistributed and/or sold with any software provided that any reserved names are not
used by derivative works. The fonts and derivatives, however, cannot be released under any other
type of license. The requirement for fonts to remain under this license does not apply to any
document created using the fonts or their derivatives.

DEFINITIONS

“Font Software” refers to the set of files released by the Copyright Holder(s) under this license
and clearly marked as such. This may include source files, build scripts and documentation.

“Reserved Font Name” refers to any names specified as such after the copyright statement(s).

“Original Version” refers to the collection of Font Software components as distributed by the
Copyright Holder(s).

“Modified Version” refers to any derivative made by adding to, deleting, or substituting — in part
or in whole — any of the components of the Original Version, by changing formats or by porting the
Font Software to a new environment.

“Author” refers to any designer, engineer, programmer, technical writer or other person who
contributed to the Font Software.

PERMISSION & CONDITIONS

Permission is hereby granted, free of charge, to any person obtaining a copy of the Font Software,
to use, study, copy, merge, embed, modify, redistribute, and sell modified and unmodified copies of
the Font Software, subject to the following conditions:

1. Neither the Font Software nor any of its individual components, in Original or Modified
   Versions, may be sold by itself.
2. Original or Modified Versions of the Font Software may be bundled, redistributed and/or sold
   with any software, provided that each copy contains the above copyright notice and this
   license. These can be included either as stand-alone text files, human-readable headers or in
   the appropriate machine-readable metadata fields within text or binary files as long as those
   fields can be easily viewed by the user.
3. No Modified Version of the Font Software may use the Reserved Font Name(s) unless explicit
   written permission is granted by the corresponding Copyright Holder. This restriction only
   applies to the primary font name as presented to the users.
4. The name(s) of the Copyright Holder(s) or the Author(s) of the Font Software shall not be used
   to promote, endorse or advertise any Modified Version, except to acknowledge the contribution(s)
   of the Copyright Holder(s) and the Author(s) or with their explicit written permission.
5. The Font Software, modified or unmodified, in part or in whole, must be distributed entirely
   under this license, and must not be distributed under any other license. The requirement for
   fonts to remain under this license does not apply to any document created using the Font
   Software.

TERMINATION

This license becomes null and void if any of the above conditions are not met.

DISCLAIMER

THE FONT SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
BUT NOT LIMITED TO ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE COPYRIGHT
HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, INCLUDING ANY GENERAL, SPECIAL,
INDIRECT, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM OTHER DEALINGS
IN THE FONT SOFTWARE.

## Protocol Buffers

Copyright 2008 Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of Google Inc. nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS “AS IS” AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Code generated by the Protocol Buffer compiler is owned by the owner of the input file used when generating it. This code is not standalone and requires a support library to be linked with it. This support library is itself covered by the above licence.

## Protocol Buffers Gradle plugin

Original work copyright (c) 2015, Alex Antonov. All rights reserved.

Modified work copyright (c) 2015, Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS “AS IS” AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## JSch

Copyright (c) 2002-2015 Atsuhiko Yamanaka, JCraft,Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
3. The names of the authors may not be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED “AS IS” AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JCRAFT, INC. OR ANY CONTRIBUTORS TO THIS SOFTWARE BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## JZlib portion

Copyright (c) 2000-2011 ymnk, JCraft,Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
3. The names of the authors may not be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED “AS IS” AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JCRAFT, INC. OR ANY CONTRIBUTORS TO THIS SOFTWARE BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## jBCrypt portion

Copyright (c) 2006 Damien Miller <djm@mindrot.org>

Permission to use, copy, modify, and distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED “AS IS” AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.

## Bouncy Castle Provider

Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Apache License 2.0

Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.

"License" shall mean the terms and conditions for use, reproduction, and distribution as defined by Sections 1 through 9 of this document.

"Licensor" shall mean the copyright owner or entity authorized by the copyright owner that is granting the License.

"Legal Entity" shall mean the union of the acting entity and all other entities that control, are controlled by, or are under common control with that entity. For the purposes of this definition, "control" means (i) the power, direct or indirect, to cause the direction or management of such entity, whether by contract or otherwise, or (ii) ownership of fifty percent (50%) or more of the outstanding shares, or (iii) beneficial ownership of such entity.

"You" (or "Your") shall mean an individual or Legal Entity exercising permissions granted by this License.

"Source" form shall mean the preferred form for making modifications, including but not limited to software source code, documentation source, and configuration files.

"Object" form shall mean any form resulting from mechanical transformation or translation of a Source form, including but not limited to compiled object code, generated documentation, and conversions to other media types.

"Work" shall mean the work of authorship, whether in Source or Object form, made available under the License, as indicated by a copyright notice that is included in or attached to the work (an example is provided in the Appendix below).

"Derivative Works" shall mean any work, whether in Source or Object form, that is based on (or derived from) the Work and for which the editorial revisions, annotations, elaborations, or other modifications represent, as a whole, an original work of authorship. For the purposes of this License, Derivative Works shall not include works that remain separable from, or merely link (or bind by name) to the interfaces of the Work and Derivative Works thereof.

"Contribution" shall mean any work of authorship, including the original version of the Work and any modifications or additions to that Work or Derivative Works thereof, that is intentionally submitted to Licensor for inclusion in the Work by the copyright owner or by an individual or Legal Entity authorized to submit on behalf of the copyright owner. For the purposes of this definition, "submitted" means any form of electronic, verbal, or written communication sent to the Licensor or its representatives, including but not limited to communication on electronic mailing lists, source code control systems, and issue tracking systems that are managed by, or on behalf of, the Licensor for the purpose of discussing and improving the Work, but excluding communication that is conspicuously marked or otherwise designated in writing by the copyright owner as "Not a Contribution."

"Contributor" shall mean Licensor and any individual or Legal Entity on behalf of whom a Contribution has been received by Licensor and subsequently incorporated within the Work.

2. Grant of Copyright License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare Derivative Works of, publicly display, publicly perform, sublicense, and distribute the Work and such Derivative Works in Source or Object form.

3. Grant of Patent License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable (except as stated in this section) patent license to make, have made, use, offer to sell, sell, import, and otherwise transfer the Work, where such license applies only to those patent claims licensable by such Contributor that are necessarily infringed by their Contribution(s) alone or by combination of their Contribution(s) with the Work to which such Contribution(s) was submitted. If You institute patent litigation against any entity (including a cross-claim or counterclaim in a lawsuit) alleging that the Work or a Contribution incorporated within the Work constitutes direct or contributory patent infringement, then any patent licenses granted to You under this License for that Work shall terminate as of the date such litigation is filed.

4. Redistribution. You may reproduce and distribute copies of the Work or Derivative Works thereof in any medium, with or without modifications, and in Source or Object form, provided that You meet the following conditions:

     (a) You must give any other recipients of the Work or Derivative Works a copy of this License; and

     (b) You must cause any modified files to carry prominent notices stating that You changed the files; and

     (c) You must retain, in the Source form of any Derivative Works that You distribute, all copyright, patent, trademark, and attribution notices from the Source form of the Work, excluding those notices that do not pertain to any part of the Derivative Works; and

     (d) If the Work includes a "NOTICE" text file as part of its distribution, then any Derivative Works that You distribute must include a readable copy of the attribution notices contained within such NOTICE file, excluding those notices that do not pertain to any part of the Derivative Works, in at least one of the following places: within a NOTICE text file distributed as part of the Derivative Works; within the Source form or documentation, if provided along with the Derivative Works; or, within a display generated by the Derivative Works, if and wherever such third-party notices normally appear. The contents of the NOTICE file are for informational purposes only and do not modify the License. You may add Your own attribution notices within Derivative Works that You distribute, alongside or as an addendum to the NOTICE text from the Work, provided that such additional attribution notices cannot be construed as modifying the License.

     You may add Your own copyright statement to Your modifications and may provide additional or different license terms and conditions for use, reproduction, or distribution of Your modifications, or for any such Derivative Works as a whole, provided that Your use, reproduction, and distribution of the Work otherwise complies with the conditions stated in this License.

5. Submission of Contributions. Unless You explicitly state otherwise, any Contribution intentionally submitted for inclusion in the Work by You to the Licensor shall be under the terms and conditions of this License, without any additional terms or conditions. Notwithstanding the above, nothing herein shall supersede or modify the terms of any separate license agreement you may have executed with Licensor regarding such Contributions.

6. Trademarks. This License does not grant permission to use the trade names, trademarks, service marks, or product names of the Licensor, except as required for reasonable and customary use in describing the origin of the Work and reproducing the content of the NOTICE file.

7. Disclaimer of Warranty. Unless required by applicable law or agreed to in writing, Licensor provides the Work (and each Contributor provides its Contributions) on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied, including, without limitation, any warranties or conditions of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE. You are solely responsible for determining the appropriateness of using or redistributing the Work and assume any risks associated with Your exercise of permissions under this License.

8. Limitation of Liability. In no event and under no legal theory, whether in tort (including negligence), contract, or otherwise, unless required by applicable law (such as deliberate and grossly negligent acts) or agreed to in writing, shall any Contributor be liable to You for damages, including any direct, indirect, special, incidental, or consequential damages of any character arising as a result of this License or out of the use or inability to use the Work (including but not limited to damages for loss of goodwill, work stoppage, computer failure or malfunction, or any and all other commercial damages or losses), even if such Contributor has been advised of the possibility of such damages.

9. Accepting Warranty or Additional Liability. While redistributing the Work or Derivative Works thereof, You may choose to offer, and charge a fee for, acceptance of support, warranty, indemnity, or other liability obligations and/or rights consistent with this License. However, in accepting such obligations, You may act only on Your own behalf and on Your sole responsibility, not on behalf of any other Contributor, and only if You agree to indemnify, defend, and hold each Contributor harmless for any liability incurred by, or claims asserted against, such Contributor by reason of your accepting any warranty or additional liability.

END OF TERMS AND CONDITIONS

APPENDIX: How to apply the Apache License to your work.

To apply the Apache License to your work, attach the following boilerplate notice, with the fields enclosed by brackets "[]" replaced with your own identifying information. (Don't include the brackets!) The text should be enclosed in the appropriate comment syntax for the file format. We also recommend that a file or class name and description of purpose be included on the same "printed page" as the copyright notice for easier identification within third-party archives.

Copyright [yyyy] [name of copyright owner]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

## Terminal Spike — GNU General Public License

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.

Source: https://github.com/Anderbone/terminal-spike

                    GNU GENERAL PUBLIC LICENSE
                       Version 3, 29 June 2007

 Copyright (C) 2007 Free Software Foundation, Inc. <http://fsf.org/>
 Everyone is permitted to copy and distribute verbatim copies
 of this license document, but changing it is not allowed.

                            Preamble

  The GNU General Public License is a free, copyleft license for
software and other kinds of works.

  The licenses for most software and other practical works are designed
to take away your freedom to share and change the works.  By contrast,
the GNU General Public License is intended to guarantee your freedom to
share and change all versions of a program--to make sure it remains free
software for all its users.  We, the Free Software Foundation, use the
GNU General Public License for most of our software; it applies also to
any other work released this way by its authors.  You can apply it to
your programs, too.

  When we speak of free software, we are referring to freedom, not
price.  Our General Public Licenses are designed to make sure that you
have the freedom to distribute copies of free software (and charge for
them if you wish), that you receive source code or can get it if you
want it, that you can change the software or use pieces of it in new
free programs, and that you know you can do these things.

  To protect your rights, we need to prevent others from denying you
these rights or asking you to surrender the rights.  Therefore, you have
certain responsibilities if you distribute copies of the software, or if
you modify it: responsibilities to respect the freedom of others.

  For example, if you distribute copies of such a program, whether
gratis or for a fee, you must pass on to the recipients the same
freedoms that you received.  You must make sure that they, too, receive
or can get the source code.  And you must show them these terms so they
know their rights.

  Developers that use the GNU GPL protect your rights with two steps:
(1) assert copyright on the software, and (2) offer you this License
giving you legal permission to copy, distribute and/or modify it.

  For the developers' and authors' protection, the GPL clearly explains
that there is no warranty for this free software.  For both users' and
authors' sake, the GPL requires that modified versions be marked as
changed, so that their problems will not be attributed erroneously to
authors of previous versions.

  Some devices are designed to deny users access to install or run
modified versions of the software inside them, although the manufacturer
can do so.  This is fundamentally incompatible with the aim of
protecting users' freedom to change the software.  The systematic
pattern of such abuse occurs in the area of products for individuals to
use, which is precisely where it is most unacceptable.  Therefore, we
have designed this version of the GPL to prohibit the practice for those
products.  If such problems arise substantially in other domains, we
stand ready to extend this provision to those domains in future versions
of the GPL, as needed to protect the freedom of users.

  Finally, every program is threatened constantly by software patents.
States should not allow patents to restrict development and use of
software on general-purpose computers, but in those that do, we wish to
avoid the special danger that patents applied to a free program could
make it effectively proprietary.  To prevent this, the GPL assures that
patents cannot be used to render the program non-free.

  The precise terms and conditions for copying, distribution and
modification follow.

                       TERMS AND CONDITIONS

  0. Definitions.

  "This License" refers to version 3 of the GNU General Public License.

  "Copyright" also means copyright-like laws that apply to other kinds of
works, such as semiconductor masks.

  "The Program" refers to any copyrightable work licensed under this
License.  Each licensee is addressed as "you".  "Licensees" and
"recipients" may be individuals or organizations.

  To "modify" a work means to copy from or adapt all or part of the work
in a fashion requiring copyright permission, other than the making of an
exact copy.  The resulting work is called a "modified version" of the
earlier work or a work "based on" the earlier work.

  A "covered work" means either the unmodified Program or a work based
on the Program.

  To "propagate" a work means to do anything with it that, without
permission, would make you directly or secondarily liable for
infringement under applicable copyright law, except executing it on a
computer or modifying a private copy.  Propagation includes copying,
distribution (with or without modification), making available to the
public, and in some countries other activities as well.

  To "convey" a work means any kind of propagation that enables other
parties to make or receive copies.  Mere interaction with a user through
a computer network, with no transfer of a copy, is not conveying.

  An interactive user interface displays "Appropriate Legal Notices"
to the extent that it includes a convenient and prominently visible
feature that (1) displays an appropriate copyright notice, and (2)
tells the user that there is no warranty for the work (except to the
extent that warranties are provided), that licensees may convey the
work under this License, and how to view a copy of this License.  If
the interface presents a list of user commands or options, such as a
menu, a prominent item in the list meets this criterion.

  1. Source Code.

  The "source code" for a work means the preferred form of the work
for making modifications to it.  "Object code" means any non-source
form of a work.

  A "Standard Interface" means an interface that either is an official
standard defined by a recognized standards body, or, in the case of
interfaces specified for a particular programming language, one that
is widely used among developers working in that language.

  The "System Libraries" of an executable work include anything, other
than the work as a whole, that (a) is included in the normal form of
packaging a Major Component, but which is not part of that Major
Component, and (b) serves only to enable use of the work with that
Major Component, or to implement a Standard Interface for which an
implementation is available to the public in source code form.  A
"Major Component", in this context, means a major essential component
(kernel, window system, and so on) of the specific operating system
(if any) on which the executable work runs, or a compiler used to
produce the work, or an object code interpreter used to run it.

  The "Corresponding Source" for a work in object code form means all
the source code needed to generate, install, and (for an executable
work) run the object code and to modify the work, including scripts to
control those activities.  However, it does not include the work's
System Libraries, or general-purpose tools or generally available free
programs which are used unmodified in performing those activities but
which are not part of the work.  For example, Corresponding Source
includes interface definition files associated with source files for
the work, and the source code for shared libraries and dynamically
linked subprograms that the work is specifically designed to require,
such as by intimate data communication or control flow between those
subprograms and other parts of the work.

  The Corresponding Source need not include anything that users
can regenerate automatically from other parts of the Corresponding
Source.

  The Corresponding Source for a work in source code form is that
same work.

  2. Basic Permissions.

  All rights granted under this License are granted for the term of
copyright on the Program, and are irrevocable provided the stated
conditions are met.  This License explicitly affirms your unlimited
permission to run the unmodified Program.  The output from running a
covered work is covered by this License only if the output, given its
content, constitutes a covered work.  This License acknowledges your
rights of fair use or other equivalent, as provided by copyright law.

  You may make, run and propagate covered works that you do not
convey, without conditions so long as your license otherwise remains
in force.  You may convey covered works to others for the sole purpose
of having them make modifications exclusively for you, or provide you
with facilities for running those works, provided that you comply with
the terms of this License in conveying all material for which you do
not control copyright.  Those thus making or running the covered works
for you must do so exclusively on your behalf, under your direction
and control, on terms that prohibit them from making any copies of
your copyrighted material outside their relationship with you.

  Conveying under any other circumstances is permitted solely under
the conditions stated below.  Sublicensing is not allowed; section 10
makes it unnecessary.

  3. Protecting Users' Legal Rights From Anti-Circumvention Law.

  No covered work shall be deemed part of an effective technological
measure under any applicable law fulfilling obligations under article
11 of the WIPO copyright treaty adopted on 20 December 1996, or
similar laws prohibiting or restricting circumvention of such
measures.

  When you convey a covered work, you waive any legal power to forbid
circumvention of technological measures to the extent such circumvention
is effected by exercising rights under this License with respect to
the covered work, and you disclaim any intention to limit operation or
modification of the work as a means of enforcing, against the work's
users, your or third parties' legal rights to forbid circumvention of
technological measures.

  4. Conveying Verbatim Copies.

  You may convey verbatim copies of the Program's source code as you
receive it, in any medium, provided that you conspicuously and
appropriately publish on each copy an appropriate copyright notice;
keep intact all notices stating that this License and any
non-permissive terms added in accord with section 7 apply to the code;
keep intact all notices of the absence of any warranty; and give all
recipients a copy of this License along with the Program.

  You may charge any price or no price for each copy that you convey,
and you may offer support or warranty protection for a fee.

  5. Conveying Modified Source Versions.

  You may convey a work based on the Program, or the modifications to
produce it from the Program, in the form of source code under the
terms of section 4, provided that you also meet all of these conditions:

    a) The work must carry prominent notices stating that you modified
    it, and giving a relevant date.

    b) The work must carry prominent notices stating that it is
    released under this License and any conditions added under section
    7.  This requirement modifies the requirement in section 4 to
    "keep intact all notices".

    c) You must license the entire work, as a whole, under this
    License to anyone who comes into possession of a copy.  This
    License will therefore apply, along with any applicable section 7
    additional terms, to the whole of the work, and all its parts,
    regardless of how they are packaged.  This License gives no
    permission to license the work in any other way, but it does not
    invalidate such permission if you have separately received it.

    d) If the work has interactive user interfaces, each must display
    Appropriate Legal Notices; however, if the Program has interactive
    interfaces that do not display Appropriate Legal Notices, your
    work need not make them do so.

  A compilation of a covered work with other separate and independent
works, which are not by their nature extensions of the covered work,
and which are not combined with it such as to form a larger program,
in or on a volume of a storage or distribution medium, is called an
"aggregate" if the compilation and its resulting copyright are not
used to limit the access or legal rights of the compilation's users
beyond what the individual works permit.  Inclusion of a covered work
in an aggregate does not cause this License to apply to the other
parts of the aggregate.

  6. Conveying Non-Source Forms.

  You may convey a covered work in object code form under the terms
of sections 4 and 5, provided that you also convey the
machine-readable Corresponding Source under the terms of this License,
in one of these ways:

    a) Convey the object code in, or embodied in, a physical product
    (including a physical distribution medium), accompanied by the
    Corresponding Source fixed on a durable physical medium
    customarily used for software interchange.

    b) Convey the object code in, or embodied in, a physical product
    (including a physical distribution medium), accompanied by a
    written offer, valid for at least three years and valid for as
    long as you offer spare parts or customer support for that product
    model, to give anyone who possesses the object code either (1) a
    copy of the Corresponding Source for all the software in the
    product that is covered by this License, on a durable physical
    medium customarily used for software interchange, for a price no
    more than your reasonable cost of physically performing this
    conveying of source, or (2) access to copy the
    Corresponding Source from a network server at no charge.

    c) Convey individual copies of the object code with a copy of the
    written offer to provide the Corresponding Source.  This
    alternative is allowed only occasionally and noncommercially, and
    only if you received the object code with such an offer, in accord
    with subsection 6b.

    d) Convey the object code by offering access from a designated
    place (gratis or for a charge), and offer equivalent access to the
    Corresponding Source in the same way through the same place at no
    further charge.  You need not require recipients to copy the
    Corresponding Source along with the object code.  If the place to
    copy the object code is a network server, the Corresponding Source
    may be on a different server (operated by you or a third party)
    that supports equivalent copying facilities, provided you maintain
    clear directions next to the object code saying where to find the
    Corresponding Source.  Regardless of what server hosts the
    Corresponding Source, you remain obligated to ensure that it is
    available for as long as needed to satisfy these requirements.

    e) Convey the object code using peer-to-peer transmission, provided
    you inform other peers where the object code and Corresponding
    Source of the work are being offered to the general public at no
    charge under subsection 6d.

  A separable portion of the object code, whose source code is excluded
from the Corresponding Source as a System Library, need not be
included in conveying the object code work.

  A "User Product" is either (1) a "consumer product", which means any
tangible personal property which is normally used for personal, family,
or household purposes, or (2) anything designed or sold for incorporation
into a dwelling.  In determining whether a product is a consumer product,
doubtful cases shall be resolved in favor of coverage.  For a particular
product received by a particular user, "normally used" refers to a
typical or common use of that class of product, regardless of the status
of the particular user or of the way in which the particular user
actually uses, or expects or is expected to use, the product.  A product
is a consumer product regardless of whether the product has substantial
commercial, industrial or non-consumer uses, unless such uses represent
the only significant mode of use of the product.

  "Installation Information" for a User Product means any methods,
procedures, authorization keys, or other information required to install
and execute modified versions of a covered work in that User Product from
a modified version of its Corresponding Source.  The information must
suffice to ensure that the continued functioning of the modified object
code is in no case prevented or interfered with solely because
modification has been made.

  If you convey an object code work under this section in, or with, or
specifically for use in, a User Product, and the conveying occurs as
part of a transaction in which the right of possession and use of the
User Product is transferred to the recipient in perpetuity or for a
fixed term (regardless of how the transaction is characterized), the
Corresponding Source conveyed under this section must be accompanied
by the Installation Information.  But this requirement does not apply
if neither you nor any third party retains the ability to install
modified object code on the User Product (for example, the work has
been installed in ROM).

  The requirement to provide Installation Information does not include a
requirement to continue to provide support service, warranty, or updates
for a work that has been modified or installed by the recipient, or for
the User Product in which it has been modified or installed.  Access to a
network may be denied when the modification itself materially and
adversely affects the operation of the network or violates the rules and
protocols for communication across the network.

  Corresponding Source conveyed, and Installation Information provided,
in accord with this section must be in a format that is publicly
documented (and with an implementation available to the public in
source code form), and must require no special password or key for
unpacking, reading or copying.

  7. Additional Terms.

  "Additional permissions" are terms that supplement the terms of this
License by making exceptions from one or more of its conditions.
Additional permissions that are applicable to the entire Program shall
be treated as though they were included in this License, to the extent
that they are valid under applicable law.  If additional permissions
apply only to part of the Program, that part may be used separately
under those permissions, but the entire Program remains governed by
this License without regard to the additional permissions.

  When you convey a copy of a covered work, you may at your option
remove any additional permissions from that copy, or from any part of
it.  (Additional permissions may be written to require their own
removal in certain cases when you modify the work.)  You may place
additional permissions on material, added by you to a covered work,
for which you have or can give appropriate copyright permission.

  Notwithstanding any other provision of this License, for material you
add to a covered work, you may (if authorized by the copyright holders of
that material) supplement the terms of this License with terms:

    a) Disclaiming warranty or limiting liability differently from the
    terms of sections 15 and 16 of this License; or

    b) Requiring preservation of specified reasonable legal notices or
    author attributions in that material or in the Appropriate Legal
    Notices displayed by works containing it; or

    c) Prohibiting misrepresentation of the origin of that material, or
    requiring that modified versions of such material be marked in
    reasonable ways as different from the original version; or

    d) Limiting the use for publicity purposes of names of licensors or
    authors of the material; or

    e) Declining to grant rights under trademark law for use of some
    trade names, trademarks, or service marks; or

    f) Requiring indemnification of licensors and authors of that
    material by anyone who conveys the material (or modified versions of
    it) with contractual assumptions of liability to the recipient, for
    any liability that these contractual assumptions directly impose on
    those licensors and authors.

  All other non-permissive additional terms are considered "further
restrictions" within the meaning of section 10.  If the Program as you
received it, or any part of it, contains a notice stating that it is
governed by this License along with a term that is a further
restriction, you may remove that term.  If a license document contains
a further restriction but permits relicensing or conveying under this
License, you may add to a covered work material governed by the terms
of that license document, provided that the further restriction does
not survive such relicensing or conveying.

  If you add terms to a covered work in accord with this section, you
must place, in the relevant source files, a statement of the
additional terms that apply to those files, or a notice indicating
where to find the applicable terms.

  Additional terms, permissive or non-permissive, may be stated in the
form of a separately written license, or stated as exceptions;
the above requirements apply either way.

  8. Termination.

  You may not propagate or modify a covered work except as expressly
provided under this License.  Any attempt otherwise to propagate or
modify it is void, and will automatically terminate your rights under
this License (including any patent licenses granted under the third
paragraph of section 11).

  However, if you cease all violation of this License, then your
license from a particular copyright holder is reinstated (a)
provisionally, unless and until the copyright holder explicitly and
finally terminates your license, and (b) permanently, if the copyright
holder fails to notify you of the violation by some reasonable means
prior to 60 days after the cessation.

  Moreover, your license from a particular copyright holder is
reinstated permanently if the copyright holder notifies you of the
violation by some reasonable means, this is the first time you have
received notice of violation of this License (for any work) from that
copyright holder, and you cure the violation prior to 30 days after
your receipt of the notice.

  Termination of your rights under this section does not terminate the
licenses of parties who have received copies or rights from you under
this License.  If your rights have been terminated and not permanently
reinstated, you do not qualify to receive new licenses for the same
material under section 10.

  9. Acceptance Not Required for Having Copies.

  You are not required to accept this License in order to receive or
run a copy of the Program.  Ancillary propagation of a covered work
occurring solely as a consequence of using peer-to-peer transmission
to receive a copy likewise does not require acceptance.  However,
nothing other than this License grants you permission to propagate or
modify any covered work.  These actions infringe copyright if you do
not accept this License.  Therefore, by modifying or propagating a
covered work, you indicate your acceptance of this License to do so.

  10. Automatic Licensing of Downstream Recipients.

  Each time you convey a covered work, the recipient automatically
receives a license from the original licensors, to run, modify and
propagate that work, subject to this License.  You are not responsible
for enforcing compliance by third parties with this License.

  An "entity transaction" is a transaction transferring control of an
organization, or substantially all assets of one, or subdividing an
organization, or merging organizations.  If propagation of a covered
work results from an entity transaction, each party to that
transaction who receives a copy of the work also receives whatever
licenses to the work the party's predecessor in interest had or could
give under the previous paragraph, plus a right to possession of the
Corresponding Source of the work from the predecessor in interest, if
the predecessor has it or can get it with reasonable efforts.

  You may not impose any further restrictions on the exercise of the
rights granted or affirmed under this License.  For example, you may
not impose a license fee, royalty, or other charge for exercise of
rights granted under this License, and you may not initiate litigation
(including a cross-claim or counterclaim in a lawsuit) alleging that
any patent claim is infringed by making, using, selling, offering for
sale, or importing the Program or any portion of it.

  11. Patents.

  A "contributor" is a copyright holder who authorizes use under this
License of the Program or a work on which the Program is based.  The
work thus licensed is called the contributor's "contributor version".

  A contributor's "essential patent claims" are all patent claims
owned or controlled by the contributor, whether already acquired or
hereafter acquired, that would be infringed by some manner, permitted
by this License, of making, using, or selling its contributor version,
but do not include claims that would be infringed only as a
consequence of further modification of the contributor version.  For
purposes of this definition, "control" includes the right to grant
patent sublicenses in a manner consistent with the requirements of
this License.

  Each contributor grants you a non-exclusive, worldwide, royalty-free
patent license under the contributor's essential patent claims, to
make, use, sell, offer for sale, import and otherwise run, modify and
propagate the contents of its contributor version.

  In the following three paragraphs, a "patent license" is any express
agreement or commitment, however denominated, not to enforce a patent
(such as an express permission to practice a patent or covenant not to
sue for patent infringement).  To "grant" such a patent license to a
party means to make such an agreement or commitment not to enforce a
patent against the party.

  If you convey a covered work, knowingly relying on a patent license,
and the Corresponding Source of the work is not available for anyone
to copy, free of charge and under the terms of this License, through a
publicly available network server or other readily accessible means,
then you must either (1) cause the Corresponding Source to be so
available, or (2) arrange to deprive yourself of the benefit of the
patent license for this particular work, or (3) arrange, in a manner
consistent with the requirements of this License, to extend the patent
license to downstream recipients.  "Knowingly relying" means you have
actual knowledge that, but for the patent license, your conveying the
covered work in a country, or your recipient's use of the covered work
in a country, would infringe one or more identifiable patents in that
country that you have reason to believe are valid.

  If, pursuant to or in connection with a single transaction or
arrangement, you convey, or propagate by procuring conveyance of, a
covered work, and grant a patent license to some of the parties
receiving the covered work authorizing them to use, propagate, modify
or convey a specific copy of the covered work, then the patent license
you grant is automatically extended to all recipients of the covered
work and works based on it.

  A patent license is "discriminatory" if it does not include within
the scope of its coverage, prohibits the exercise of, or is
conditioned on the non-exercise of one or more of the rights that are
specifically granted under this License.  You may not convey a covered
work if you are a party to an arrangement with a third party that is
in the business of distributing software, under which you make payment
to the third party based on the extent of your activity of conveying
the work, and under which the third party grants, to any of the
parties who would receive the covered work from you, a discriminatory
patent license (a) in connection with copies of the covered work
conveyed by you (or copies made from those copies), or (b) primarily
for and in connection with specific products or compilations that
contain the covered work, unless you entered into that arrangement,
or that patent license was granted, prior to 28 March 2007.

  Nothing in this License shall be construed as excluding or limiting
any implied license or other defenses to infringement that may
otherwise be available to you under applicable patent law.

  12. No Surrender of Others' Freedom.

  If conditions are imposed on you (whether by court order, agreement or
otherwise) that contradict the conditions of this License, they do not
excuse you from the conditions of this License.  If you cannot convey a
covered work so as to satisfy simultaneously your obligations under this
License and any other pertinent obligations, then as a consequence you may
not convey it at all.  For example, if you agree to terms that obligate you
to collect a royalty for further conveying from those to whom you convey
the Program, the only way you could satisfy both those terms and this
License would be to refrain entirely from conveying the Program.

  13. Use with the GNU Affero General Public License.

  Notwithstanding any other provision of this License, you have
permission to link or combine any covered work with a work licensed
under version 3 of the GNU Affero General Public License into a single
combined work, and to convey the resulting work.  The terms of this
License will continue to apply to the part which is the covered work,
but the special requirements of the GNU Affero General Public License,
section 13, concerning interaction through a network will apply to the
combination as such.

  14. Revised Versions of this License.

  The Free Software Foundation may publish revised and/or new versions of
the GNU General Public License from time to time.  Such new versions will
be similar in spirit to the present version, but may differ in detail to
address new problems or concerns.

  Each version is given a distinguishing version number.  If the
Program specifies that a certain numbered version of the GNU General
Public License "or any later version" applies to it, you have the
option of following the terms and conditions either of that numbered
version or of any later version published by the Free Software
Foundation.  If the Program does not specify a version number of the
GNU General Public License, you may choose any version ever published
by the Free Software Foundation.

  If the Program specifies that a proxy can decide which future
versions of the GNU General Public License can be used, that proxy's
public statement of acceptance of a version permanently authorizes you
to choose that version for the Program.

  Later license versions may give you additional or different
permissions.  However, no additional obligations are imposed on any
author or copyright holder as a result of your choosing to follow a
later version.

  15. Disclaimer of Warranty.

  THERE IS NO WARRANTY FOR THE PROGRAM, TO THE EXTENT PERMITTED BY
APPLICABLE LAW.  EXCEPT WHEN OTHERWISE STATED IN WRITING THE COPYRIGHT
HOLDERS AND/OR OTHER PARTIES PROVIDE THE PROGRAM "AS IS" WITHOUT WARRANTY
OF ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE.  THE ENTIRE RISK AS TO THE QUALITY AND PERFORMANCE OF THE PROGRAM
IS WITH YOU.  SHOULD THE PROGRAM PROVE DEFECTIVE, YOU ASSUME THE COST OF
ALL NECESSARY SERVICING, REPAIR OR CORRECTION.

  16. Limitation of Liability.

  IN NO EVENT UNLESS REQUIRED BY APPLICABLE LAW OR AGREED TO IN WRITING
WILL ANY COPYRIGHT HOLDER, OR ANY OTHER PARTY WHO MODIFIES AND/OR CONVEYS
THE PROGRAM AS PERMITTED ABOVE, BE LIABLE TO YOU FOR DAMAGES, INCLUDING ANY
GENERAL, SPECIAL, INCIDENTAL OR CONSEQUENTIAL DAMAGES ARISING OUT OF THE
USE OR INABILITY TO USE THE PROGRAM (INCLUDING BUT NOT LIMITED TO LOSS OF
DATA OR DATA BEING RENDERED INACCURATE OR LOSSES SUSTAINED BY YOU OR THIRD
PARTIES OR A FAILURE OF THE PROGRAM TO OPERATE WITH ANY OTHER PROGRAMS),
EVEN IF SUCH HOLDER OR OTHER PARTY HAS BEEN ADVISED OF THE POSSIBILITY OF
SUCH DAMAGES.

  17. Interpretation of Sections 15 and 16.

  If the disclaimer of warranty and limitation of liability provided
above cannot be given local legal effect according to their terms,
reviewing courts shall apply local law that most closely approximates
an absolute waiver of all civil liability in connection with the
Program, unless a warranty or assumption of liability accompanies a
copy of the Program in return for a fee.

                     END OF TERMS AND CONDITIONS

            How to Apply These Terms to Your New Programs

  If you develop a new program, and you want it to be of the greatest
possible use to the public, the best way to achieve this is to make it
free software which everyone can redistribute and change under these terms.

  To do so, attach the following notices to the program.  It is safest
to attach them to the start of each source file to most effectively
state the exclusion of warranty; and each file should have at least
the "copyright" line and a pointer to where the full notice is found.

    <one line to give the program's name and a brief idea of what it does.>
    Copyright (C) <year>  <name of author>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

Also add information on how to contact you by electronic and paper mail.

  If the program does terminal interaction, make it output a short
notice like this when it starts in an interactive mode:

    <program>  Copyright (C) <year>  <name of author>
    This program comes with ABSOLUTELY NO WARRANTY; for details type `show w'.
    This is free software, and you are welcome to redistribute it
    under certain conditions; type `show c' for details.

The hypothetical commands `show w' and `show c' should show the appropriate
parts of the General Public License.  Of course, your program's commands
might be different; for a GUI interface, you would use an "about box".

  You should also get your employer (if you work as a programmer) or school,
if any, to sign a "copyright disclaimer" for the program, if necessary.
For more information on this, and how to apply and follow the GNU GPL, see
<http://www.gnu.org/licenses/>.

  The GNU General Public License does not permit incorporating your program
into proprietary programs.  If your program is a subroutine library, you
may consider it more useful to permit linking proprietary applications with
the library.  If this is what you want to do, use the GNU Lesser General
Public License instead of this License.  But first, please read
<http://www.gnu.org/philosophy/why-not-lgpl.html>.

## Local Arch opt-in development test downloads

The main APK does not bundle TypeScript, esbuild, sharp, better-sqlite3 or the
is-number Git fixture. The opt-in Android test scripts download pinned versions
into their separate Arch environment to exercise real development workloads.
Versions, upstream sources, licences and redistribution obligations are recorded
in `docs/DEPENDENCIES.md`. Keep package-provided notices and native-component
licences with any exported copy of that environment; these test commands are not
a source offer for downloaded third-party software.


The separate opt-in guest Prisma fixture pins `prisma`, `@prisma/client`, and
`@prisma/adapter-better-sqlite3` to stable **7.10.0**, Apache-2.0, from the official
npm registry and [Prisma source](https://github.com/prisma/prisma). They are installed
only by an explicit device compatibility test, never bundled in the app APK.
Retain package license/NOTICE files if redistributing a populated guest filesystem;
the test itself does not redistribute one. Its exact transitive tree is recorded
in the guest-generated package-lock.json. No prerelease `latest` tag is used.

The Prisma fixture additionally verifies transitive `better-sqlite3` **12.11.1**
(MIT, https://github.com/WiseLibs/better-sqlite3) before approving that version's
native install script. This is distinct from the primary development fixture's
13.0.3 and is not an Android APK dependency. Its package license remains in the guest.


## 2026-09-08: user-requested Arch starter tools

Architecture/licensing decision: install the following stable packages from the
signed Arch Linux ARM core/extra repositories into the user's guest filesystem,
using a full pacman upgrade. No additional native binary is linked into or bundled
with the Android application. GPL guest tools are explicitly approved in this
architecture; preserve their installed notices and corresponding source obligations
if ever distributing a populated filesystem. The application distributes only its
original setup script; users fetch packages from their official repositories.

| Direct guest package | Purpose | Upstream source | License / notice obligations |
| --- | --- | --- | --- |
| github-cli | User-requested GitHub login and repository CLI (stable signed Arch ARM package) | https://github.com/cli/cli | MIT; retain copyright and permission notice on redistribution |
| @openai/codex 0.153.4 | User-requested default ARM64 coding CLI, fetched from https://registry.npmjs.org | https://github.com/openai/codex | Apache-2.0; retain license, applicable NOTICE and bundled component notices |
| git | Clone and manage projects | https://git-scm.com/ | GPL-2.0-only; retain notices/source on redistribution |
| bash-completion | Shell completion | https://github.com/scop/bash-completion | GPL-2.0-or-later; retain notices/source |
| zoxide | Directory navigation | https://github.com/ajeetdsouza/zoxide | MIT; retain copyright/license |
| ripgrep | Search project text | https://github.com/BurntSushi/ripgrep | MIT/Unlicense; retain chosen license |
| fd | Find files | https://github.com/sharkdp/fd | MIT/Apache-2.0; retain license/notices |
| fzf | Interactive selection | https://github.com/junegunn/fzf | MIT; retain copyright/license |
| bat | Read source files | https://github.com/sharkdp/bat | MIT/Apache-2.0; retain license/notices |
| eza | Directory listings | https://github.com/eza-community/eza | MIT; retain copyright/license |
| jq | Inspect JSON | https://jqlang.org/ | MIT; retain copyright/license |
| nano | Terminal editor | https://nano-editor.org/ | GPL-3.0-or-later; retain notices/source |
| less | Pager | https://www.greenwoodsoftware.com/less/ | GPL-3.0-or-later or Less license; preserve chosen license |
| unzip | Extract ZIP files | https://infozip.sourceforge.net/ | Info-ZIP; retain copyright/license |
| zip | Create ZIP files | https://infozip.sourceforge.net/ | Info-ZIP; retain copyright/license |
| curl | Download files / HTTP tools | https://curl.se/ | curl license; retain copyright/license |
| openssh | SSH client and Git transport | https://www.openssh.com/ | BSD and other file-level permissive licenses; retain notices |
| rsync | Copy project files | https://rsync.samba.org/ | GPL-3.0-or-later; retain notices/source |
| base-devel | Build guest software | https://archlinuxarm.org/packages/aarch64/base-devel | Meta-package; dependencies retain their licenses, including GCC/binutils/make GPL and runtime exceptions |
| nodejs-lts-krypton | Node 24 LTS for Cable Flow | https://nodejs.org/ | MIT plus bundled component notices |
| npm | Install JavaScript tools | https://github.com/npm/cli | Artistic-2.0 and bundled notices |

GitHub CLI and Codex became default guest tools at the user’s explicit request
on 2026-09-08. Codex is fetched separately from the official npm registry at the
stable version above, only when absent. Existing Codex and account files are kept.
No authentication or AI request runs automatically; users initiate both logins
and subsequent commands. The APK contains setup instructions, not these binaries.

Pacman versions are resolved by signed stable repository metadata and recorded by pacman
in the guest; no testing/staging or prerelease channel is enabled. Arch's full
upgrade avoids partial upgrades. Omarchy's CLI package list is a selection reference
(https://github.com/omacom/omarchy/blob/master/install/omarchy-base.packages), not
copied application code or a desktop installation. AUR helpers are not a bootstrap
requirement: makepkg requires non-root execution and AUR recipes need ARM review.

### Optional phone Cable Flow verification dependencies (2026-09-08)

These are explicitly requested guest compatibility probes, downloaded only in the
opt-in isolated USB test, not Android APK dependencies or automatic account setup.

| Dependency | Purpose and source | Licence / notice obligations |
| --- | --- | --- |
| pnpm 10.29.2 | Cable Flow frozen-lockfile package manager; https://github.com/pnpm/pnpm | MIT; retain copyright and permission notice on redistribution |
| PostgreSQL (stable Arch ARM package) | Isolated phone database; https://www.postgresql.org/ and https://archlinuxarm.org/packages/aarch64/postgresql | PostgreSQL license; retain copyright and permission notice |
| pgvector 0.8.2 | Cable Knowledge SQL extension; https://github.com/pgvector/pgvector/tree/v0.8.2 | PostgreSQL license; retain copyright and permission notice |

Cable Flow source and its own frozen dependency graph are supplied privately from
the user's repository for this test, not redistributed with Terminal Spike.
Existing Cable Flow CI licensing fixtures are used only for isolated verification.
No host authentication files, databases or license files are transferred.

The 2026-09-08 runtime enables PRoot's existing System V IPC extension and adds
an original stale-local-key lookup fix to libandroid-shmem 0.7 (BSD-3-Clause).
`local-arch-runtime/patches/shmem-stale-local-key.patch` is included in the runtime
source offer along with the existing source archives and build inputs. No new
third-party dependency or version is introduced by this fix.
