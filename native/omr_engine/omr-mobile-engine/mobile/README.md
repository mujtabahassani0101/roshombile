# Lajvard OMR mobile native package

The mobile package links the same `lajvard_omr_core` used by the desktop runner.
Answer scoring, student-code order, production-safe geometry fallback, and review
gates therefore have one implementation on every platform.

The active templates are calibrated for the supplied A5 T1, T2, and T3 sheets:
100 questions in four 25-row blocks, 60 questions in three 20-row blocks, and
40 questions in two 20-row blocks. These sheets have no student-code bubble
grid, so `studentCode.available` is false. The portable engine's student-code
reader remains available for future templates.

## QR sheet-type routing

The portable C++ core reads the QR before bubble classification. The first two
bytes of a valid QR payload select the active question count:

| Prefix | Active questions |
| --- | --- |
| `T1` | 100 |
| `T2` | 60 |
| `T3` | 40 |

The prefix is uppercase and case-sensitive. Any content may follow it, for
example `T2:exam-2026:student-token`. In multi-template mode, a missing,
lowercase, or unrecognized prefix stops processing with `SHEET_TYPE_REQUIRED`.
A recognized type without its matching supplied template stops with
`UNSUPPORTED_SHEET_TYPE`. Unbound physical templates are rejected during
request validation.

Each sheet result exposes both `sheetType` and `activeQuestionCount`. Questions
above the active count are not classified or returned. This routing belongs to
the engine and does not depend on Android, Expo, or any mobile-app code.

## Native ABI

```c
int omr_mobile_api_version(void);
const char* omr_mobile_engine_revision(void);
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
  "templateJsonPaths": [
    "/app/assets/yse_omr_a5_t1_100q_v2.json",
    "/app/assets/yse_omr_a5_t2_60q_v1.json",
    "/app/assets/yse_omr_a5_t3_40q_v1.json"
  ],
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

For a QR payload beginning with `T1`, the sheet result includes:

```json
{
  "sheetType": {
    "detected": true,
    "code": "T1",
    "templateId": "yse-omr-a5-t1-100q-v2",
    "questionCount": 100,
    "status": "DETECTED"
  },
  "activeQuestionCount": 100
}
```

## Host build / smoke test

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --parallel
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
