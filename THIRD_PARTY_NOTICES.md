# Third-party notices

Terminal Spike's main-application source remains private and unlicensed. The separate project-owned
`mosh-api` module is licensed under Apache-2.0 as stated in `mosh-api/LICENSE`; that grant does not
apply to the main application or other project-owned modules.

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

No ConnectBot, Termux, Termius, LobiShell, libvterm, xterm.js, GPL, or AGPL source or binary is
included in the main application APK or `mosh-api` AAR. The separately built and installed Mosh
extension is a GPL-3.0-or-later artifact with its own notices and Corresponding Source inventory in
`mosh-extension/THIRD_PARTY_NOTICES.md` and `mosh-extension/DEPENDENCIES.md`.

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
