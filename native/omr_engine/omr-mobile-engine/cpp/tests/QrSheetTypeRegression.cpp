#include <cstdlib>
#include <iostream>
#include <string>

#include "omr/QrSheetType.hpp"

namespace {

bool expect(bool condition, const std::string& message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << "\n";
  }
  return condition;
}

bool expectDetected(
  const std::string& payload,
  const std::string& expectedCode,
  int expectedQuestionCount,
  const std::string& expectedTemplateId = ""
) {
  const omr::OmrQrSheetType result = omr::QrSheetTypeResolver::resolve(payload);
  bool passed = true;
  passed &= expect(result.detected, payload + " must be detected");
  passed &= expect(result.status == "DETECTED", payload + " must use DETECTED status");
  passed &= expect(result.code == expectedCode, payload + " must preserve the routing code");
  passed &= expect(
    result.templateId == expectedTemplateId,
    payload + " must resolve the expected physical template"
  );
  passed &= expect(
    result.questionCount == expectedQuestionCount,
    payload + " must resolve the expected question count"
  );
  return passed;
}

} // namespace

int main() {
  bool passed = true;
  passed &= expectDetected("T1:001234:S2000000000000000000", "T1", 100, "yse-omr-a5-t1-100q-v2");
  passed &= expectDetected("T2:123456:S2EAE97QMWMFX0ENW5FX", "T2", 60, "yse-omr-a5-t2-60q-v1");
  passed &= expectDetected("T3:001234:S2000000000000000000", "T3", 40, "yse-omr-a5-t3-40q-v1");
  passed &= expectDetected("T3:213327:rZeGOHtv", "T3", 40, "yse-omr-a5-t3-40q-v1");
  passed &= expect(omr::QrSheetTypeResolver::fromCode("T2").questionCount == 60,
    "bare codes remain valid template metadata only");
  for (const std::string malformed : {
      "T1", "T1:exam-2026", "T2|student-token", "T3-any-following-content",
      "T20:123456:S2EAE97QMWMFX0ENW5FX", "T2:123456:S2EAE97QMWMFX0ENW5FX:extra",
      "T2:123456:S2EAE97QMWMFX0ENW5FX ", "T2:123456:S2EAE97QMWMFX0ENW5FX\n",
      "T2: 123456:S2EAE97QMWMFX0ENW5FX", "T2::S2EAE97QMWMFX0ENW5FX",
      "T2:123456:s2eae97qmwmfx0enw5fx", "T2:123456:C2EAE97QMWMFX0ENW5FX",
      "T3:213327:rZeGOHt", "T3:213327:rZeGOHtv9", "T3:213327:rZeGOH_v"}) {
    const auto rejected = omr::QrSheetTypeResolver::resolve(malformed);
    passed &= expect(!rejected.detected && rejected.questionCount == 0 && rejected.code.empty(),
      "malformed printed QR must never select a layout");
  }

  const omr::OmrQrSheetType missing = omr::QrSheetTypeResolver::resolve("");
  passed &= expect(!missing.detected, "empty QR payload must not be detected");
  passed &= expect(missing.status == "QR_MISSING", "empty QR payload must report QR_MISSING");
  passed &= expect(missing.questionCount == 0, "missing QR must not select a question count");

  const omr::OmrQrSheetType unsupported = omr::QrSheetTypeResolver::resolve("x1:exam-2026");
  passed &= expect(!unsupported.detected, "unknown prefix must not be detected");
  passed &= expect(
    unsupported.status == "UNSUPPORTED_PREFIX",
    "unknown prefix must report UNSUPPORTED_PREFIX"
  );
  passed &= expect(unsupported.code.empty(), "unknown prefix must not expose a routing code");
  passed &= expect(unsupported.questionCount == 0, "unknown prefix must not select a question count");

  const omr::OmrQrSheetType lowercase = omr::QrSheetTypeResolver::resolve("t1:exam-2026");
  passed &= expect(!lowercase.detected, "lowercase sheet-type prefixes must be rejected");

  return passed ? EXIT_SUCCESS : EXIT_FAILURE;
}
