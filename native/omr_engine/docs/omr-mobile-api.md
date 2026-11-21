# OMR Mobile Core API

## C ABI

```c
int omr_mobile_api_version(void);
const char* omr_mobile_process_json(const char* request_json);
void omr_mobile_free_string(const char* value);
```

`omr_mobile_process_json` returns a heap-allocated UTF-8 JSON string. The caller must release it with `omr_mobile_free_string`.
`omr_mobile_api_version()` returns `2` and does not allocate memory.

## Request JSON

The native module passes rendered page image paths to the C++ core:

```json
{
  "templateJsonPath": "/app/cache/lajvard_answer_sheet_120q_qr_v2.json",
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

- `templateJson` or `templateJsonPath`: required. Prefer `templateJsonPath` on mobile.
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

Single-image requests may use a root-level `imagePath` or `imageUri`. A nested
`pages[].imagePath` never selects single-image mode. Batch output has one sheet
record per input page, even when that page fails; callers must not discard
unmatched originals if the response is incomplete.

## Result JSON

The core returns one JSON string:

```json
{
  "schemaVersion": 2,
  "success": true,
  "status": "OK",
  "examCode": "EXAM-123",
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
      "studentId": "01234",
      "examCode": "EXAM-123",
      "normalizedImagePath": null,
      "qrCode": {
        "found": true,
        "attempted": true,
        "status": "FOUND",
        "text": "EXAM-123",
        "format": "QRCode",
        "confidence": 1,
        "error": "",
        "skippedReason": ""
      },
      "studentCode": {
        "available": true,
        "complete": true,
        "status": "COMPLETE",
        "value": "01234",
        "rawValue": "01234",
        "reason": "",
        "summary": {
          "columns": 5,
          "marked": 5,
          "blank": 0,
          "multi": 0,
          "ambiguous": 0
        }
      },
      "answerSummary": {
        "total": 120,
        "marked": 118,
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

### Optional live page progress (r24)

Add a unique, opaque `progressId` (1–64 ASCII letters, digits or hyphens) to a
batch JSON request. While `omr_mobile_process_json` runs on a worker, call
`omr_mobile_progress_json(progressId)` concurrently to obtain a snapshot.
Free each returned snapshot with `omr_mobile_free_string`. After processing
returns, including failures, call `omr_mobile_release_progress(progressId)`.
Unknown/released tokens return `{}`. Clients that omit the token incur no
telemetry registration; existing C ABI version 2 requests remain supported.

Snapshots contain only `pages: [{page, type, state}]`. The type stays null until
header routing. States are `header_queued`, `header`, `queued`, `processing`,
`done` and `failed`. Progress counts completed pages, not a guessed percentage
inside a page. No paths, QR contents, identities or answers are included.
Tokens must be unique across concurrent calls. The registry is bounded to 64
entries and can reclaim abandoned completed entries; UI clients should always
release their token. Telemetry does not change result JSON or queue scheduling.
