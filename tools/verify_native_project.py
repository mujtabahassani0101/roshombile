#!/usr/bin/env python3
"""Audit the project and deliverables for executable Flutter/Dart remnants."""
import json
from pathlib import Path
import re
import zipfile

IGNORED = {"build", ".gradle", ".kotlin", ".cxx", ".git", "__pycache__"}
LEGACY_FILES = {"pubspec.yaml", "pubspec.lock", ".metadata", ".flutter-plugins", ".flutter-plugins-dependencies"}
LEGACY_DIRECTORIES = {".dart_tool", ".flutter", "flutter_assets"}
SDK_REFERENCE = re.compile(r"import\s+io\.flutter\b|dev\.flutter\.|flutter\.sdk\s*=|\bFLUTTER_ROOT\b")

def audit_native_project(root: Path) -> dict:
    root = root.resolve()
    problems = []
    kotlin = 0
    archives = 0
    for path in root.rglob("*"):
        relative = path.relative_to(root)
        if any(part in IGNORED for part in relative.parts):
            continue
        if any(part in LEGACY_DIRECTORIES for part in relative.parts):
            problems.append(str(relative))
            continue
        if path.is_dir():
            continue
        if path.name in LEGACY_FILES or path.suffix == ".dart" or path.name.startswith("libflutter."):
            problems.append(str(relative))
        if "output" not in relative.parts and path.suffix in {".kt", ".kts", ".gradle", ".properties", ".xml"}:
            if SDK_REFERENCE.search(path.read_text(encoding="utf-8", errors="replace")):
                problems.append(str(relative)+": Flutter SDK dependency")
            if path.suffix == ".kt":
                kotlin += 1
        if path.suffix in {".apk", ".aab", ".zip"}:
            archives += 1
            with zipfile.ZipFile(path) as archive:
                if any(name.endswith(".dart") or "flutter_assets/" in name or name.endswith("/libflutter.so") or Path(name).name in LEGACY_FILES for name in archive.namelist()):
                    problems.append(str(relative)+": archived Flutter/Dart files")
    if problems:
        raise ValueError("Flutter/Dart remnants found:\n"+"\n".join(sorted(set(problems))[:50]))
    for required in ["gradlew", "settings.gradle.kts", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml"]:
        if not (root/required).is_file():
            raise ValueError("Missing native project file: "+required)
    return {"kotlinFiles": kotlin, "archivesChecked": archives, "dartFiles": 0, "flutterSdkDependencies": 0, "flutterRuntimeArchives": 0}

if __name__ == "__main__":
    print(json.dumps(audit_native_project(Path(__file__).resolve().parents[1]), indent=2))
