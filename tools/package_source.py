#!/usr/bin/env python3
"""Package a self-contained source tree without build caches or signing keys."""
import argparse
import hashlib
from pathlib import Path
import zipfile
from verify_native_project import audit_native_project

parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
output = args.output.resolve()
output.parent.mkdir(parents=True, exist_ok=True)
audit_native_project(root)
excluded = {"build", ".gradle", ".kotlin", ".cxx", ".git", "__pycache__", "output"}
files = sorted(
    path for path in root.rglob("*")
    if path.is_file() and path.resolve() != output
    and not excluded.intersection(path.relative_to(root).parts)
    and path.name != "local.properties"
    and path.suffix.lower() not in {".keystore", ".jks", ".apk", ".aab", ".pyc"}
)
prefix = "RoshdKotlin/"
hashes = []
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for path in files:
        name = path.relative_to(root).as_posix()
        archive.write(path, prefix + name)
        hashes.append(hashlib.sha256(path.read_bytes()).hexdigest() + "  " + name)
    archive.writestr(prefix + "SOURCE.sha256", "\n".join(hashes) + "\n")
with zipfile.ZipFile(output) as archive:
    assert archive.testzip() is None
    assert not any(name.endswith(".dart") or "libflutter.so" in name for name in archive.namelist())
print(f"{output}\n{len(files)} source / asset files; {output.stat().st_size:,} bytes")
