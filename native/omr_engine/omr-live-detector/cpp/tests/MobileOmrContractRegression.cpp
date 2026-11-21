#include <cstdlib>
#include <iostream>
#include <string>

#include "omr/MobileOmrC.h"
#include "utils/Json.hpp"

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
    std::string(omr_mobile_engine_revision()) == "omr-mobile-api-v2-qr-r6-multipage",
    "engine revision must identify the multi-page parsing fix"
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

  const std::string twoPagesFailed = callApi(
    "{"
    "\"templateJsonPath\":\"" OMR_TEST_TEMPLATE_PATH "\","
    "\"returnNormalizedImages\":false,"
    "\"pages\":["
    "{\"pageNumber\":7,\"imagePath\":\"/missing/student-one.jpg\"},"
    "{\"pageNumber\":9,\"imagePath\":\"/missing/student-two.jpg\"}"
    "]}"
  );
  passed &= expect(contains(twoPagesFailed, "\"sheetCount\":2"), "two inputs must return two sheet records");
  passed &= expect(contains(twoPagesFailed, "\"failedSheetCount\":2"), "failed pages must not disappear");
  passed &= expect(contains(twoPagesFailed, "\"page\":7"), "first requested page number must survive parsing");
  passed &= expect(contains(twoPagesFailed, "\"page\":9"), "second requested page number must survive parsing");
  passed &= expect(contains(twoPagesFailed, "page_9:"), "second page must actually reach the engine");

  const std::string nestedPaths = "{\"pages\":[{\"imagePath\":\"first.jpg\"},{\"imagePath\":\"second.jpg\"}]}";
  passed &= expect(!omr::json::topLevelStringForKey(nestedPaths, "imagePath").has_value(), "nested path must not select single-image mode");
  passed &= expect(
    omr::json::topLevelStringForKey("{\"metadata\":{\"imagePath\":\"wrong.jpg\"},\"imagePath\":\"right.jpg\"}", "imagePath").value_or("") == "right.jpg",
    "single-image mode must use only an actual root property"
  );

  const std::string minimalBatch = callApi(
    "{\"templateJsonPath\":\"" OMR_TEST_TEMPLATE_PATH "\",\"resultMode\":\"minimal\","
    "\"pages\":[{\"imagePath\":\"one.jpg\"},{\"imagePath\":\"two.jpg\"}]}"
  );
  passed &= expect(contains(minimalBatch, "single_image_required"), "minimal mode must reject a batch, not silently select its first page");

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
  passed &= expect(!contains(minimalScanFailed, "\"sheets\":"), "minimal scans must not expose the legacy batch envelope");
  passed &= expect(!contains(minimalScanFailed, "\"barcode\":"), "minimal scans must not expose the legacy barcode alias");

  return passed ? EXIT_SUCCESS : EXIT_FAILURE;
}
