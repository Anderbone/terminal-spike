#!/usr/bin/env python3
"""Encode terminal acceptance input and recognize its complete native output line."""

import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET


SAFE_MARKER = re.compile(r"[A-Z0-9_]{1,96}")


def require_marker(marker: str) -> None:
    if not SAFE_MARKER.fullmatch(marker):
        raise ValueError("terminal marker must contain 1–96 ASCII uppercase letters, digits or underscores")


def marker_input_argument(marker: str) -> str:
    require_marker(marker)
    # ADB joins shell arguments; the outer quotes protect one input-tool argument. Android's
    # input tool turns %s into a space. The terminal shell then receives printf's quoted octal
    # format, whose command echo cannot contain the plaintext marker. Output ends in a newline.
    encoded = "".join(f"\\{ord(character):03o}" for character in marker) + "\\012"
    return f'"printf%s\'{encoded}\'"'


def has_terminal_marker(root: ET.Element, marker: str) -> bool:
    require_marker(marker)
    return any(
        node.get("content-desc") == "Native terminal renderer"
        and any(line.rstrip(" ") == marker for line in node.get("text", "").split("\n"))
        for node in root.iter("node")
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("input", "verify"))
    parser.add_argument("marker")
    parser.add_argument("xml", nargs="?", type=Path)
    args = parser.parse_args()
    try:
        require_marker(args.marker)
    except ValueError as error:
        parser.error(str(error))
    if args.operation == "input":
        if args.xml is not None:
            parser.error("input does not accept an XML file")
        print(marker_input_argument(args.marker))
        return 0
    if args.xml is None:
        parser.error("verify requires an XML file")
    return 0 if has_terminal_marker(ET.parse(args.xml).getroot(), args.marker) else 1


if __name__ == "__main__":
    raise SystemExit(main())
