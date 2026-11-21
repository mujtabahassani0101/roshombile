#pragma once

#include <string>
#include <vector>
#include "Geometry.hpp"
#include "OmrLiveResult.hpp"

namespace omr {

struct MobileOmrPageInput {
  int pageNumber = 1;
  std::string imagePath;
  std::vector<Point> anchorPointsPercent;
};

struct MobileOmrBatchRequest {
  std::vector<MobileOmrPageInput> pages;
  std::string templateJson;
  std::string optionsJson;
  std::string outputDirectory;
  std::string examCode;
  bool returnDebugImages = false;
  // Normalized images are not needed by normal grading flows. Keeping this off
  // removes an image encode and filesystem write for every processed page.
  bool returnNormalizedImages = false;
  int maxInputDimension = 2600;
  // Compact mode keeps summaries and selectedAnswers but omits the 120-item
  // per-question array, reducing mobile bridge serialization and allocation.
  bool includeQuestions = true;
  bool qrOnly = false;
  // The scanner plug-in uses a single-image contract that exposes only OMR
  // answers, student code, QR result, and the quality decision. The legacy
  // batch contract remains available to existing import flows.
  bool minimalResult = false;
};

struct MobileOmrBatchResult {
  bool success = false;
  std::string status = "ERROR";
  std::string json;
  std::vector<NormalizeResult> sheets;
  std::vector<std::string> errors;
};

class MobileOmrBatchProcessor {
public:
  static MobileOmrBatchResult process(const MobileOmrBatchRequest& request);
  static MobileOmrBatchResult processJson(const std::string& requestJson);
};

} // namespace omr
