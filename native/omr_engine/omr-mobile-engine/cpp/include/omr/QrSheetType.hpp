#pragma once

#include <string>

namespace omr {

struct OmrQrSheetType {
  bool detected = false;
  std::string code;
  std::string templateId;
  int questionCount = 0;
  std::string status = "QR_MISSING";
};

// Resolve complete printed Tn:student-code:identifier payloads exactly. The
// identifier is either the current 8-character mixed-case value or a legacy
// uppercase S2 value.
// The mapping intentionally lives in the portable C++ core so every future
// host (Android, iOS, desktop, or server) uses the same routing contract.
class QrSheetTypeResolver {
public:
  static OmrQrSheetType resolve(const std::string& payload);
  // Template configuration has a bare code, never a printed credential.
  static OmrQrSheetType fromCode(const std::string& code);
};

} // namespace omr
