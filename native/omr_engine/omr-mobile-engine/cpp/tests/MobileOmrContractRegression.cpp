#include <cstdlib>
#include <iostream>
#include <string>

#include "omr/MobileOmrC.h"

namespace {

bool contains(const std::string& value, const std::string& fragment) {
  return value.find(fragment) != std::string::npos;
}

bool expect(bool condition, const std::string& message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << "\n";
  }
  return condition;
}

std::string callApi(const char* requestJson) {
  const char* response = omr_mobile_process_json(requestJson);
  const std::string copy = response == nullptr ? "" : response;
  omr_mobile_free_string(response);
  return copy;
}

} // namespace

int main() {
  bool passed = true;

  passed &= expect(omr_mobile_api_version() == 2, "C ABI version must match schemaVersion 2");
  passed &= expect(
    std::string(omr_mobile_engine_revision()) == "omr-mobile-api-v2-four-engines-r26-highest-fill",
    "engine revision must invalidate cached results after the four-engine split"
  );

  const std::string nullRequest = callApi(nullptr);
  passed &= expect(contains(nullRequest, "\"schemaVersion\":2"), "errors must retain schemaVersion");
  passed &= expect(contains(nullRequest, "\"status\":\"ERROR\""), "null request must be an error");
  passed &= expect(
    contains(nullRequest, "\"errors\":[\"template_json_missing\"]"),
    "null request must expose a stable errors array"
  );
  passed &= expect(
    contains(nullRequest, "\"error\":\"template_json_missing\""),
    "error response must keep the legacy single error field"
  );
  passed &= expect(contains(nullRequest, "\"sheets\":[]"), "errors must include an empty sheets array");

  const std::string allPagesFailed = callApi(
    "{"
    "\"templateJsonPath\":\"" OMR_TEST_TEMPLATE_PATH "\","
    "\"compactResult\":true,"
    "\"returnNormalizedImages\":false,"
    "\"pages\":[{\"pageNumber\":1,\"imagePath\":\"/path/that/does/not/exist.jpg\"}]"
    "}"
  );
  passed &= expect(
    contains(allPagesFailed, "\"status\":\"ERROR\""),
    "a batch where no page succeeds must be ERROR, not PARTIAL"
  );
  passed &= expect(contains(allPagesFailed, "\"successfulSheetCount\":0"), "success count must be present");
  passed &= expect(contains(allPagesFailed, "\"failedSheetCount\":1"), "failure count must be present");
  passed &= expect(contains(allPagesFailed, "\"compact\":true"), "compact request must be reflected in response");
  passed &= expect(
    contains(allPagesFailed, "\"normalizedImagePath\":null"),
    "normalized image path must be explicit when image persistence is disabled"
  );
  passed &= expect(
    !contains(allPagesFailed, "\":,"),
    "sheet serialization must not emit a missing JSON value"
  );

  const std::string multiTemplateMissingImage = callApi(
    "{"
    "\"templateJsonPaths\":["
      "\"" OMR_TEST_TEMPLATE_PATH "\","
      "\"" OMR_TEST_T2_TEMPLATE_PATH "\","
      "\"" OMR_TEST_T3_TEMPLATE_PATH "\""
    "],"
    "\"imagePath\":\"/path/that/does/not/exist.jpg\""
    "}"
  );
  passed &= expect(
    contains(multiTemplateMissingImage, "image_load_failed"),
    "multi-template QR preflight must preserve image loading failures"
  );
  passed &= expect(
    !contains(multiTemplateMissingImage, "sheet_type_qr_missing_or_invalid"),
    "image loading failures must not be mislabeled as missing sheet-type QR data"
  );

  const std::string minimalScanFailed = callApi(
    "{"
    "\"templateJsonPath\":\"" OMR_TEST_TEMPLATE_PATH "\","
    "\"resultMode\":\"minimal\","
    "\"imagePath\":\"/path/that/does/not/exist.jpg\""
    "}"
  );
  passed &= expect(contains(minimalScanFailed, "\"schemaVersion\":1"), "minimal scans must use schema v1");
  passed &= expect(contains(minimalScanFailed, "\"questions\":[]"), "failed minimal scans must return an empty question list");
  passed &= expect(contains(minimalScanFailed, "\"qrCode\":"), "minimal scans must expose the QR result");
  passed &= expect(contains(minimalScanFailed, "\"studentCode\":"), "minimal scans must expose the student-code result");
  passed &= expect(contains(minimalScanFailed, "\"sheetType\":"), "minimal scans must expose the QR sheet type");
  passed &= expect(contains(minimalScanFailed, "\"activeQuestionCount\":"), "minimal scans must expose the active question count");
  passed &= expect(!contains(minimalScanFailed, "\"sheets\":"), "minimal scans must not expose the legacy batch envelope");
  passed &= expect(!contains(minimalScanFailed, "\"barcode\":"), "minimal scans must not expose the legacy barcode alias");

  return passed ? EXIT_SUCCESS : EXIT_FAILURE;
}
