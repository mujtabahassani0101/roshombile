#include "omr/QrSheetType.hpp"

#include <array>

namespace omr {

namespace {

struct SheetTypeDefinition {
  const char* code;
  int questionCount;
  const char* templateId;
};

constexpr std::array<SheetTypeDefinition, 3> kSheetTypes{{
  {"T1", 100, "yse-omr-a5-t1-100q-v2"},
  {"T2", 60, "yse-omr-a5-t2-60q-v1"},
  {"T3", 40, "yse-omr-a5-t3-40q-v1"},
}};

} // namespace

OmrQrSheetType QrSheetTypeResolver::fromCode(const std::string& code) {
  OmrQrSheetType result;
  if (code.empty()) {
    return result;
  }

  result.status = "UNSUPPORTED_PREFIX";
  for (const auto& definition : kSheetTypes) {
    if (code != definition.code) {
      continue;
    }
    result.detected = true;
    result.code = definition.code;
    result.templateId = definition.templateId;
    result.questionCount = definition.questionCount;
    result.status = "DETECTED";
    return result;
  }
  return result;
}

OmrQrSheetType QrSheetTypeResolver::resolve(const std::string& payload) {
  OmrQrSheetType rejected;
  if (payload.empty()) return rejected;
  rejected.status = "UNSUPPORTED_PREFIX";
  const auto binding = fromCode(payload.substr(0, 2));
  if (!binding.detected || payload.size() < 3 || payload[2] != ':') return rejected;
  rejected.status = "INVALID_PAYLOAD";
  const auto separator = payload.find(':', 3);
  if (separator == std::string::npos || separator <= 3 || separator > 35 ||
      payload.find_first_not_of("0123456789", 3) != separator) return rejected;
  const auto identifier = payload.substr(separator + 1);
  const bool legacyIdentifier = identifier.size() == 20 &&
    identifier.rfind("S2", 0) == 0 &&
    identifier.find_first_not_of("0123456789ABCDEFGHJKMNPQRSTVWXYZ", 2) == std::string::npos;
  const bool compactIdentifier = identifier.size() == 8 &&
    identifier.find_first_not_of(
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    ) == std::string::npos;
  if (!legacyIdentifier && !compactIdentifier) return rejected;
  return binding;
}

} // namespace omr
