#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")" && pwd)"

python3 - "$root_dir" <<'PY'
from pathlib import Path
import struct
import sys

root = Path(sys.argv[1])
limits = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
failed = False

for locale_dir in sorted((root / "metadata").iterdir()):
    if not locale_dir.is_dir():
        continue
    for name, limit in limits.items():
        path = locale_dir / name
        if not path.is_file():
            print(f"ERROR missing {path.relative_to(root)}")
            failed = True
            continue
        text = path.read_text(encoding="utf-8").rstrip("\n")
        count = len(text)
        state = "OK" if count <= limit else "ERROR"
        print(f"{state} {path.relative_to(root)}: {count}/{limit}")
        failed |= count > limit

def png_size(path: Path):
    with path.open("rb") as handle:
        signature = handle.read(24)
    if signature[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")
    return struct.unpack(">II", signature[16:24])

expected = {
    root / "graphics" / "app-icon-512.png": ((512, 512), 1 * 1024 * 1024),
    root / "graphics" / "feature-graphic-1024x500.png": ((1024, 500), 15 * 1024 * 1024),
}
for path, (dimensions, byte_limit) in expected.items():
    if not path.is_file():
        print(f"ERROR missing {path.relative_to(root)}")
        failed = True
        continue
    actual = png_size(path)
    size = path.stat().st_size
    state = "OK" if actual == dimensions and size <= byte_limit else "ERROR"
    print(f"{state} {path.relative_to(root)}: {actual[0]}x{actual[1]}, {size} bytes")
    failed |= state == "ERROR"

for category in ("phone", "tablet-7", "tablet-10"):
    paths = sorted((root / "screenshots" / category).glob("*.png"))
    if len(paths) < 4:
        print(f"ERROR screenshots/{category}: expected at least 4 PNG files, found {len(paths)}")
        failed = True
    for path in paths:
        width, height = png_size(path)
        size = path.stat().st_size
        ratio_ok = width * 16 == height * 9 or width * 9 == height * 16
        dimensions_ok = min(width, height) >= 1080 and max(width, height) <= 7680
        state = "OK" if ratio_ok and dimensions_ok and size <= 8 * 1024 * 1024 else "ERROR"
        print(f"{state} {path.relative_to(root)}: {width}x{height}, {size} bytes")
        failed |= state == "ERROR"

raise SystemExit(1 if failed else 0)
PY
