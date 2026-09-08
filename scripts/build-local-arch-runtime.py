#!/usr/bin/env python3
"""Build only the pinned Android arm64 Local Arch runtime from vendored sources.

No network access, privilege changes, or device actions. Generated inputs, native
objects and packaging outputs remain under local-arch-runtime/build.
"""

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "local-arch-runtime"


def run(args, cwd, env):
    subprocess.run([str(arg) for arg in args], cwd=cwd, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    lock = json.loads((RUNTIME / "sources.lock").read_text())
    ndk = args.ndk.resolve()
    properties = (ndk / "source.properties").read_text()
    if f"Pkg.Revision = {lock['ndkVersion']}" not in properties:
        parser.error(f"NDK {lock['ndkVersion']} is required")
    toolchain = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    compiler = toolchain / "aarch64-linux-android26-clang"
    if not compiler.is_file():
        parser.error("Requires the Linux x86_64 host NDK toolchain")
    work = RUNTIME / "build"
    work.mkdir(parents=True, exist_ok=True)
    build_lock = (work / "build.lock").open("w")
    try:
        fcntl.flock(build_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError("A Local Arch native build is already running; wait for that process") from None
    source_root = work / "sources"
    stage = work / "stage"
    output = work / "jniLibs/arm64-v8a"
    # Validate ALL sources before modifying any generated output.
    for source in lock["sources"]:
        archive = RUNTIME / "third_party/distfiles" / source["file"]
        if hashlib.sha256(archive.read_bytes()).hexdigest() != source["sha256"]:
            raise RuntimeError(f"Source checksum mismatch: {archive.name}")
    for directory in (source_root, stage, output):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
    for source in lock["sources"]:
        archive = RUNTIME / "third_party/distfiles" / source["file"]
        if archive.suffix == ".zip":
            with zipfile.ZipFile(archive) as bundle:
                bundle.extractall(source_root)
        else:
            with tarfile.open(archive) as bundle:
                bundle.extractall(source_root, filter="data")
    env = dict(os.environ)
    # Build subprocesses receive controlled cross-compiler settings; do not inherit
    # host CFLAGS/LDFLAGS, loader paths or compiler caches from the caller.
    for name in ("LD_LIBRARY_PATH", "CPPFLAGS", "CXXFLAGS", "CFLAGS", "LDFLAGS",
                 "CC", "CXX", "AR", "LD", "CROSS_COMPILE", "PKG_CONFIG_PATH"):
        env.pop(name, None)
    alignment = "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
    env.update(
        CC=str(compiler), AR=str(toolchain / "llvm-ar"),
        STRIP=str(toolchain / "llvm-strip"),
        OBJCOPY=str(toolchain / "llvm-objcopy"),
        OBJDUMP=str(toolchain / "llvm-objdump"),
        CFLAGS=f"-O2 -fPIC -ffile-prefix-map={ROOT}=.",
        LDFLAGS=f"{alignment} -Wl,-z,relro -Wl,-z,now",
        LC_ALL="C", TZ="UTC", PYTHONHASHSEED="0", SOURCE_DATE_EPOCH="1788739200",
    )
    talloc = source_root / "talloc-2.4.3"
    # Waf may append missing answers; preserve the checked-in evidence unchanged.
    answers = work / "talloc-cross-answers.txt"
    shutil.copyfile(RUNTIME / answers.name, answers)
    run(["./configure", f"--prefix={stage}", "--disable-rpath", "--disable-python",
         "--cross-compile", f"--cross-answers={answers}"], talloc, env)
    run(["make", f"-j{args.jobs}"], talloc, env)
    run(["make", "install"], talloc, env)
    shmem = source_root / "libandroid-shmem-0.7"
    run(["patch", "--batch", "--fuzz=0", "-p1", "-i", RUNTIME / "patches/shmem-private-tmp.patch"], shmem, env)
    run(["patch", "--batch", "--fuzz=0", "-p1", "-i", RUNTIME / "patches/shmem-stale-local-key.patch"], shmem, env)
    run(["make", f"-j{args.jobs}", "install", f"PREFIX={stage}"], shmem, env)
    proot = source_root / "proot-5.1.107.81/src"
    run(["patch", "--batch", "--fuzz=0", "-p1", "-i", RUNTIME / "patches/proot-string-header.patch"], proot.parent, env)
    run(["patch", "--batch", "--fuzz=0", "-p1", "-i", RUNTIME / "patches/proot-exitkill.patch"], proot.parent, env)
    proot_env = dict(env)
    proot_env.update(
        # This impossible default prevents reliance on a Termux-owned data path.
        # Every launch must supply the APK's actual PROOT_LOADER path explicitly.
        PROOT_UNBUNDLE_LOADER="/local-arch-requires-explicit-loader",
        PROOT_WITH_LIBANDROID_SHMEM="true",
        GIT="false",
        CPPFLAGS=f'-I{stage}/include -DARG_MAX=131072 -DVERSION=\\"5.1.107.81\\"',
        LDFLAGS=f"{env['LDFLAGS']} -L{stage}/lib",
        LOADER_LDFLAGS=alignment,
    )
    run(["make", f"-j{args.jobs}", "GIT=false", "HAS_LOADER_32BIT="], proot, proot_env)
    artifacts = {
        "libproot.so": proot / "proot",
        "libproot_loader.so": proot / "loader/loader",
        "libtalloc.so": stage / "lib/libtalloc.so",
        "libandroid-shmem.so": stage / "lib/libandroid-shmem.so",
    }
    for name, source in artifacts.items():
        destination = output / name
        shutil.copyfile(source, destination)
        # Match the APK's lib*.so extraction names. Same-length replacement with
        # NUL padding preserves ELF offsets; verify dynamic entries below.
        data = destination.read_bytes().replace(b"libtalloc.so.2\0", b"libtalloc.so\0\0\0")
        destination.write_bytes(data)
        destination.chmod(0o755)
        elf = destination.read_bytes()
        if elf[:6] != b"\x7fELF\x02\x01" or int.from_bytes(elf[18:20], "little") != 183:
            raise RuntimeError(f"{name} is not little-endian ELF64 AArch64")
        run([toolchain / "llvm-strip", "--strip-unneeded", destination], work, env)
        headers = subprocess.check_output(
            [str(toolchain / "llvm-readelf"), "-lW", str(destination)], text=True)
        loads = [line.split() for line in headers.splitlines() if line.strip().startswith("LOAD ")]
        if not loads or any(int(fields[-1], 16) < 16384 for fields in loads):
            raise RuntimeError(f"{name} is not aligned for 16 KB Android pages")
        dynamic = subprocess.check_output(
            [str(toolchain / "llvm-readelf"), "-dW", str(destination)], text=True)
        for line in dynamic.splitlines():
            if "(NEEDED)" in line:
                needed = line.split("[", 1)[1].split("]", 1)[0]
                if needed not in artifacts and needed not in {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}:
                    raise RuntimeError(f"Unexpected runtime dependency: {name} -> {needed}")
        print(f"Verified arm64/16KB dependency closure: {name}", flush=True)
    # Preserve the main app's existing x86_64 remote-terminal availability.
    # Only the generic JNI bridge is built there; no x86 Arch/PRoot is provided.
    for abi, triple in (("arm64-v8a", "aarch64"), ("x86_64", "x86_64")):
        bridge_output = work / "jniLibs" / abi / "liblocalpty.so"
        bridge_output.parent.mkdir(parents=True, exist_ok=True)
        run([toolchain / f"{triple}-linux-android26-clang++", "-std=c++17",
             "-shared", "-fPIC", "-static-libstdc++", "-O2", "-Wall", "-Wextra", "-Werror",
             "-fvisibility=hidden", "-fstack-protector-strong", f"-ffile-prefix-map={ROOT}=.",
             "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
             "-Wl,-z,relro", "-Wl,-z,now", "-Wl,-z,noexecstack",
             ROOT / "app/src/main/cpp/local_pty.cpp", "-o", bridge_output], work, env)
        run([toolchain / "llvm-strip", "--strip-unneeded", bridge_output], work, env)
    manifest = {
        "schemaVersion": 1, "abi": lock["abi"], "ndkVersion": lock["ndkVersion"],
        "files": {str(path.relative_to(work / "jniLibs")): hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in sorted((work / "jniLibs").glob("*/*.so"))},
    }
    (work / "runtime-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Runtime built: {output}")


if __name__ == "__main__":
    main()
