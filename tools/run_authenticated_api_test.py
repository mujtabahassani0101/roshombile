#!/usr/bin/env python3
"""Run opt-in JVM HTTPS account checks without saving or echoing login secrets."""
import getpass
import argparse
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--bundle", action="store_true", help="Also download round 1 with disposable signing keys, then revoke the test device")
parser.add_argument("--batch-validation", action="store_true", help="Check authenticated rejection of an empty scan batch; no student scan is submitted")
parser.add_argument("--log", type=Path, default=Path("qa/authenticated-api-test.log"), help="Build log path (no credentials are logged)")
arguments = parser.parse_args()
username = getpass.getpass("Test username (hidden): ")
password = getpass.getpass("Test password (hidden): ")
environment = os.environ.copy()
environment.update(ROSHD_AUTH_LIVE_TEST="1", ROSHD_TEST_USERNAME=username, ROSHD_TEST_PASSWORD=password)
if arguments.bundle:
    environment["ROSHD_AUTH_BUNDLE_TEST"] = "1"
if arguments.batch_validation:
    environment["ROSHD_AUTH_BATCH_TEST"] = "1"
command = [str(root / "gradlew"), ":app:testDebugUnitTest", "--tests", "*AuthenticatedRoshdApiTest", "--rerun",
           "--no-daemon", "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8",
           "-Pkotlin.compiler.execution.strategy=in-process", "--console=plain"]
log_path = arguments.log if arguments.log.is_absolute() else root / arguments.log
log_path.parent.mkdir(parents=True, exist_ok=True)
with log_path.open("w") as log:
    completed = subprocess.run(command, cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT)
environment.pop("ROSHD_TEST_PASSWORD", None)
environment.pop("ROSHD_TEST_USERNAME", None)
print("Authenticated test exit:", completed.returncode)
raise SystemExit(completed.returncode)
