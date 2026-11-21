#!/usr/bin/env python3
"""Run the real native OMR engine on synthetic T1/T3 and eight JPEG EXIF orientations."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from PIL import Image

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--executable", type=Path, required=True)
parser.add_argument("--library-dir", type=Path)
parser.add_argument("--output", type=Path, default=root / "qa/native-benchmark-1.1.17.json")
args = parser.parse_args()
environment = os.environ.copy()
if args.library_dir:
    environment["LD_LIBRARY_PATH"] = str(args.library_dir.resolve()) + os.pathsep + environment.get("LD_LIBRARY_PATH", "")
measurements = []
with tempfile.TemporaryDirectory(prefix="roshd-camera-fixtures-") as directory:
    fixtures = Path(directory)
    for family in (1, 3):
        shutil.copyfile(root / f"app/src/androidTest/assets/omr/T{family}.jpg", fixtures / f"T{family}.jpg")
    image = Image.open(fixtures / "T1.jpg").convert("RGB")
    operations = {2: Image.Transpose.FLIP_LEFT_RIGHT, 3: Image.Transpose.ROTATE_180,
                  4: Image.Transpose.FLIP_TOP_BOTTOM, 5: Image.Transpose.TRANSPOSE,
                  6: Image.Transpose.ROTATE_90, 7: Image.Transpose.TRANSVERSE, 8: Image.Transpose.ROTATE_270}
    for orientation in range(1, 9):
        pixels = image.transpose(operations[orientation]) if orientation in operations else image
        exif = Image.Exif()
        exif[274] = orientation
        pixels.save(fixtures / f"T1-exif{orientation}.jpg", quality=97, exif=exif)
    for threads in (1, 2, 4):
        run = subprocess.run([str(args.executable.resolve()), str(root / "app/src/main/assets/templates"),
                              str(fixtures), str(threads)], env=environment, text=True, capture_output=True, check=True)
        row = json.loads(run.stdout.strip().splitlines()[-1])
        assert row["correctAnswers"] == 940 and row["errors"] == 0
        measurements.append(row)
report = {"platform": "Linux x86_64 host; not Android", "opencv": "host library; see build configuration (Android ships 4.12.0)",
          "synthetic_fixtures": True, "measurements": measurements}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
