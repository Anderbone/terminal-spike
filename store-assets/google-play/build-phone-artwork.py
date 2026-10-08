#!/usr/bin/env python3
"""Frame real Android captures. Requires the existing ImageMagick and librsvg tools."""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import tempfile
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent


def run(*args):
    subprocess.run(args, check=True)


def build(manifest_path, output):
    manifest = json.loads(manifest_path.read_text())
    output.mkdir(parents=True, exist_ok=True)
    # Remove only the three superseded generated files; original captures remain archived.
    for legacy in ('03-connections.png', '04-settings.png', '05-add-host.png'):
        if legacy not in {shot['output'] for shot in manifest['screenshots']}:
            (output / legacy).unlink(missing_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        work = Path(temporary)
        for index, shot in enumerate(manifest['screenshots'], 1):
            source = manifest_path.parent / shot['source']
            # Only remove Android system bars; the app UI is never reconstructed or retouched.
            run('magick', str(source), '-crop', shot['crop'], '+repage', '-strip', str(work / 'capture.png'))
            capture = base64.b64encode((work / 'capture.png').read_bytes()).decode()
            title = ''.join(f'<tspan x="72" dy="{0 if i == 0 else 96}">{escape(line)}</tspan>'
                            for i, line in enumerate(shot['headline']))
            svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1080" height="1920" viewBox="0 0 1080 1920">
<defs>
  <linearGradient id="bg" x2=".8" y2="1"><stop stop-color="#172723"/><stop offset="1" stop-color="#0b1110"/></linearGradient>
  <clipPath id="screen"><rect x="166" y="370" width="748" height="1522" rx="26"/></clipPath>
</defs>
<rect width="1080" height="1920" fill="url(#bg)"/>
<path d="M830 0V325M864 0V325M898 0V325" stroke="#c9f58b" stroke-opacity=".06" stroke-width="1"/>
<text x="74" y="55" fill="#bdd1c8" font-family="Liberation Sans" font-size="23" letter-spacing="4">TERMINAL SPIKE</text>
<text x="1008" y="55" fill="#bdd1c8" text-anchor="end" font-family="Liberation Sans" font-size="23">{index:02d}</text>
<text x="72" y="156" fill="#e7f5d5" font-family="Liberation Sans" font-weight="bold" font-size="88" letter-spacing="-2">{title}</text>
<text x="76" y="319" fill="#b6c7c0" font-family="Liberation Sans" font-size="30">{escape(shot['subtitle'])}</text>
<rect x="154" y="358" width="772" height="1546" rx="38" fill="#060b09" stroke="#42534a" stroke-width="2"/>
<image x="166" y="370" width="748" height="1522" preserveAspectRatio="xMidYMin meet" clip-path="url(#screen)" xlink:href="data:image/png;base64,{capture}"/>
</svg>'''
            (work / 'artwork.svg').write_text(svg)
            run('rsvg-convert', '-o', str(work / 'artwork.png'), str(work / 'artwork.svg'))
            run('magick', str(work / 'artwork.png'), '-strip', f'PNG24:{output / shot["output"]}')
    print(f'Built {len(manifest["screenshots"])} phone screenshots from supplied captures.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'sources/phone-s23/manifest.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'screenshots/phone')
    args = parser.parse_args()
    build(args.manifest, args.output)
