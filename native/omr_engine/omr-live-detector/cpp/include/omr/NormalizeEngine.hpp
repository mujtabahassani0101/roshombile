#pragma once

#include <string>
#include <vector>
#include "Geometry.hpp"
#include "OmrLiveOptions.hpp"
#include "OmrLiveResult.hpp"
#include "OmrTemplate.hpp"

namespace omr {

struct NormalizeRequest {
  std::string imagePath;
  std::string outputDirectory;
  bool returnDebugImages = false;
  // Desktop callers keep the normalized sheet by default. Mobile callers can
  // disable this to avoid a full-resolution image encode and filesystem write.
  bool persistNormalizedImage = true;
  // Optional memory guard for mobile captures. Zero keeps the original resolution.
  int maxInputDimension = 0;
  // Brightness/sharpness diagnostics do not participate in still-image OMR
  // decisions. Minimal mobile scans can skip them without changing detection.
  bool collectQualityMetrics = true;
  // Mixed-class routing needs only the exam QR. Skip bubble refinement,
  // student-code reading, answer reading, and normalized-image persistence.
  bool qrOnly = false;
  // Optional caller-supplied sheet corner markers in percentages of the input image.
  // Each point uses x/y in the 0..100 range. The normalizer converts them to pixels,
  // orders them, builds a homography, and runs answer reading on the warped sheet.
  std::vector<Point> anchorPointsPercent;
};

class NormalizeEngine {
public:
  void configure(const OmrTemplate& tpl, const OmrLiveOptions& opts);
  NormalizeResult normalize(const NormalizeRequest& request);

private:
  OmrTemplate template_;
  OmrLiveOptions options_;
};

} // namespace omr
