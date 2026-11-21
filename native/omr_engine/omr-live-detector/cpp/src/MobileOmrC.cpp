#include "omr/MobileOmrC.h"

#include <cstring>
#include <exception>
#include <string>
#include "omr/MobileOmrBatch.hpp"

namespace {

const char* copyCString(const std::string& value) {
  char* out = new char[value.size() + 1];
  std::memcpy(out, value.c_str(), value.size() + 1);
  return out;
}

std::string jsonEscape(const std::string& value) {
  std::string escaped;
  escaped.reserve(value.size());
  for (const char ch : value) {
    if (ch == '\\') {
      escaped += "\\\\";
    } else if (ch == '"') {
      escaped += "\\\"";
    } else if (ch == '\n') {
      escaped += "\\n";
    } else if (ch == '\r') {
      escaped += "\\r";
    } else if (ch == '\t') {
      escaped += "\\t";
    } else if (static_cast<unsigned char>(ch) < 0x20) {
      const char* hex = "0123456789abcdef";
      const auto byte = static_cast<unsigned char>(ch);
      escaped += "\\u00";
      escaped += hex[(byte >> 4) & 0x0f];
      escaped += hex[byte & 0x0f];
    } else {
      escaped += ch;
    }
  }
  return escaped;
}

} // namespace

extern "C" const char* omr_mobile_process_json(const char* request_json) {
  try {
    const std::string request = request_json == nullptr ? std::string{} : std::string(request_json);
    const omr::MobileOmrBatchResult result = omr::MobileOmrBatchProcessor::processJson(request);
    return copyCString(result.json);
  } catch (const std::exception& error) {
    return copyCString(
      std::string("{\"schemaVersion\":2,\"success\":false,\"status\":\"ERROR\","
                  "\"examCode\":null,\"examCodeSource\":\"none\",\"sheetCount\":0,"
                  "\"successfulSheetCount\":0,\"failedSheetCount\":0,"
                  "\"reviewRequired\":false,\"reviewSheetCount\":0,\"compact\":false,"
                  "\"processingTimeMs\":0,\"error\":\"") +
      jsonEscape(error.what()) +
      "\",\"errors\":[\"" + jsonEscape(error.what()) + "\"],\"sheets\":[]}"
    );
  }
}

extern "C" void omr_mobile_free_string(const char* value) {
  delete[] value;
}

extern "C" int omr_mobile_api_version(void) {
  return 2;
}

extern "C" const char* omr_mobile_engine_revision(void) {
  return "omr-mobile-api-v2-qr-r6-multipage";
}
