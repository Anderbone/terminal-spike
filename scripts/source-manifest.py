from hashlib import sha256
from pathlib import Path
from subprocess import check_output

def listed(*args: str) -> set[str]:
    raw = check_output(["git", "ls-files", "-z", *args])
    return {p.decode() for p in raw.split(b"\0") if p}

files = listed() | listed("--others", "--exclude-standard")
excluded_parts = {"build", ".gradle", ".idea", ".kotlin"}
files = {
    p for p in files
    if not p.startswith("plans/")
    and not (set(Path(p).parts) & excluded_parts)
}
manifest = sha256()
for name in sorted(files):
    path = Path(name)
    if not path.is_file():
        continue
    manifest.update(name.encode("utf-8"))
    manifest.update(b"\0")
    manifest.update(sha256(path.read_bytes()).digest())
print(manifest.hexdigest())
