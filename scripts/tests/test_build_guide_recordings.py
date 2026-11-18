import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


SCRIPT = Path(__file__).resolve().parents[1] / "build-guide-recordings.py"
NAMES = ("typing", "snippets", "projects", "gestures")


class GuideCaptureImportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="guide import ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        spec = importlib.util.spec_from_file_location("guide_capture_fixture", SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.module.ROOT = self.root
        self.module.RES = self.root / "app/src/main/res"
        self.module.SOURCES = self.root / "store-assets/user-guide/source"
        self.module.SOURCES.mkdir(parents=True)
        for directory in ("raw", "drawable-nodpi"):
            (self.module.RES / directory).mkdir(parents=True)
        self.captures = self.root / "captures"
        self.captures.mkdir()
        (self.module.SOURCES.parent / "recordings.json").write_text(
            json.dumps({"clips": {name: {} for name in NAMES}})
        )
        for name in NAMES:
            (self.module.SOURCES / f"{name}.mp4").write_bytes(f"existing {name}".encode())
            (self.captures / f"{name}-source.mp4").write_bytes(f"capture {name}".encode())
        self.original = self.recordings()
        self.module.build_visuals = Mock()

    def recordings(self):
        return {name: (self.module.SOURCES / f"{name}.mp4").read_bytes() for name in NAMES}

    def import_captures(self):
        with patch.object(sys, "argv", [str(SCRIPT), "--capture-dir", str(self.captures)]):
            self.module.main()

    def test_failed_capture_import_preserves_every_recording_and_removes_partial_files(self):
        original_entries = {p.relative_to(self.root) for p in self.root.rglob("*")}
        calls = 0

        def encode(*args):
            nonlocal calls
            calls += 1
            destination = Path(args[-1])
            destination.write_bytes(b"partial encoded output")
            if calls == 2:
                raise subprocess.CalledProcessError(1, ["ffmpeg"])

        self.module.run = encode
        with self.assertRaises(subprocess.CalledProcessError):
            self.import_captures()

        self.assertEqual(self.original, self.recordings())
        self.assertEqual(original_entries, {p.relative_to(self.root) for p in self.root.rglob("*")})
        self.module.build_visuals.assert_not_called()

    def test_successful_import_keeps_the_previous_set_until_all_encodes_finish(self):
        visible_during_encoding = []

        def encode(*args):
            source = Path(args[args.index("-i") + 1])
            Path(args[-1]).write_bytes(source.read_bytes())
            visible_during_encoding.append(self.recordings())

        self.module.run = encode
        published_to_builder = []
        self.module.build_visuals.side_effect = lambda _: published_to_builder.append(self.recordings())
        self.import_captures()

        expected = {name: f"capture {name}".encode() for name in NAMES}
        self.assertEqual([self.original] * len(NAMES), visible_during_encoding)
        self.assertEqual(expected, self.recordings())
        self.assertEqual([expected], published_to_builder)

    def test_default_regeneration_does_not_import_over_the_canonical_recordings(self):
        self.module.run = Mock()
        with patch.object(sys, "argv", [str(SCRIPT)]):
            self.module.main()
        self.module.run.assert_not_called()
        self.assertEqual(self.original, self.recordings())
        self.module.build_visuals.assert_called_once()


if __name__ == "__main__":
    unittest.main()
