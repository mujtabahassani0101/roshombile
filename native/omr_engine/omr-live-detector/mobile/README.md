# Lajvard OMR mobile native package

The mobile package links the same `lajvard_omr_core` used by the desktop runner.
Answer scoring, student-code order, production-safe geometry fallback, and review
gates therefore have one implementation on every platform.

The active template is calibrated for the supplied five-row student-code sheet:
positions are read top-to-bottom, with digit choices `9` through `0` laid out
left-to-right in each row.

## Native ABI

```c
int omr_mobile_api_version(void);
const char* omr_mobile_process_json(const char* request_json);
void omr_mobile_free_string(const char* value);
```

The returned UTF-8 string belongs to the library and must be released with
`omr_mobile_free_string`.

## Request

For camera capture, keep the original only when the app needs it for audit/review,
and create an app-private processing copy whose largest edge is about `2600 px`.
Pass that copy's path or `file://` URI. Pre-sizing before the native decode reduces
peak memory; `maxInputDimension` remains a second safety limit inside the core.
Debug files are disabled by default to reduce mobile I/O.

```json
{
  "templateJsonPath": "/app/assets/lajvard_answer_sheet_120q_qr_v2.json",
  "outputDirectory": "/app/cache/omr",
  "compactResult": true,
  "maxInputDimension": 2600,
  "returnDebugImages": false,
  "returnNormalizedImages": false,
  "pages": [
    {"pageNumber": 1, "imagePath": "/app/cache/capture.jpg"}
  ]
}
```

`compactResult=true` preserves `answerSummary`, `selectedAnswers`, student code,
confidence decisions, warnings, and review state while returning `questions: []`.
Use `includeQuestions=true` when a review UI needs every question status.

The batch response has `schemaVersion: 2`, `reviewRequired`, and
`reviewSheetCount`. A sheet with `reviewRequired=true` must not be finalized without
reviewing its `reviewReasons`.

## Host build / smoke test

```sh
cmake -S mobile -B mobile/build -DCMAKE_BUILD_TYPE=Release
cmake --build mobile/build -j2
```

This produces `liblajvard_omr_mobile.so` on Linux/Android-style builds. Android
builds should configure this project with the NDK toolchain and ABI. iOS builds
produce a static framework when configured with an iOS CMake toolchain.

OpenCV and ZXing must be supplied for the target ABI through the toolchain or
`CMAKE_PREFIX_PATH`.

## Mobile performance rules

- keep `returnDebugImages=false` outside diagnostic builds;
- keep `returnNormalizedImages=false` unless a review UI needs the warped image;
  this skips a PNG/JPEG encode and one full-image write per page;
- use `compactResult=true` for automatic grading flows;
- keep `maxInputDimension=2600` for ordinary camera captures; raise it only after
  device-specific accuracy and memory testing;
- pre-size camera/PDF images close to `maxInputDimension` before calling native
  code so the decoder never has to hold an unnecessarily large source bitmap;
- batch pages in one request so the template and options are parsed once;
- process off the UI thread;
- preserve the original capture file until the native call completes;
- reject or review results according to `reviewRequired`, not only `success`.
