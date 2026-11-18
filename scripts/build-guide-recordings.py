#!/usr/bin/env python3
"""Package real app recordings; regenerate focused excerpts and lossless stills offline."""
import argparse
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
SOURCES = ROOT / "store-assets/user-guide/source"


def run(*args: str) -> None:
    subprocess.run(["ffmpeg", "-y", "-v", "error", *map(str, args)], check=True)


def build_visuals(codec: list[str]) -> None:
    """One action per loop or still, for the current tour and independent tips."""
    manifest = json.loads((ROOT / "store-assets/user-guide/visuals.json").read_text())
    for name, visual in manifest.items():
        source = SOURCES / f"{visual['source']}.mp4"
        poster = RES / "drawable-nodpi" / f"guide_{name}.webp"
        filters = visual["filter"]
        if "duration" in visual:
            video = RES / "raw" / f"guide_{name}.mp4"
            speed = visual.get("speed", 1)
            run("-ss", visual["start"], "-t", visual["duration"], "-i", source,
                "-vf", f"{filters},setpts=PTS/{speed},fps=24,setsar=1", *codec, video)
            run("-ss", visual["poster_at"], "-i", video, "-frames:v", "1",
                "-c:v", "libwebp", "-lossless", "1", poster)
        else:
            run("-ss", visual["start"], "-i", source, "-vf", filters, "-frames:v", "1",
                "-c:v", "libwebp", "-lossless", "1", poster)
        print(f"GUIDE_VISUAL {name} poster={poster.stat().st_size}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--capture-dir", type=Path, help="Import original NAME-source.mp4 recordings")
    mode.add_argument("--short-only", action="store_true", help="Compatibility alias for rebuilding the current tour and tips")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "store-assets/user-guide/recordings.json").read_text())
    raw = RES / "raw"
    posters = RES / "drawable-nodpi"
    raw.mkdir(exist_ok=True)
    posters.mkdir(exist_ok=True)
    codec = ["-an", "-c:v", "libx264", "-profile:v", "baseline", "-level:v", "4.0",
             "-pix_fmt", "yuv420p", "-crf", "18", "-preset", "medium", "-movflags", "+faststart",
             "-map_metadata", "-1"]
    if args.capture_dir:
        SOURCES.parent.mkdir(parents=True, exist_ok=True)
        # A failed encoder must not overwrite a canonical capture or publish a mixed set.
        with TemporaryDirectory(prefix=".guide-captures-", dir=SOURCES.parent) as temporary:
            staged = Path(temporary)
            for name in manifest["clips"]:
                run("-i", args.capture_dir / f"{name}-source.mp4", "-vf", "fps=24,setsar=1",
                    *codec, staged / f"{name}.mp4")
            SOURCES.mkdir(exist_ok=True)
            for name in manifest["clips"]:
                full = SOURCES / f"{name}.mp4"
                (staged / f"{name}.mp4").replace(full)
                print(f"GUIDE_SOURCE {name} bytes={full.stat().st_size}")
    build_visuals(codec)


if __name__ == "__main__":
    main()
