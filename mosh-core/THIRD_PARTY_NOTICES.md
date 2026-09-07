# Third-party notices — Terminal Spike built-in Mosh transport

The built-in Mosh transport is distributed with Terminal Spike under GPL-3.0-or-later and
comes with **no warranty**. Complete corresponding source is in the unified Terminal Spike
repository. `scripts/create-source-bundle.sh` at the repository root produces the complete
source archive, including the application, API, native implementation and all build inputs.

## Mosh 1.4.0

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

## GNU Nettle 3.10.2

Copyright the GNU Nettle authors, including Niels Möller and contributors. The AES implementation
is offered under LGPL-3.0-or-later or GPL-2.0-or-later; this GPL-3.0-or-later combined work selects a
GPL-compatible version. The supplied GPLv2, GPLv3 and LGPLv3 texts are packaged unchanged.

## Protocol Buffers 21.12

Copyright 2008 Google Inc. All rights reserved. Redistribution in source and binary forms, with or
without modification, is permitted subject to the three conditions and disclaimer in the packaged
`PROTOBUF-LICENSE`. Neither Google nor contributor names may endorse derived products without
specific prior written permission. The software is provided “AS IS”, without warranty.

## Android NDK, LLVM libc++, AndroidX, and Kotlin

Portions of LLVM libc++ are statically linked from the pinned Android NDK r29 toolchain. LLVM libc++,
AndroidX libraries, and Kotlin standard library are licensed under Apache License 2.0; libc++ also
uses the LLVM exception. The exact NDK and LLVM toolchain notices used for this build are packaged
as `ANDROID-NDK-R29-NOTICE` and `LLVM-TOOLCHAIN-NOTICE`. The extension dynamically uses Android's
platform `libz`; the NDK notice includes its zlib licence text.
