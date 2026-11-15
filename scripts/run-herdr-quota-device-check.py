#!/usr/bin/env python3
"""Opt-in S10e check in an already-selected, owned two-tab Herdr fixture. Never fills real /tmp."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--session", required=True)
    parser.add_argument("--pane-one", default="w1:p1")
    parser.add_argument("--tab-one", default="w1:t1")
    parser.add_argument("--tab-two", default="w1:t2")
    parser.add_argument("--x-one", type=int, required=True)
    parser.add_argument("--x-two", type=int, required=True)
    parser.add_argument("--y", type=int, required=True)
    parser.add_argument("--cycles", type=int, default=10)
    parser.add_argument("--phase", choices=("both", "green"), default="both")
    parser.add_argument("--herdr", default="herdr")
    parser.add_argument("--output", type=Path, default=Path("build/verification/quota-device-check"))
    args = parser.parse_args()
    if not re.fullmatch(r"terminal-spike-quota-[0-9]+", args.session):
        parser.error("Requires a runner-owned named session")
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    environment = {k: v for k, v in os.environ.items() if not k.startswith("HERDR_")}
    def adb(*command):
        return subprocess.check_output(["adb", "-s", args.serial, *map(str, command)], timeout=15)
    def verify():
        inventory = subprocess.check_output(["adb", "devices", "-l"], text=True)
        assert any(line.split()[:2] == [args.serial, "device"] for line in inventory.splitlines())
        assert adb("shell", "getprop", "ro.product.model").decode().strip() == "SM-G9708"
        assert adb("shell", "getprop", "ro.serialno").decode().strip() == "R28M227XA0E"
    def herdr(*command):
        result = subprocess.check_output([args.herdr, "--session", args.session, *command], env=environment, timeout=10)
        if not result.strip():
            return {}
        return json.loads(result)["result"]
    def run_in_pane(script):
        encoded = base64.b64encode(script.encode()).decode()
        herdr("pane", "run", args.pane_one, f"printf %s {encoded} | base64 -d | bash")
    def wait_tab(expected):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            # CLI focus is not this client's focus. Verify the selected tab on the phone.
            frame = adb("exec-out", "screencap", "-p")
            x = args.x_one if expected == args.tab_one else args.x_two
            color = subprocess.check_output(["ffmpeg", "-hide_banner", "-loglevel", "error", "-threads", "1",
                "-filter_threads", "1", "-i", "pipe:0", "-vf", f"format=rgb24,crop=1:1:{x}:{args.y - 20}:exact=1",
                "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"], input=frame, timeout=10)
            red, green, blue = color
            if blue > red + 40 and green > red + 30:
                return
            time.sleep(.025)
        raise AssertionError("Real phone tap did not switch the owned Herdr tab")
    def screenshot(name):
        path = root / (name + ".png")
        path.write_bytes(adb("exec-out", "screencap", "-p"))
        text = subprocess.check_output(["tesseract", str(path), "stdout", "--psm", "11"], stderr=subprocess.DEVNULL, timeout=20).decode()
        (root / (name + ".ocr.txt")).write_text(text)
        return re.sub(r"[^A-Z0-9]", "", text.upper())
    verify()
    assert {tab["tab_id"] for tab in herdr("tab", "list")["tabs"]} == {args.tab_one, args.tab_two}
    adb("shell", "input", "tap", args.x_one, args.y)
    wait_tab(args.tab_one)
    library = root / "edquot.so"
    source = Path(__file__).resolve().parents[1] / "integration-tests/quota/edquot.c"
    subprocess.run(["cc", "-shared", "-fPIC", "-O2", str(source), "-ldl", "-o", str(library)], check=True)
    bad = root / "quota-directory"
    good = root / "disk-directory"
    bad.mkdir(exist_ok=True)
    good.mkdir(exist_ok=True)
    evidence = {"device": args.serial, "model": "SM-G9708", "session": args.session,
                "scope": "Controlled EDQUOT in fixture only; not proof of reproducing the original freeze", "phases": {}}
    if args.phase == "green":
        previous = json.loads((root / "evidence.json").read_text())
        assert previous["device"] == args.serial and previous["session"] == args.session
        assert "red" in previous["phases"]
        evidence["phases"]["red"] = previous["phases"]["red"]
    try:
        for phase, directory in [("red", bad), ("green", good)]:
            if args.phase == "green" and phase == "red":
                continue
            verify()
            script = "printf '\\033[2J\\033[H'\n"
            script += f"if printf payload | env LD_PRELOAD={shlex.quote(str(library))} TERMINAL_SPIKE_QUOTA_ROOT={shlex.quote(str(bad))} cat >{shlex.quote(str(directory / 'probe'))}; then\n"
            script += "printf 'QUOTA_PROBE_OK\\n'\nelse\nprintf 'QUOTA_PROBE_FAILED\\n'\nfi\n"
            script += f"printf 'QUOTA_{phase.upper()}_DONE\\n'\n"
            run_in_pane(script)
            time.sleep(1)
            visible = screenshot(phase + "-initial")
            assert "QUOTAPROBE" + ("FAILED" if phase == "red" else "OK") in visible, visible
            if phase == "red":
                assert "DISKQUOTAEXCEEDED" in visible, visible
            else:
                assert "DISKQUOTAEXCEEDED" not in visible, visible
            timings = []
            for _ in range(args.cycles):
                for tab, x in [(args.tab_two, args.x_two), (args.tab_one, args.x_one)]:
                    # Adjacent rapid taps can intentionally invoke the app's double-tap action.
                    time.sleep(.4)
                    started = time.monotonic()
                    adb("shell", "input", "tap", x, args.y)
                    wait_tab(tab)
                    timings.append(round((time.monotonic() - started) * 1000, 1))
            screenshot(phase + "-final")
            evidence["phases"][phase] = {"probe": "EDQUOT" if phase == "red" else "writable",
                                          "real_taps": len(timings), "gesture_to_selected_phone_frame_ms": timings,
                                          "max_ms": max(timings)}
            print(phase, evidence["phases"][phase], flush=True)
    finally:
        evidence["finished_at"] = time.time()
        (root / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")


if __name__ == "__main__":
    main()
