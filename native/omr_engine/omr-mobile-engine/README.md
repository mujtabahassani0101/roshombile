# YSE - Young Scientist Mobile OMR Engine

Standalone, framework-neutral OMR engine intended for mobile devices. This
package contains the complete portable C++ processing core, C ABI, answer-sheet
templates, regression tests, and small reference bridges for Android and iOS.

It does **not** contain Expo, React Native, application UI, authentication,
networking, document pickers, PDF rendering, result delivery, or app storage.

## Processing boundary

```text
mobile host renders/captures an image
  -> liblajvard_omr_mobile
    -> orientation and sheet geometry
    -> QR decode and sheet-type routing
    -> bubble-grid refinement
    -> student code and answer classification
  -> versioned JSON result
```

The host must provide a local JPEG/PNG page path. PDF rendering remains the
responsibility of Android `PdfRenderer`, iOS `PDFKit`, or another host API.

## QR sheet types

The first two characters of the QR payload are case-sensitive:

| Prefix | Active questions |
| --- | --- |
| `T1` | 100 |
| `T2` | 60 |
| `T3` | 40 |

QR routing happens before bubble classification. The result exposes
`sheetType` and `activeQuestionCount`; questions above that count are not
classified or returned. Only the three bound production templates are accepted;
each requires a valid recognized prefix and stops before grading when it is
missing or invalid.

The active layouts are `templates/yse_omr_a5_t1_100q_v2.json`,
`templates/yse_omr_a5_t2_60q_v1.json`, and
`templates/yse_omr_a5_t3_40q_v1.json`. They bind uppercase `T1`, `T2`, and
`T3` to the supplied 100-, 60-, and 40-question A5 geometries. All have a
combined QR/header panel and no student-code bubble grid; the reusable engine
still retains student-code support for future templates.

Pass all three paths through `templateJsonPaths` to enable automatic routing.
The engine performs a QR-only preflight, chooses the matching physical layout,
and only then runs bubble refinement and classification. Supplying a single
`templateJsonPath` remains supported for fixed-layout integrations.

## Public ABI

```c
int omr_mobile_api_version(void);
const char* omr_mobile_engine_revision(void);
const char* omr_mobile_process_json(const char* request_json);
void omr_mobile_free_string(const char* value);
```

The returned UTF-8 string must be released with `omr_mobile_free_string`.
Current API version: `2`. Current engine revision:
`omr-mobile-api-v2-a5-t1-t2-t3-r11`.

See [API.md](docs/API.md) for the JSON request/result contract.

## Directory layout

```text
cpp/                 portable C++ core and regression tests
mobile/              C ABI library build
templates/           versioned answer-sheet geometry
bindings/android/    reference JNI bridge, no Expo dependency
bindings/ios/        reference Objective-C++ bridge
docs/                 API and calibration notes
```

## Android build

The consuming toolchain must support C++20.

```sh
cmake -S . -B build-android \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DOMR_OPENCV_INCLUDE_DIR=/path/to/OpenCV/sdk/native/jni/include \
  -DOMR_OPENCV_SHARED_DIR=/path/to/OpenCV/sdk/native/libs \
  -DOMR_ZXING_SOURCE_DIR=/path/to/zxing-cpp
cmake --build build-android --parallel
```

Outputs include `liblajvard_omr_mobile.so` and, by default on Android,
`libomr_scanner_jni.so`.

The package does not force Android ABIs. The consuming mobile build selects
`arm64-v8a`, `x86_64`, or another ABI supported by its OpenCV distribution.

## Host regression build

With host OpenCV and ZXing CMake packages installed:

```sh
cmake -S . -B build -DOMR_MOBILE_ENABLE_TESTS=ON
cmake --build build --parallel
ctest --test-dir build/mobile/lajvard_omr_core_build --output-on-failure
```

## Integration rule

Applications should depend on a packaged build of this directory. Do not copy
engine source into an app or add Expo/React Native dependencies to this package.
