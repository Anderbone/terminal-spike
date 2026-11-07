#!/usr/bin/env python3
"""Package real app recordings; regenerate focused excerpts and lossless stills offline."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"


def run(*args: str) -> None:
    subprocess.run(["ffmpeg", "-y", "-v", "error", *map(str, args)], check=True)


def camera_expression(anchors: list[dict]) -> str:
    value = str(anchors[0]["y"])
    # Each move is a half-second pan over the original pixels, with no reconstructed controls.
    for previous, current in zip(anchors, anchors[1:]):
        start, end = current["time"], current["time"] + .5
        old, new = previous["y"], current["y"]
        move = f"{old}+({new}-{old})*(t-{start})/0.5"
        value = f"if(lt(t,{start}),{value},if(lt(t,{end}),{move},{new}))"
    return value


def build_short_visuals(codec: list[str]) -> None:
    """One action per loop or still, for the three-step tour and independent tips."""
    manifest = json.loads((ROOT / "store-assets/user-guide/visuals.json").read_text())
    for name, visual in manifest.items():
        source = RES / "raw" / f"guide_{visual['source']}_full.mp4"
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
    parser.add_argument("--capture-dir", type=Path, help="Import original NAME-source.mp4 recordings")
    parser.add_argument("--short-only", action="store_true", help="Only rebuild the concise tour and tips")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "store-assets/user-guide/recordings.json").read_text())
    raw = RES / "raw"
    posters = RES / "drawable-nodpi"
    raw.mkdir(exist_ok=True)
    posters.mkdir(exist_ok=True)
    codec = ["-an", "-c:v", "libx264", "-profile:v", "baseline", "-level:v", "4.0",
             "-pix_fmt", "yuv420p", "-crf", "18", "-preset", "medium", "-movflags", "+faststart",
             "-map_metadata", "-1"]
    if args.short_only:
        build_short_visuals(codec)
        return
    for name, clip in manifest["clips"].items():
        full = raw / f"guide_{name}_full.mp4"
        focused = raw / f"guide_{name}.mp4"
        if args.capture_dir:
            run("-i", args.capture_dir / f"{name}-source.mp4", "-vf", "fps=24,setsar=1", *codec, full)
        expression = camera_expression(clip["camera"])
        run("-i", full, "-vf", f"crop=720:600:0:'{expression}',setsar=1", *codec, focused)
        for source, suffix in [(focused, ""), (full, "_full")]:
            run("-i", source, "-frames:v", "1", "-c:v", "libwebp", "-lossless", "1",
                posters / f"guide_{name}{suffix}.webp")
        print(f"GUIDE_MEDIA {name} full={full.stat().st_size} focused={focused.stat().st_size}")
    build_short_visuals(codec)


if __name__ == "__main__":
    main()
