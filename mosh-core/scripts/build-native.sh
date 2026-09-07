#!/usr/bin/env bash
# Copyright 2026 Terminal Spike contributors
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "usage: build-native.sh ANDROID_SDK_DIR WORK_DIR JNI_OUTPUT_DIR" >&2
    exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
extension_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
sdk_dir=$1
work_root=$2
output_dir=$3
ndk_revision=29.0.14206865
ndk_dir="$sdk_dir/ndk/$ndk_revision"
toolchain="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64"
api_level=26

if [[ ! -f "$ndk_dir/source.properties" ]] ||
   ! grep -Fxq "Pkg.Revision = $ndk_revision" "$ndk_dir/source.properties"; then
    echo "Android NDK $ndk_revision is required under the selected SDK." >&2
    exit 1
fi
if [[ ! -x "$toolchain/bin/llvm-readelf" ]]; then
    echo "Pinned NDK LLVM tools are unavailable." >&2
    exit 1
fi

"$script_dir/verify-sources.sh"
(
    cd "$extension_dir/src/main/cpp/generated"
    sha256sum --check SHA256SUMS
)

mkdir -p "$work_root" "$output_dir"
run_dir=$(mktemp -d "$work_root/run.XXXXXX")
trap 'rm -rf -- "$run_dir"' EXIT
source_dir="$run_dir/source"
mkdir -p "$source_dir"
tar -xzf "$extension_dir/third_party/distfiles/mosh-1.4.0.tar.gz" -C "$source_dir"
tar -xzf "$extension_dir/third_party/distfiles/nettle-3.10.2.tar.gz" -C "$source_dir"
tar -xzf "$extension_dir/third_party/distfiles/protobuf-all-21.12.tar.gz" -C "$source_dir"
patch --batch --fuzz=0 -d "$source_dir/mosh-1.4.0" -p1 \
    < "$extension_dir/patches/mosh-1.4.0-android-key-hygiene.patch"
patch --batch --fuzz=0 -d "$source_dir/mosh-1.4.0" -p1 \
    < "$extension_dir/patches/mosh-1.4.0-android-network.patch"

jobs=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)
if [[ "$jobs" -gt 8 ]]; then jobs=8; fi

build_abi() {
    local abi=$1
    local host_triple=$2
    local compiler_triple=$3
    local expected_machine=$4
    local abi_dir="$run_dir/$abi"
    local nettle_build="$abi_dir/nettle-build"
    local nettle_install="$abi_dir/nettle-install"
    local protobuf_build="$abi_dir/protobuf-build"
    local native_build="$abi_dir/native-build"
    local native_library="$native_build/libmosh_extension.so"

    mkdir -p "$nettle_build" "$nettle_install" "$protobuf_build" "$native_build"
    (
        cd "$nettle_build"
        env \
            CC="$toolchain/bin/${compiler_triple}${api_level}-clang" \
            AR="$toolchain/bin/llvm-ar" \
            RANLIB="$toolchain/bin/llvm-ranlib" \
            STRIP="$toolchain/bin/llvm-strip" \
            CFLAGS="-O2 -fPIC -fstack-protector-strong -D_FORTIFY_SOURCE=2" \
            "$source_dir/nettle-3.10.2/configure" \
                --quiet \
                --host="$host_triple" \
                --prefix="$nettle_install" \
                --enable-static \
                --disable-shared \
                --disable-public-key \
                --disable-documentation \
                --disable-openssl \
                --disable-fat \
                --disable-assembler
        make --silent --jobs="$jobs" libnettle.a
        make --silent install-headers install-static
    )

    cmake \
        -S "$source_dir/protobuf-21.12" \
        -B "$protobuf_build" \
        -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$api_level" \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -Dprotobuf_BUILD_TESTS=OFF \
        -Dprotobuf_BUILD_CONFORMANCE=OFF \
        -Dprotobuf_BUILD_EXAMPLES=OFF \
        -Dprotobuf_BUILD_PROTOC_BINARIES=OFF \
        -Dprotobuf_BUILD_LIBPROTOC=OFF \
        -Dprotobuf_BUILD_SHARED_LIBS=OFF \
        -Dprotobuf_WITH_ZLIB=OFF \
        -Dprotobuf_INSTALL=OFF
    cmake --build "$protobuf_build" --target libprotobuf-lite --parallel "$jobs"

    cmake \
        -S "$extension_dir/src/main/cpp" \
        -B "$native_build" \
        -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$api_level" \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo \
        -DMOSH_SOURCE_DIR="$source_dir/mosh-1.4.0" \
        -DNETTLE_INCLUDE_DIR="$nettle_install/include" \
        -DNETTLE_LIBRARY="$nettle_install/lib/libnettle.a" \
        -DPROTOBUF_INCLUDE_DIR="$source_dir/protobuf-21.12/src" \
        -DPROTOBUF_LITE_LIBRARY="$protobuf_build/libprotobuf-lite.a"
    cmake --build "$native_build" --target mosh_extension --parallel "$jobs"

    "$toolchain/bin/llvm-readelf" -h "$native_library" | grep -F "$expected_machine" >/dev/null
    while IFS= read -r alignment; do
        if (( alignment < 0x4000 )); then
            echo "$abi library is not aligned for 16 KiB Android pages." >&2
            exit 1
        fi
    done < <("$toolchain/bin/llvm-readelf" -lW "$native_library" |
        awk '$1 == "LOAD" { print $NF }')

    local needed
    needed=$("$toolchain/bin/llvm-readelf" -dW "$native_library" |
        sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
    while IFS= read -r dependency; do
        case "$dependency" in
            ""|libc.so|libm.so|libdl.so|liblog.so|libz.so) ;;
            *)
                echo "$abi has unexpected dynamic dependency: $dependency" >&2
                exit 1
                ;;
        esac
    done <<< "$needed"

    mkdir -p "$output_dir/$abi" "$work_root/symbols/$abi"
    install -m 0755 "$native_library" "$output_dir/$abi/libmosh_extension.so"
    install -m 0755 "$native_library" "$work_root/symbols/$abi/libmosh_extension.so"
    "$toolchain/bin/llvm-readelf" -n "$native_library" > "$work_root/symbols/$abi/elf-notes.txt"
    echo "Built and verified $abi Mosh 1.4.0 native engine."
}

build_abi arm64-v8a aarch64-linux-android aarch64-linux-android AArch64
build_abi x86_64 x86_64-linux-android x86_64-linux-android "Advanced Micro Devices X86-64"

echo "Pinned Mosh JNI libraries are ready for arm64-v8a and x86_64."
