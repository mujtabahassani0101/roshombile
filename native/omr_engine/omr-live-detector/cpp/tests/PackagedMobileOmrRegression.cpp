#include <cstdlib>
#include <iostream>
#include <sstream>
#include <string>

#include "omr/MobileOmrC.h"

namespace {

constexpr const char* kRuntimeDirectory = "/data/local/tmp/omr-bench";

bool require(bool condition, const std::string& message) {
  if (!condition) {
    std::cerr << "FAIL: " << message << '\n';
  }
  return condition;
}

bool contains(const std::string& value, const std::string& expected) {
  return value.find(expected) != std::string::npos;
}

size_t occurrences(const std::string& value, const std::string& expected) {
  size_t count = 0;
  size_t position = 0;
  while ((position = value.find(expected, position)) != std::string::npos) {
    ++count;
    position += expected.size();
  }
  return count;
}

} // namespace

int main() {
  std::ostringstream request;
  request
    << "{\"templateJsonPath\":\"" << kRuntimeDirectory
    << "/lajvard_answer_sheet_120q_qr_template_v2.json\"," 
    << "\"outputDirectory\":\"" << kRuntimeDirectory << "\"," 
    << "\"returnDebugImages\":false,"
    << "\"returnNormalizedImages\":true,"
    << "\"compactResult\":false,"
    << "\"includeQuestions\":true,"
    << "\"maxInputDimension\":1800,"
    << "\"pages\":[{\"pageNumber\":1,\"imagePath\":\""
    << kRuntimeDirectory << "/omr_production_twenty_answers_input.jpg\"},"
    << "{\"pageNumber\":2,\"imagePath\":\""
    << kRuntimeDirectory << "/omr_production_twenty_answers_input.jpg\"}]}";

  const char* raw = omr_mobile_process_json(request.str().c_str());
  const std::string response = raw == nullptr ? "" : raw;
  omr_mobile_free_string(raw);

  bool ok = true;
  ok &= require(omr_mobile_api_version() == 2, "packaged API version must be 2");
  ok &= require(
    std::string(omr_mobile_engine_revision()) ==
      "omr-mobile-api-v2-qr-r6-multipage",
    "packaged engine revision must match the Flutter contract"
  );
  ok &= require(contains(response, "\"status\":\"OK\""), "packaged request must succeed");
  ok &= require(contains(response, "\"sheetCount\":2"), "both scanned sheets must be returned");
  ok &= require(contains(response, "\"successfulSheetCount\":2"), "both scanned sheets must be processed");
  ok &= require(contains(response, "\"page\":2"), "second page must remain available in results");
  ok &= require(
    occurrences(response, "\"answerSummary\":{\"total\":120,\"marked\":20,\"blank\":100,\"multi\":0,\"ambiguous\":0}") == 2,
    "both packaged sheet results must preserve exactly twenty marks"
  );
  ok &= require(
    occurrences(response, "\"studentId\":\"012345\"") == 2,
    "both packaged sheet results must preserve the six-digit student code"
  );

  for (int question = 1; question <= 20; ++question) {
    const int expectedOption = ((question - 1) % 4) + 1;
    std::ostringstream expectedQuestion;
    expectedQuestion
      << "\"question\":" << question
      << ",\"status\":\"MARKED\",\"selectedOption\":\""
      << expectedOption << "\",\"selectedOptionNumber\":" << expectedOption;
    ok &= require(
      occurrences(response, expectedQuestion.str()) == 2,
      "both packaged sheet results must preserve question " + std::to_string(question)
    );
  }

  if (!ok) {
    std::cerr << "Response prefix: " << response.substr(0, 800) << '\n';
    return EXIT_FAILURE;
  }

  std::cout << "Packaged mobile OMR regression passed: 2 sheets, 20/20 choices exact\n";
  return EXIT_SUCCESS;
}
