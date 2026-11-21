# OMR Mobile Core API

## C ABI

```c
int omr_mobile_api_version(void);
const char* omr_mobile_engine_revision(void);
const char* omr_mobile_process_json(const char* request_json);
void omr_mobile_free_string(const char* value);
```

`omr_mobile_process_json` returns a heap-allocated UTF-8 JSON string. The caller must release it with `omr_mobile_free_string`.
`omr_mobile_api_version()` returns `2` and does not allocate memory.

## QR sheet-type contract

After geometry normalization, QR decoding is the first semantic read. The
case-sensitive prefix selects the active question count before answers are
classified: `T1` = 100, `T2` = 60, and `T3` = 40. Any content may follow the
prefix. `T1`, `T2`, and `T3` are bound respectively to
`yse-omr-a5-t1-100q-v2`, `yse-omr-a5-t2-60q-v1`, and
`yse-omr-a5-t3-40q-v1`. With `templateJsonPaths`, the engine performs a QR-only
preflight and selects the matching layout before classifying answers. A
recognized code whose matching template is absent returns
`UNSUPPORTED_SHEET_TYPE`; a missing or invalid prefix returns
`SHEET_TYPE_REQUIRED`. Templates not bound to one of these three sheet families
are rejected before page processing.

## Request JSON

The native module passes rendered page image paths to the C++ core:

```json
{
  "templateJsonPaths": [
    "/app/cache/yse_omr_a5_t1_100q_v2.json",
    "/app/cache/yse_omr_a5_t2_60q_v1.json",
    "/app/cache/yse_omr_a5_t3_40q_v1.json"
  ],
  "optionsJsonPath": "/app/cache/omr_options.json",
  "outputDirectory": "/app/cache/omr-output",
  "examCode": "OPTIONAL-EXAM-CODE",
  "returnDebugImages": false,
  "returnNormalizedImages": false,
  "compactResult": true,
  "maxInputDimension": 2600,
  "pages": [
    {
      "pageNumber": 1,
      "imagePath": "/app/cache/pdf-pages/page-1.png"
    },
    {
      "pageNumber": 2,
      "imagePath": "/app/cache/pdf-pages/page-2.png"
    }
  ]
}
```

Supported fields:

- `templateJsonPaths`: recommended array of candidate template paths. The QR
  selects exactly one before answer classification.
- `templateJson` or `templateJsonPath`: supported single-layout alternative.
  At least one template source is required.
- `optionsJson` or `optionsJsonPath`: optional.
- `outputDirectory`: optional but recommended. Use app cache.
- `examCode`: optional override. If omitted, a valid QR payload is used when found.
- `returnDebugImages`: optional, default `false`.
- `returnNormalizedImages`: optional, default `false`. When disabled, the core
  skips normalized-image encoding and output I/O.
- `compactResult`: optional, default `false` at the raw C API. Use `true` for
  grading; the TypeScript SDK defaults it to `true`.
- `includeQuestions`: optional. When present, it overrides `compactResult`.
- `maxInputDimension`: optional, default `2600`, clamped to `1800..4096`.
- `pages`: required rendered image pages.
- `pages[].pageNumber`: optional, defaults to array index + 1.
- `pages[].imagePath` or `pages[].uri`: required.
- `pages[].anchorPointsPercent`: optional flat number array `[x1,y1,x2,y2,x3,y3,x4,y4]`.
- `pages[].anchors`: optional object array `[{ "x": 95.1, "y": 40.8 }, ...]`.

## Result JSON

The core returns one JSON string:

```json
{
  "schemaVersion": 2,
  "success": true,
  "status": "OK",
  "examCode": "T1:EXAM-123",
  "examCodeSource": "qr_code",
  "sheetCount": 2,
  "successfulSheetCount": 2,
  "failedSheetCount": 0,
  "reviewRequired": false,
  "reviewSheetCount": 0,
  "compact": true,
  "processingTimeMs": 612.8,
  "errors": [],
  "sheets": [
    {
      "page": 1,
      "success": true,
      "status": "NORMALIZED",
      "reviewRequired": false,
      "reviewReasons": [],
      "error": "",
      "studentId": null,
      "examCode": "T1:EXAM-123",
      "normalizedImagePath": null,
      "qrCode": {
        "found": true,
        "attempted": true,
        "status": "FOUND",
        "text": "T1:EXAM-123",
        "format": "QRCode",
        "confidence": 1,
        "error": "",
        "skippedReason": ""
      },
      "studentCode": {
        "available": false,
        "complete": false,
        "status": "UNAVAILABLE",
        "value": null,
        "rawValue": null,
        "reason": "student_code_template_missing",
        "summary": {
          "columns": 0,
          "marked": 0,
          "blank": 0,
          "multi": 0,
          "ambiguous": 0
        }
      },
      "sheetType": {
        "detected": true,
        "code": "T1",
        "templateId": "yse-omr-a5-t1-100q-v2",
        "questionCount": 100,
        "status": "DETECTED"
      },
      "activeQuestionCount": 100,
      "answerSummary": {
        "total": 100,
        "marked": 98,
        "blank": 2,
        "multi": 0,
        "ambiguous": 0
      },
      "selectedAnswers": {
        "1": 2,
        "2": 4
      },
      "questions": [],
      "warnings": [],
      "processingTimeMs": 321.4
    }
  ]
}
```

Each sheet also includes a deprecated `barcode` object containing the same data as
`qrCode`. It is retained only so existing mobile clients can migrate without a
breaking response change. New clients should read `qrCode` and only accept it when
`found=true` and `status="FOUND"`.

Top-level `status` values:

- `OK`: every sheet succeeded.
- `PARTIAL`: at least one page succeeded and at least one page failed.
- `ERROR`: invalid request or no page succeeded.

Per-sheet `status` may also be `UNSUPPORTED_SHEET_TYPE` when the QR prefix is
recognized but the configured physical template does not match it. In that
case no answer bubbles are classified and `reviewRequired` is true.
`SHEET_TYPE_REQUIRED` likewise stops classification when a production template
does not receive a valid recognized QR prefix.

Error responses retain the same schema-v2 envelope and always include `errors`
and `sheets`, so clients do not need a separate parser for native failures. The
single `error` field is also retained for older clients; new code should read the
`errors` array.

Per-question `status` values:

- `MARKED`
- `BLANK`
- `MULTI`
- `AMBIGUOUS`

## C++ API

If the native module is C++/Objective-C++ friendly, it can call the typed API directly:

```cpp
#include "omr/MobileOmrBatch.hpp"

omr::MobileOmrBatchRequest request;
request.templateJson = templateJson;
request.outputDirectory = cacheDir;
request.pages.push_back({1, pageOnePngPath, {}});

const omr::MobileOmrBatchResult result =
  omr::MobileOmrBatchProcessor::process(request);

std::string json = result.json;
```
