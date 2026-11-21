#!/bin/bash
# Fresh-install the side-by-side QA package and copy the private 1.1.52 inputs.
# The test phone has little free space, so the old QA package is removed first.
set -e
cd "$(dirname "$0")/.."
adb uninstall com.haseebullahjan.roshd.amaqa.test >/dev/null 2>&1 || true
adb uninstall com.haseebullahjan.roshd.amaqa >/dev/null 2>&1 || true
adb install app/build/outputs/apk/release/app-release.apk
adb install app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
D=/sdcard/Android/data/com.haseebullahjan.roshd.amaqa/files/qa/omr-speed-1154
adb shell mkdir -p $D/input
for n in 02 06 07 09 11; do
  adb shell mkdir -p $D/input/case-$n
  adb push qa/omr-speed-1.1.52/private/input/case-$n/source.jpg $D/input/case-$n/source.jpg >/dev/null
done
adb push qa/omr-speed-1.1.52/private/input/bindings.json $D/input/ >/dev/null
adb push qa/omr-speed-1.1.52/private/input/manual-labels.json $D/input/ >/dev/null
echo inputs-ready
